package dev.sxc.kadaishan.domain.session

import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.model.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaKindResetTest {
    private class History : ReviewHistory {
        val kept = mutableSetOf(1L, 2L, 3L, 10L, 11L, 99L)
        var fail = false
        override suspend fun keptIds() = kept.toSet()
        override suspend fun setKept(ids: Set<MediaKey>, kept: Boolean) {
            if (fail) throw IOException("disk full")
            if (kept) this.kept.addAll(ids) else this.kept.removeAll(ids)
        }
    }

    private class Source : MediaSource {
        override suspend fun selectionRows(query: MediaQuery) = emptyList<dev.sxc.kadaishan.domain.selection.SelectionRow>()
        var last: MediaQuery? = null
        var fail = false
        override suspend fun ids(query: MediaQuery): List<MediaKey> {
            if (fail) throw IOException("query unavailable")
            last = query
            return when {
                query.board == Board.VIDEO -> listOf(10L, 11L)
                query.mergeImages -> listOf(1L, 2L, 3L)
                query.board == Board.SCREENSHOT -> listOf(2L)
                else -> listOf(1L)
            }
        }
        override suspend fun page(query: MediaQuery, after: MediaKey?, limit: Int) = Page<MediaItem>(emptyList(), null)
        override suspend fun queryByIds(ids: List<MediaKey>, matchTrashed: Int) = emptyList<MediaItem>()
    }

    @Test fun `image reset includes all image categories and preserves videos and inaccessible IDs`() = runBlocking {
        for (board in listOf(Board.PHOTO, Board.SCREENSHOT, Board.SAVED)) {
            val source = Source()
            val history = History()
            val controller = SessionController(source, board, 7, history = history)
            controller.applySettings(SortSpec.Random, false)
            controller.resetKeptForMediaKind()
            assertEquals(Board.PHOTO, source.last!!.board)
            assertTrue(source.last!!.mergeImages)
            assertEquals(7, source.last!!.matchTrashed)
            assertEquals(setOf(10L, 11L, 99L), history.keptIds())
            val videos = SessionController(source, Board.VIDEO, 7, history = history)
            assertNull(videos.rebuild(10).current)
            assertNotNull(controller.rebuild(20).current)
        }
    }

    @Test fun `video reset preserves all image decisions across rebuild`() = runBlocking {
        val source = Source()
        val history = History()
        val videos = SessionController(source, Board.VIDEO, 7, history = history)
        videos.resetKeptForMediaKind()
        assertEquals(Board.VIDEO, source.last!!.board)
        assertFalse(source.last!!.mergeImages)
        assertEquals(setOf(1L, 2L, 3L, 99L), history.keptIds())
        val images = SessionController(source, Board.PHOTO, 7, history = history)
        assertNull(images.rebuild(20).current)
        assertNotNull(videos.rebuild(10).current)
    }

    @Test fun `query failure does not change any saved decisions`() = runBlocking {
        val source = Source().apply { fail = true }
        val history = History()
        val before = history.keptIds()
        try {
            SessionController(source, Board.PHOTO, 7, history = history).resetKeptForMediaKind()
            fail("expected query failure")
        } catch (_: IOException) { }
        assertEquals(before, history.keptIds())
    }

    @Test fun `write failure retains both media kinds and previous session`() = runBlocking {
        val source = Source()
        val history = History()
        val controller = SessionController(source, Board.VIDEO, 7, history = history)
        val session = controller.rebuild(10)
        val before = history.keptIds()
        history.fail = true
        try {
            controller.resetKeptForMediaKind()
            fail("expected write failure")
        } catch (_: IOException) { }
        assertEquals(before, history.keptIds())
        assertSame(session, controller.session)
    }
}
