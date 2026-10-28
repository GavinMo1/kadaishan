package dev.sxc.kadaishan.domain.session

import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.domain.selection.SelectionRow
import dev.sxc.kadaishan.model.*
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SmartSessionTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private class Source : MediaSource {
        val rows = (1L..25).map { SelectionRow(it, ImageCategory.CAMERA, "camera", 1_700_000_000_000L, 0) }
        var selectionCalls = 0
        var idsCalls = 0
        var lastQuery: MediaQuery? = null
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override suspend fun selectionRows(query: MediaQuery): List<SelectionRow> {
            selectionCalls++; lastQuery = query; entered.complete(Unit)
            gate?.await()
            failure?.let { throw it }
            return rows
        }
        override suspend fun ids(query: MediaQuery): List<Long> { idsCalls++; lastQuery = query; return rows.map { it.id } }
        override suspend fun page(query: MediaQuery, after: Long?, limit: Int) = Page<MediaItem>(emptyList(), null)
        override suspend fun queryByIds(ids: List<Long>, matchTrashed: Int) = emptyList<MediaItem>()
    }
    private fun controller(source: Source, board: Board = Board.PHOTO, history: ReviewHistory? = null) =
        SessionController(source, board, 7, random = Random(7), history = history, clock = { now }, zone = { ZoneId.of("UTC") }).apply {
            applySettings(SortSpec.Smart, true)
        }

    @Test fun `smart excludes only explicit keeps and preserves order across rounds and undo`() = runBlocking {
        val source = Source()
        val history = object : ReviewHistory {
            override suspend fun keptIds() = setOf(1L, 10L)
            override suspend fun setKept(ids: Set<Long>, kept: Boolean) = Unit
        }
        val c = controller(source, history = history)
        val session = c.rebuild(1)
        assertEquals(2L, session.current)
        session.mark(2)
        session.undo()
        assertEquals(2L, session.current)
        session.mark(2)
        assertEquals(RoundAdvance.START_NEW_ROUND, c.decideNextRound(false))
        session.startNewRound()
        assertEquals(3L, session.current)
        assertEquals(1, source.selectionCalls)
        assertEquals(0, source.idsCalls)
        assertTrue(source.lastQuery!!.mergeImages)
        assertEquals(7, source.lastQuery!!.matchTrashed)
        session.updateRoundSize(300)
        val remaining = generateSequence(session.current) { session.advance(); session.current }.toList()
        assertFalse(remaining.contains(10L))
    }

    @Test fun `video smart uses existing seeded random and never reads image metadata`() = runBlocking {
        val smartSource = Source()
        val randomSource = Source()
        val smart = controller(smartSource, Board.VIDEO).rebuild(300)
        val random = controller(randomSource, Board.VIDEO).apply { applySettings(SortSpec.Random, true) }.rebuild(300)
        assertEquals(random.peekUpcoming(300), smart.peekUpcoming(300))
        assertEquals(0, smartSource.selectionCalls)
        assertEquals(SortSpec.Random, smartSource.lastQuery!!.sort)
        assertFalse(smartSource.lastQuery!!.mergeImages)
    }

    @Test fun `loading replays latest round size rather than captured twenty`() = runBlocking {
        for ((initial, latest) in listOf(20 to 1, 1 to 300)) {
            val source = Source().apply { gate = CompletableDeferred() }
            val c = controller(source)
            var size = initial
            val task = async { c.rebuild(initial) { size } }
            source.entered.await()
            size = latest
            source.gate!!.complete(Unit)
            val session = task.await()
            assertEquals(latest, session.roundSize)
            if (latest == 1) { session.advance(); assertTrue(session.roundEnded()) }
        }
    }

    @Test fun `query failure and cancellation never install an empty or stale session`() = runBlocking {
        val source = Source()
        val c = controller(source)
        val original = c.rebuild(20)
        for (failure in listOf(IOException("null cursor or provider failure"), SecurityException("permission revoked"))) {
            source.failure = failure
            try { c.rebuild(20); fail("must propagate") } catch (e: Exception) { assertSame(failure, e) }
            assertSame(original, c.session)
        }
        source.failure = null
        source.gate = CompletableDeferred()
        val cancelled = launch { c.rebuild(1) }
        yield()
        cancelled.cancelAndJoin()
        assertSame(original, c.session)
    }
}
