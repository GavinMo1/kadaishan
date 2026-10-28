package dev.sxc.kadaishan.ui.boards

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import dev.sxc.kadaishan.data.SettingsRepository
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.domain.selection.SelectionRow
import dev.sxc.kadaishan.domain.session.ReviewHistory
import dev.sxc.kadaishan.model.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoLoadingTest {
    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private class Source : MediaSource {
        var calls = 0
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun ids(query: MediaQuery): List<Long> {
            calls++; gate?.await(); failure?.let { throw it }
            return (1L..300L).toList()
        }
        override suspend fun selectionRows(query: MediaQuery): List<SelectionRow> { error("not needed for VM state tests") }
        // Keep the detail loader suspended so tests exercise VM actions rather than Android URI stubs.
        override suspend fun queryByIds(ids: List<Long>, matchTrashed: Int): List<MediaItem> = awaitCancellation()
        override suspend fun page(query: MediaQuery, after: Long?, limit: Int) = Page<MediaItem>(emptyList(), null)
    }
    private class History : ReviewHistory {
        val kept = mutableSetOf<Long>()
        override suspend fun keptIds() = kept.toSet()
        override suspend fun setKept(ids: Set<Long>, kept: Boolean) {
            if (kept) this.kept.addAll(ids) else this.kept.removeAll(ids)
        }
    }

    @Test fun `first load and retry replay latest live round count without permitting premature actions`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for ((initial, latest) in listOf(20 to 1, 1 to 300)) {
                val repo = SettingsRepository(Store())
                repo.setRoundSize(Board.PHOTO, initial)
                val source = Source().apply { gate = CompletableDeferred() }
                val vm = PhotoCardViewModel(Board.PHOTO, source, repo, History())
                try {
                    runCurrent()
                    vm.onAction(BoardAction.MARK_DELETE)
                    assertEquals(0, vm.markedCount)
                    repo.setRoundSize(Board.PHOTO, latest)
                    runCurrent()
                    source.gate!!.complete(Unit)
                    runCurrent()
                    assertTrue(vm.ready)
                    assertEquals(latest, vm.currentRoundSize)
                    repeat(latest) { vm.onAction(BoardAction.MARK_DELETE) }
                    assertTrue(vm.roundEnded)
                    assertEquals(latest, vm.seenCount)
                } finally { vm.viewModelScope.cancel() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `read and permission failures stay unready and repeated retry stays single flight`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (failure in listOf(IOException("provider unavailable"), SecurityException("permission revoked"))) {
                val source = Source().apply { this.failure = failure }
                val repo = SettingsRepository(Store())
                val history = History().apply { kept.add(42) }
                val vm = PhotoCardViewModel(Board.PHOTO, source, repo, history)
                try {
                    runCurrent()
                    assertFalse(vm.ready)
                    assertEquals(if (failure is SecurityException) PhotoLoadFailure.PERMISSION else PhotoLoadFailure.READ, vm.loadFailure)
                    assertEquals(setOf(42L), history.kept)
                    source.failure = null
                    source.gate = CompletableDeferred()
                    vm.retryLoading(); vm.retryLoading(); runCurrent()
                    assertEquals(2, source.calls)
                    repo.setRoundSize(Board.PHOTO, 1); runCurrent()
                    source.gate!!.complete(Unit); runCurrent()
                    assertTrue(vm.ready)
                    assertNull(vm.loadFailure)
                    vm.onAction(BoardAction.MARK_DELETE)
                    assertTrue(vm.roundEnded)
                } finally { vm.viewModelScope.cancel() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `durable keep remains ready and undo revokes the same decision`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val history = History()
            val vm = PhotoCardViewModel(Board.PHOTO, Source(), SettingsRepository(Store()), history)
            try {
                runCurrent()
                val id = vm.currentId!!
                vm.onAction(BoardAction.SKIP); runCurrent()
                assertTrue(vm.ready)
                assertTrue(id in history.kept)
                vm.undo(); runCurrent()
                assertTrue(vm.ready)
                assertEquals(id, vm.currentId)
                assertFalse(id in history.kept)
            } finally { vm.viewModelScope.cancel() }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `cancelled screen cannot publish a late ready session or a misleading error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source().apply { gate = CompletableDeferred() }
            val vm = PhotoCardViewModel(Board.PHOTO, source, SettingsRepository(Store()), History())
            runCurrent()
            vm.viewModelScope.cancel(); runCurrent()
            source.gate!!.complete(Unit); runCurrent()
            assertFalse(vm.ready)
            assertNull(vm.currentId)
            assertNull(vm.loadFailure)
        } finally { Dispatchers.resetMain() }
    }
}
