package dev.sxc.kadaishan.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dev.sxc.kadaishan.domain.cultivation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

internal class GameTestStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val writes = kotlinx.coroutines.sync.Mutex()
    override val data = MutableStateFlow(initial)
    var before: ((Preferences) -> Unit)? = null
    var after: ((Preferences) -> Unit)? = null
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = writes.withLock {
        val next = transform(data.value)
        before?.invoke(next)
        data.value = next
        after?.invoke(next)
        next
    }
}

class CultivationMigrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = stringPreferencesKey("state_v1")
    private fun gameStore(s: CultivationState) = GameTestStore(mutablePreferencesOf(key to CultivationCodec.encode(s)))
    private fun persisted(store: GameTestStore) = CultivationCodec.decode(store.data.value[key]!!)
    private fun repo(store: GameTestStore, outbox: CultivationRewardOutbox = CultivationRewardOutbox(GameTestStore()),
                     now: Long = 0, backup: suspend (String) -> Unit = {}) = CultivationRepository(store, outbox, backup, { now })

    @Test fun oldThirtyDayOfflineSettlesUnderOldRulesThenMigratesWithoutLosingAssetsOrRemainder() = runBlocking {
        val old = CultivationState(version = 1, lastMillis = 0, seed = 42, carryMillis = 59000, giftMillis = 123,
            consumedRound = 0, reincarnations = 2, world = 3)
        val store = gameStore(old)
        var backed: String? = null
        val now = 43_200 * 60_000L
        val game = repo(store, now = now, backup = { if (backed == null) backed = it })
        game.refresh()
        var expected = old
        do { expected = CultivationEngine.catchUp(expected, now) } while (CultivationEngine.pending(expected, now) > 0)
        expected = expected.copy(version = 2, report = expected.report.copy(battles = 43200, legacyMinutes = 43200))
        assertEquals(expected, game.state.value)
        assertEquals(old, CultivationCodec.decode(backed!!))
        game.refresh()
        assertEquals(expected, game.state.value)
        assertEquals(59000, persisted(store).carryMillis)
    }
    @Test fun backupFailurePreservesRawSaveAndRetryMigratesExactlyOnce() = runBlocking {
        val old = CultivationState(version = 1, lastMillis = 0)
        val store = gameStore(old)
        val raw = store.data.value[key]
        var fail = true
        val game = repo(store, now = 60_000, backup = { if (fail) throw IOException("backup failed") })
        game.refresh()
        assertNotNull(game.error.value)
        assertEquals(raw, store.data.value[key])
        fail = false; game.refresh()
        assertEquals(1, game.state.value!!.report.wins)
        assertEquals(2, game.state.value!!.version)
    }
    @Test fun partiallyCompletedLegacyMigrationResumesFromDurableChunk() = runBlocking {
        val store = gameStore(CultivationState(version = 1, lastMillis = 0))
        store.before = { p ->
            val value = CultivationCodec.decode(p[key]!!)
            if (value.version == 1 && value.report.minutes > 1440) throw IOException("disk interrupted")
        }
        val first = repo(store, now = 3000 * 60_000L)
        first.refresh()
        assertEquals(1440, persisted(store).report.minutes)
        assertEquals(1, persisted(store).version)
        store.before = null
        val resumed = repo(store, now = 3000 * 60_000L)
        resumed.refresh()
        assertEquals(3000, persisted(store).report.wins)
        assertEquals(2, persisted(store).version)
    }
    @Test fun committedMigrationWithLostAcknowledgementInvalidatesCacheAndNeverRepeatsIncome() = runBlocking {
        val store = gameStore(CultivationState(version = 1, lastMillis = 0))
        var injected = false
        store.after = { p ->
            if (!injected && CultivationCodec.decode(p[key]!!).version == 2) { injected = true; throw IOException("ACK lost") }
        }
        val game = repo(store, now = 60_000)
        game.refresh()
        assertNotNull(game.error.value)
        val committed = persisted(store)
        store.after = null
        game.refresh()
        assertEquals(committed, game.state.value)
        assertEquals(1, game.state.value!!.report.wins)
    }
    @Test fun committedThenCancelledUpdateIsRereadRatherThanOverwritten() = runBlocking {
        val store = gameStore(CultivationState(lastMillis = 0))
        val game = repo(store)
        game.refresh()
        var cancel = true
        store.after = { if (cancel) { cancel = false; throw CancellationException("committed before cancel") } }
        try { game.update { it.copy(stones = it.stones + 10) }; fail() } catch (_: CancellationException) { }
        store.after = null
        game.refresh()
        assertEquals(10, game.state.value!!.stones)
        game.update { it.copy(stones = it.stones + 1) }
        assertEquals(11, game.state.value!!.stones)
    }
    @Test fun updateMapsBattleFractionAndClearingReportDoesNotClearProgress() = runBlocking {
        val initial = CultivationEngine.simulate(CultivationState(lastMillis = 0), 1)
        val game = repo(gameStore(initial))
        game.update { CultivationEngine.equip(it, -2) }
        val changed = game.state.value!!
        assertEquals(initial.battleCarryMillis * 34286 / 36000, changed.battleCarryMillis)
        game.clearReport()
        assertEquals(changed.battleCarryMillis, game.state.value!!.battleCarryMillis)
        assertEquals(Earnings(), game.state.value!!.report)
    }
    @Test fun reportDistinguishesLegacyRealAndBonusMinutes() = runBlocking {
        val old = CultivationEngine.simulate(CultivationState(version = 1, lastMillis = 0), 100)
        val queue = CultivationRewardOutbox(GameTestStore())
        val reservation = queue.reserve()
        queue.append(reservation.first, reservation.second, 0, true)
        var now = 0L
        val game = CultivationRepository(gameStore(old), queue, {}, { now })
        game.refresh()
        now = 10 * 60_000L
        assertTrue(game.deliver(queue.head()!!))
        val r = game.state.value!!.report
        assertEquals(140, r.minutes)
        assertEquals(100, r.legacyMinutes)
        assertEquals(30, r.bonusMinutes)
        assertEquals(10, r.minutes - r.legacyMinutes - r.bonusMinutes)
    }
    @Test fun outboxRetainsFailedDeliveryAndReplayAfterAckLossIsExactlyOnce() = runBlocking {
        val queue = CultivationRewardOutbox(GameTestStore())
        val reserved = queue.reserve()
        queue.append(reserved.first, reserved.second, 0, true)
        val event = queue.head()!!
        val store = gameStore(CultivationState(lastMillis = 0))
        val game = repo(store, queue)
        store.before = { throw IOException("write failed") }
        assertFalse(game.deliver(event))
        assertEquals(event, queue.head())
        store.before = null
        assertTrue(game.deliver(event))
        val committed = game.state.value!!
        assertTrue(game.deliver(event))
        assertEquals(committed, game.state.value)
        queue.acknowledge(event.id)
        assertNull(queue.head())
    }
    @Test fun outOfOrderRoundCompletionGetsOrderedDeliveryIdsAndDurableAppendReceipts() = runBlocking {
        val storage = GameTestStore()
        val queue = CultivationRewardOutbox(storage)
        val earlier = queue.reserve(); val later = queue.reserve()
        queue.append(later.first, later.second, 0, true)
        val first = queue.head()!!
        queue.acknowledge(first.id) // A drainer can consume before append's caller recovers its ACK.
        queue.append(later.first, later.second, 0, true)
        assertNull(queue.head())
        queue.append(earlier.first, earlier.second, 86_400_000, true)
        assertTrue(queue.head()!!.id > first.id)
    }
    @Test fun resetRejectsLateOldHandlesAndAlreadyReadOldHeadButAcceptsNewEvents() = runBlocking {
        val queue = CultivationRewardOutbox(GameTestStore())
        val oldHandle = queue.reserve()
        queue.append(oldHandle.first, oldHandle.second, 0, true)
        val oldHead = queue.head()!!
        val game = repo(gameStore(CultivationState(lastMillis = 0)), queue)
        game.reset()
        assertEquals(1, game.state.value!!.rewardGeneration)
        queue.append(oldHandle.first, oldHandle.second, 0, true)
        assertNull(queue.head())
        assertTrue(game.deliver(oldHead))
        assertEquals(0, game.state.value!!.report.bonusMinutes)
        val fresh = queue.reserve(); queue.append(fresh.first, fresh.second, 0, true)
        assertTrue(game.deliver(queue.head()!!))
        assertEquals(30, game.state.value!!.report.bonusMinutes)
    }
    @Test fun resetBarrierRecoversEachCrossFileWindowWithoutResettingAlreadyInstalledProgress() = runBlocking {
        val qs = GameTestStore()
        val queue = CultivationRewardOutbox(qs)
        queue.coordination.withLock { queue.prepareReset() }
        val store = gameStore(CultivationState(lastMillis = 0))
        store.before = { throw IOException("install failed") }
        repo(store, queue).refresh()
        assertTrue(queue.control().resetPending)
        store.before = null
        qs.before = { p -> if (p[booleanPreferencesKey("reset_pending")] == false) throw IOException("finish failed") }
        repo(store, queue, now = 1000).refresh()
        assertEquals(1, persisted(store).rewardGeneration)
        val installed = persisted(store)
        qs.before = null
        val recovered = repo(store, queue, now = 2000)
        recovered.refresh()
        assertEquals(installed.seed, recovered.state.value!!.seed)
        assertEquals(1000, recovered.state.value!!.carryMillis)
        assertFalse(queue.control().resetPending)
    }
    @Test fun missingV2FieldsFutureSchemasAndCorruptBackupFailWithoutReplacingProgress() = runBlocking {
        val valid = CultivationCodec.encode(CultivationState(lastMillis = 0))
        for (field in listOf("battleCarry", "rewardGeneration")) {
            try { CultivationCodec.decode(JSONObject(valid).apply { remove(field) }.toString()); fail(field) } catch (_: Exception) { }
        }
        val file = temp.newFile("backup.json").apply { delete() }
        val original = CultivationCodec.encode(CultivationState(version = 1, lastMillis = 0))
        CultivationBackup.preserve(file, original)
        val bytes = file.readBytes()
        CultivationBackup.preserve(file, original.replace("\"stones\":0", "\"stones\":1"))
        assertArrayEquals(bytes, file.readBytes())
        file.writeText("broken")
        try { CultivationBackup.preserve(file, original); fail() } catch (_: Exception) { }
        assertEquals("broken", file.readText())
    }

    @Test fun appendCommittedButAckLostCanBeRetriedEvenAfterDraining() = runBlocking {
        val storage = GameTestStore()
        val queue = CultivationRewardOutbox(storage)
        val handle = queue.reserve()
        var fail = true
        storage.after = { p -> if (fail && p[stringPreferencesKey("pending_rewards")] != null) { fail = false; throw IOException("commit ACK lost") } }
        queue.append(handle.first, handle.second, 0, true)
        val event = queue.head()!!
        queue.acknowledge(event.id)
        queue.append(handle.first, handle.second, 0, true)
        assertNull(queue.head())
    }

    @Test fun bridgePersistsOnceAndRetriesBeforePersistFailureWithoutBlockingToolCaller() = runBlocking {
        val storage = GameTestStore()
        val queue = CultivationRewardOutbox(storage)
        val game = repo(gameStore(CultivationState(lastMillis = 0)), queue)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bridge = CultivationRoundBridge(game, scope, { 0 })
            val handle = bridge.begin()
            handle.identity.await()
            var fail = true
            storage.before = { p -> if (fail && !p[stringPreferencesKey("pending_rewards")].isNullOrEmpty()) throw IOException("outbox full") }
            bridge.complete(handle, true)
            withTimeout(3000) { while (game.error.value == null) delay(10) }
            fail = false
            game.refresh()
            withTimeout(3000) { while (game.state.value?.report?.bonusMinutes != 30L) delay(10) }
            val consumed = game.state.value!!.consumedRound
            bridge.complete(handle, true)
            game.refresh()
            assertEquals(consumed, game.state.value!!.consumedRound)
            assertEquals(30, game.state.value!!.report.bonusMinutes)
        } finally { scope.cancel() }
    }
}
