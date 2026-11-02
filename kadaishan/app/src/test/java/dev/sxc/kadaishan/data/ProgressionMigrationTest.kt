package dev.sxc.kadaishan.data

import androidx.datastore.preferences.core.*
import dev.sxc.kadaishan.domain.cultivation.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ProgressionMigrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = stringPreferencesKey("state_v1")
    private fun fixture() = javaClass.getResource("/cultivation-schema2.json")!!.readText()
    private fun store(raw: String = fixture()) = GameTestStore(mutablePreferencesOf(key to raw))
    private fun state(store: GameTestStore) = CultivationCodec.decode(store.data.value[key]!!)
    private fun game(store: GameTestStore, now: Long, backup: suspend (String) -> Unit = {}) = CultivationRepository(
        store, CultivationRewardOutbox(GameTestStore()), backup, { now })

    @Test fun schemaTwoFixtureSettlesThirtyDaysExactlyUnderOldRulesThenPreservesAssetsAndFractions() = runBlocking {
        val old = CultivationCodec.decode(fixture())
        val now = old.lastMillis + 43_200 * 60_000L
        var expected = old
        do { expected = CultivationEngine.catchUp(expected, now) } while (CultivationEngine.pending(expected, now) > 0)
        val storage = store(); val backups = mutableListOf<String>()
        val repo = game(storage, now) { backups += it }
        repo.refresh()
        val actual = state(storage)
        assertEquals(expected.copy(version = 3, report = expected.report.copy(legacyCultivation = expected.report.cultivationTenths)), actual)
        assertEquals(160000, old.stones)
        assertEquals(old.enhancements, actual.enhancements)
        assertTrue(backups.all { CultivationCodec.decode(it).version == 2 })
        assertEquals(old, CultivationCodec.decode(backups.first()))
        assertEquals(0, actual.report.manualDrops)
        assertEquals(2, actual.manualLevels.size)
        assertEquals(59000, actual.carryMillis)
        val before = actual
        repo.refresh(); assertEquals(before, state(storage))
    }

    @Test fun zeroOfflineMigrationPreservesSixteenWanMaxedSlotsAndSelectedPractice() = runBlocking {
        val storage = store(); val old = state(storage)
        val repo = game(storage, old.lastMillis)
        repo.refresh()
        val new = state(storage)
        assertEquals(160000, new.stones); assertEquals(old.enhancements, new.enhancements)
        assertEquals(Practice.LIFE, new.practice); assertEquals(12345, new.battleCarryMillis)
        assertEquals(old.cultivationTenths, new.cultivationTenths)
        assertEquals(18000, new.report.legacyCultivation)
        assertEquals(0, new.baseRemainder); assertEquals(0, new.killRemainder)
    }

    @Test fun failedBackupNeverChangesOldSaveAndEachSchemaHasAnIndependentVerifiedBackup() = runBlocking {
        val storage = store(); val raw = storage.data.value[key]
        val repo = game(storage, 9_000_000) { throw IOException("full") }
        repo.refresh(); assertEquals(raw, storage.data.value[key]); assertNotNull(repo.error.value)
        val one = temp.newFile("v1").apply { delete() }; val two = temp.newFile("v2").apply { delete() }
        val legacy = CultivationCodec.encode(CultivationState(version = 1, lastMillis = 0))
        CultivationBackup.preserve(one, legacy); CultivationBackup.preserve(two, fixture())
        assertEquals(1, JSONObject(one.readText()).getInt("schema"))
        assertEquals(2, JSONObject(two.readText()).getInt("schema"))
        try { CultivationBackup.preserve(one, fixture()); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun durableOldChunkResumesAfterInterruptionAndCommittedV3AckLossDoesNotRepeatIncome() = runBlocking {
        val storage = store(); val old = state(storage); val now = old.lastMillis + 3000 * 60_000L
        storage.before = { p -> if (CultivationCodec.decode(p[key]!!).lastMillis > old.lastMillis + 1440 * 60_000L) throw IOException("cut") }
        game(storage, now).refresh()
        val partial = state(storage)
        assertEquals(2, partial.version); assertTrue(partial.lastMillis > old.lastMillis)
        storage.before = null
        var lost = false
        storage.after = { p -> if (!lost && CultivationCodec.decode(p[key]!!).version == 3) { lost = true; throw IOException("ack lost") } }
        val repo = game(storage, now); repo.refresh()
        assertNotNull(repo.error.value)
        val committed = state(storage)
        storage.after = null; repo.refresh()
        assertEquals(committed, state(storage)); assertEquals(committed, repo.state.value)
        var full = old
        do { full = CultivationEngine.catchUp(full, now) } while (CultivationEngine.pending(full, now) > 0)
        assertEquals(full.cultivationTenths, committed.cultivationTenths)
    }

    @Test fun pauseAndClockRollbackCanMigrateWithoutInventingTime() = runBlocking {
        for (paused in listOf(false, true)) {
            val old = CultivationCodec.decode(fixture()).copy(paused = paused)
            val storage = store(CultivationCodec.encode(old))
            game(storage, 0).refresh()
            val new = state(storage)
            assertEquals(3, new.version); assertEquals(old.stones, new.stones)
            assertEquals(old.lastMillis, new.lastMillis); assertEquals(old.carryMillis, new.carryMillis)
        }
    }

    @Test fun allNewFieldsRoundTripAndMissingInvalidOrFutureValuesFailClosed() {
        val good = CultivationState(lastMillis = 0, practice = Practice.ORIGIN,
            manualLevels = Practice.entries.associateWith { 20 }, insight = 190, manualDryWins = 199,
            baseRemainder = 499, killRemainder = 499, stoneRemainder = 99,
            report = Earnings(cultivationTenths = 31, baseCultivation = 10, killCultivation = 20, legacyCultivation = 1,
                manualDrops = 5, insightDrops = 1, newManuals = setOf(Practice.ORIGIN)))
        val raw = CultivationCodec.encode(good)
        assertEquals(good, CultivationCodec.decode(raw))
        val cases = mutableListOf<String>()
        for (field in listOf("manualLevels", "insight", "manualDryWins", "baseRemainder", "killRemainder", "stoneRemainder"))
            cases += JSONObject(raw).apply { remove(field) }.toString()
        cases += JSONObject(raw).put("baseRemainder", 500).toString()
        cases += JSONObject(raw).put("manualDryWins", 200).toString()
        cases += JSONObject(raw).put("insight", -1).toString()
        cases += JSONObject(raw).put("manualLevels", JSONObject().put("SWORD", 1).put("LIFE", 1)).toString()
        cases += JSONObject(raw).put("version", 4).toString()
        cases += JSONObject(raw).apply { getJSONObject("report").put("xp", 32) }.toString()
        for (bad in cases) { try { CultivationCodec.decode(bad); fail("corrupt v3 accepted") } catch (_: Exception) { } }
    }

    @Test fun spendingIsSavedAtomicallyStaleAfterIncomeAndUnknownCommitIsNotChargedAgain() = runBlocking {
        val initial = CultivationState(lastMillis = 0, stones = 160000, cultivationTenths = 0, insight = 190)
        val storage = store(CultivationCodec.encode(initial)); var now = 0L
        val repo = CultivationRepository(storage, CultivationRewardOutbox(GameTestStore()), {}, { now })
        repo.refresh()
        val quote = CultivationProgression.refinement(repo.state.value!!, 1000)
        now = 60_000
        assertNull(repo.update { CultivationProgression.refine(it, quote) })
        val settled = repo.state.value!!
        assertTrue(settled.stones >= initial.stones)
        val training = CultivationProgression.training(settled, Practice.SWORD, true)
        storage.after = { throw IOException("committed but no ACK") }
        assertNull(repo.update { CultivationProgression.train(it, training) })
        val committed = state(storage)
        storage.after = null; repo.refresh()
        assertEquals(committed, repo.state.value)
        assertNull(repo.update { CultivationProgression.train(it, training) })
        assertEquals(committed, state(storage))
    }
}
