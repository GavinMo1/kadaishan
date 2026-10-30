package dev.sxc.kadaishan.domain.cultivation

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CombatGrowthTest {
    private fun fresh() = CultivationState(lastMillis = 0, seed = 42)
    private fun profile(attack: Long, health: Long, map: Int = 1, practice: Practice = Practice.SWORD) = fresh().copy(
        map = map, unlockedMap = map, practice = practice,
        gear = listOf(Gear(1, GearSlot.WEAPON, 1, Quality.WHITE, attack - 10), Gear(2, GearSlot.ROBE, 1, Quality.WHITE, (health - 80) / 8)),
        equipped = mapOf(GearSlot.WEAPON to 1, GearSlot.ROBE to 2))

    @Test fun exactProfilesExplainAttackAndHealthGrowth() {
        assertEquals(36_000, CultivationEngine.battle(fresh()).cycleMillis)
        val robe = CultivationEngine.equip(fresh(), -2)
        assertEquals(34_286, CultivationEngine.battle(robe).cycleMillis)
        assertEquals(27_000, CultivationEngine.battle(profile(20, 80)).cycleMillis)
        assertEquals(18_000, CultivationEngine.battle(profile(40, 80)).cycleMillis)
        assertEquals(100, CultivationEngine.battle(fresh()).winsPerHour)
        assertEquals(104, CultivationEngine.battle(robe).winsPerHour)
    }
    @Test fun traceUsesRealDamageAndNoRetaliationAfterKill() {
        val with = CultivationEngine.battle(fresh(), captureTrace = true)
        assertEquals(CultivationEngine.battle(fresh()), with.copy(trace = emptyList()))
        assertEquals(listOf(14L, 14L, 12L), with.trace.map { it.damage })
        assertEquals(listOf(8L, 8L, 0L), with.trace.map { it.retaliation })
        assertTrue(with.trace.last().burst)
        assertEquals(64, with.playerHp)
        val life = CultivationEngine.battle(fresh().copy(practice = Practice.LIFE), captureTrace = true)
        assertEquals(0, life.trace.first().healing)
        assertEquals(8, life.trace[1].healing)
    }
    @Test fun failureReasonsAndCappedRecoveryAreConsistent() {
        val dead = CultivationEngine.battle(fresh().copy(map = 6))
        assertEquals(BattleEnd.DEAD, dead.end)
        assertEquals(60_000, dead.cycleMillis)
        val timeout = CultivationEngine.battle(profile(14, 5000, 6))
        assertEquals(BattleEnd.TIMEOUT, timeout.end)
        assertEquals(5, timeout.turns)
        val capped = CultivationEngine.battle(profile(120, 504, 6))
        assertTrue(capped.won)
        assertEquals(60_000, capped.cycleMillis)
        assertEquals(capped.cycleMillis, 12_000 + capped.turns * 6_000 + capped.recoveryMillis)
    }
    private fun catchUp(start: CultivationState, now: Long, batch: () -> Long): CultivationState {
        var s = start
        do { s = CultivationEngine.catchUp(s, now, batch()) } while (CultivationEngine.pending(s, now) > 0)
        return s
    }
    @Test fun thirtyDaysHaveExactVictoryCountAndRandomChunkEquivalence() {
        val original = fresh()
        val minutes = 43_200L
        val now = minutes * 60_000 + 59_000
        val once = catchUp(original, now) { 1440 }
        val random = Random(5)
        val fragmented = catchUp(original, now) { random.nextLong(1, 1441) }
        assertEquals(once, fragmented)
        assertEquals(minutes * 60_000 / 36_000, once.report.wins)
        assertEquals(once.report.wins, once.sequence)
        assertEquals(59_000, once.carryMillis)
        assertTrue(once.gear.size <= 13)
        assertEquals(once, CultivationEngine.catchUp(once, now))
    }
    @Test fun foregroundMinuteStepsMatchOfflineForBothRemainders() {
        val original = CultivationEngine.equip(fresh(), -2)
        val once = catchUp(original, 480 * 60_000L + 17000) { 1440 }
        var foreground = original
        for (m in 1..480) foreground = CultivationEngine.catchUp(foreground, m * 60_000L)
        foreground = CultivationEngine.catchUp(foreground, 480 * 60_000L + 17000)
        assertEquals(once, foreground)
        assertTrue(once.battleCarryMillis in 1 until CultivationEngine.battle(once).cycleMillis)
    }
    @Test fun switchingNeverCreatesBattleProgressAndPausePreservesIt() {
        var s = CultivationEngine.simulate(fresh(), 1)
        val original = s.battleCarryMillis.toDouble() / CultivationEngine.battle(s).cycleMillis
        repeat(100) {
            s = CultivationEngine.transition(s, s.copy(practice = if (s.practice == Practice.SWORD) Practice.LIFE else Practice.SWORD))
            assertTrue(s.battleCarryMillis.toDouble() / CultivationEngine.battle(s).cycleMillis <= original)
        }
        assertEquals(1, s.sequence)
        val paused = CultivationEngine.catchUp(s.copy(paused = true), 43_200 * 60_000L)
        assertEquals(s.battleCarryMillis, paused.battleCarryMillis)
        assertEquals(s.sequence, paused.sequence)
    }
    @Test fun bonusesUseCyclesButNeverChangeRealClockAndKeepCooldown() {
        val a = CultivationEngine.completedRound(fresh(), 1, true, 0)
        assertEquals(30, a.report.minutes)
        assertEquals(30, a.report.bonusMinutes)
        assertEquals(50, a.report.wins)
        assertEquals(0, a.lastMillis)
        assertEquals(a, CultivationEngine.completedRound(a, 1, true, 90_000_000))
        val b = CultivationEngine.completedRound(a, 2, true, 1000)
        assertEquals(a.report, b.report)
    }
    @Test fun failureTrainsWithoutLootAndPityOnlyCountsVictories() {
        val s = fresh().copy(map = 6, dryWins = 19, dullDrops = 49)
        val after = CultivationEngine.simulate(s, 30)
        assertEquals(30, after.report.battles)
        assertEquals(0, after.report.wins)
        assertEquals(0, after.report.drops)
        assertEquals(19, after.dryWins)
        assertEquals(49, after.dullDrops)
        assertEquals(5700, after.cultivationTenths)
    }
    @Test fun sequenceLimitRejectsWholeBatchBeforeAwardingAnyResource() {
        val s = fresh().copy(sequence = Long.MAX_VALUE - 1)
        try { CultivationEngine.simulate(s, 2); fail("partially consumed time") } catch (_: IllegalArgumentException) { }
        assertEquals(300, s.cultivationTenths)
        assertEquals(0, s.stones)
    }
    @Test fun reportSaturationKeepsCategoryArithmeticValid() {
        val s = fresh().copy(report = Earnings(minutes = Long.MAX_VALUE - 2, legacyMinutes = Long.MAX_VALUE - 2))
        val a = CultivationEngine.simulate(s, 30, bonus = true)
        assertEquals(Long.MAX_VALUE, a.report.minutes)
        assertEquals(2, a.report.bonusMinutes)
        assertEquals(0, a.report.minutes - a.report.legacyMinutes - a.report.bonusMinutes)
        assertEquals(50, a.report.wins)
    }
    @Test(expected = CancellationException::class) fun longOfflineSimulationCooperatesWithCancellation() {
        var checks = 0
        CultivationEngine.simulate(fresh(), 1440, checkActive = { if (++checks == 3) throw CancellationException() })
    }
    @Test fun strongerEquipmentNeverReducesVictoryOrEfficiencyAcrossAllMapsAndPractices() {
        for (map in 1..6) for (practice in Practice.entries) for (strength in 1L..140L step 3) {
            val s = profile(10 + strength, 80 + strength * 8, map, practice)
            val before = CultivationEngine.battle(s)
            for (slot in listOf(GearSlot.WEAPON, GearSlot.ROBE)) {
                val stronger = s.copy(gear = s.gear.map { if (it.slot == slot) it.copy(strength = it.strength + 1) else it })
                val after = CultivationEngine.battle(stronger)
                if (before.won) { assertTrue(after.won); assertTrue(after.cycleMillis <= before.cycleMillis) }
            }
        }
    }
    @Test fun hostLongOfflineBenchmarkRecordsBatchLatencyWithoutCappingIncome() {
        var s = fresh()
        val times = mutableListOf<Long>()
        val runtime = Runtime.getRuntime()
        var sampledHeap = 0L
        val started = System.nanoTime()
        repeat(30) {
            val batchStart = System.nanoTime()
            s = CultivationEngine.simulate(s, 1440)
            times += (System.nanoTime() - batchStart) / 1_000_000
            sampledHeap = maxOf(sampledHeap, runtime.totalMemory() - runtime.freeMemory())
        }
        assertEquals(43_200, s.report.minutes)
        println("HOST ONLY 30d totalMs=${(System.nanoTime() - started) / 1_000_000} batchP95Ms=${times.sorted()[27]} sampledProcessHeapBytes=$sampledHeap; excludes DataStore and device I/O")
    }
}
