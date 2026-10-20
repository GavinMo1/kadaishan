package dev.sxc.kadaishan.domain.session

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DurableReviewActionTest {
    @Test fun `cross round undo waits for keep revocation and preserves state on failure`() = runBlocking {
        val session = BoardSession(listOf(1L, 2L, 3L), 2)
        session.skip(1L)
        session.skip(2L)
        session.startNewRound(preserveUndo = true)
        val revoked = session.keptIdsRevokedByUndo()
        val gate = DurableReviewAction()
        assertEquals(setOf(2L), revoked)
        assertTrue(gate.begin())
        try {
            gate.complete({ throw IOException("disk full") }) { session.undo() }
            fail("expected failure")
        } catch (_: IOException) { }
        assertEquals(3L, session.current)
        assertEquals(0, session.consumed)
        assertEquals(setOf(1L, 2L), session.keptIds)
        assertTrue(gate.begin())
        gate.complete({}) { session.undo() }
        assertEquals(2L, session.current)
        assertEquals(1, session.consumed)
        assertEquals(setOf(1L), session.keptIds)
    }

    @Test fun `slow commit holds current card and rejects a second gesture`() = runBlocking {
        val session = BoardSession(listOf(1L, 2L), 2)
        val gate = DurableReviewAction()
        val disk = CompletableDeferred<Unit>()
        assertTrue(gate.begin())
        val action = async(start = CoroutineStart.UNDISPATCHED) {
            gate.complete({ disk.await() }) { session.skip(1L) }
        }
        assertEquals(1L, session.current)
        assertFalse(gate.begin())
        disk.complete(Unit)
        action.await()
        assertEquals(2L, session.current)
        assertFalse(gate.busy)
    }

    @Test fun `failed keep keeps current card and permits retry`() = runBlocking {
        val session = BoardSession(listOf(1L, 2L), 2)
        val gate = DurableReviewAction()
        assertTrue(gate.begin())
        try {
            gate.complete({ throw IOException("disk full") }) { session.skip(1L) }
            fail("expected write failure")
        } catch (_: IOException) { }
        assertEquals(1L, session.current)
        assertTrue(session.keptIds.isEmpty())
        assertTrue(gate.begin())
        gate.complete({}) { session.skip(1L) }
        assertEquals(2L, session.current)
    }

    @Test fun `failed undo retains pointer and decisions until retry commits`() = runBlocking {
        val session = BoardSession(listOf(1L, 2L, 3L), 3)
        session.skip(1L)
        session.skip(2L)
        val revoked = session.keptIdsRevokedByUndo()
        assertEquals(setOf(2L), revoked)
        val gate = DurableReviewAction()
        assertTrue(gate.begin())
        try {
            gate.complete({ throw IOException("disk full") }) { session.undo() }
            fail("expected write failure")
        } catch (_: IOException) { }
        assertEquals(3L, session.current)
        assertEquals(setOf(1L, 2L), session.keptIds)
        assertEquals(revoked, session.keptIdsRevokedByUndo())
        assertTrue(gate.begin())
        gate.complete({}) { session.undo() }
        assertEquals(2L, session.current)
        assertEquals(setOf(1L), session.keptIds)
    }
}
