package dev.sxc.kadaishan.domain.cultivation

import org.junit.Assert.*
import org.junit.Test

/** Uses the shipped engine, never an expected-value approximation of random loot. */
class CultivationPacingTest {
    private data class Run(val minutes: Int, val checkpoints: List<Int>, val orangeDrops: Int, val fullUpgradeAt: Int?, val state: CultivationState)
    private fun play(seed: Long, cadence: Int, reincarnations: Long = 0, inherited: CultivationState? = null): Run {
        var s = inherited ?: CultivationState(lastMillis = 0, seed = seed, reincarnations = reincarnations)
        val checkpoint = mutableListOf<Int>()
        var minute = 0
        var orange = 0
        var maxUpgrades: Int? = null
        while (6 !in s.bosses && minute < 43_200) {
            s = CultivationEngine.simulate(s, cadence.toLong(), onDrop = { if (it.quality == Quality.ORANGE) orange++ })
            minute += cadence
            repeat(6) {
                GearSlot.entries.forEach { slot ->
                    val best = s.gear.asSequence().filter { it.slot == slot && it.quality != Quality.ORANGE }.maxByOrNull { it.strength }
                    if (best != null && s.equipped[slot] != best.id) s = CultivationEngine.transition(s, CultivationEngine.equip(s, best.id))
                    while ((s.enhancements[slot] ?: 0) < 10) {
                        val n = (s.enhancements[slot] ?: 0) + 1
                        if (s.stones < 20L * n * n) break
                        s = CultivationEngine.transition(s, CultivationEngine.upgrade(s, slot))
                    }
                }
                if (s.level < 18 && s.cultivationTenths / 10 >= CultivationEngine.nextCost(s.level))
                    s = CultivationEngine.transition(s, CultivationEngine.breakthrough(s, true))
                val bestMap = (1..s.unlockedMap).filter { s.level >= 1 + (it - 1) * 3 }
                    .lastOrNull { CultivationEngine.battle(s.copy(map = it)).won } ?: 1
                if (s.map != bestMap) s = CultivationEngine.transition(s, CultivationEngine.selectMap(s, bestMap))
                if (s.level >= s.map * 3 && s.map !in s.bosses) {
                    val (next, battle) = CultivationEngine.challenge(s)
                    s = next
                    if (battle.won) checkpoint.add(minute)
                }
            }
            if (maxUpgrades == null && GearSlot.entries.all { (s.enhancements[it] ?: 0) == 10 }) maxUpgrades = minute
        }
        return Run(minute, checkpoint, orange, maxUpgrades, s)
    }
    private fun percentile(values: List<Int>, fraction: Double) = values.sorted()[((values.size - 1) * fraction).toInt()]
    @Test fun hundredSeedsClearWithoutOrangeAtBothCadencesAndReincarnationsRemainPlayable() {
        for (cadence in listOf(720, 1)) {
            val runs = (1L..100L).map { play(it, cadence) }
            assertTrue("cadence=$cadence must clear all seeds within 30 days", runs.all { 6 in it.state.bosses })
            assertTrue(runs.all { run -> run.state.gear.none { run.state.equipped[it.slot] == it.id && it.quality == Quality.ORANGE } })
            val minutes = runs.map { it.minutes }
            val median = percentile(minutes, .5)
            if (cadence == 720) assertTrue("low frequency median must remain within 14 days", median <= 20_160)
            else assertTrue("fast median must not collapse below two days", median >= 2880)
            println("pacing cadence=$cadence seeds=100 p10/median/p90 minutes=${percentile(minutes,.1)}/$median/${percentile(minutes,.9)}")
            println("map-clear medians=" + (0..5).map { i -> percentile(runs.map { it.checkpoints[i] }, .5) })
            println("actual orange drops median=" + percentile(runs.map { it.orangeDrops }, .5) +
                "; fully upgraded by clear=${runs.count { it.fullUpgradeAt != null }}; median upgrade minute=" +
                runs.mapNotNull { it.fullUpgradeAt }.let { if (it.isEmpty()) "none" else percentile(it, .5) })
        }
        val finishedLife = play(42, 720).state
        for (r in listOf(1L, 5L, 10L)) for (cadence in listOf(720, 1)) {
            val previous = finishedLife.copy(reincarnations = r - 1)
            val heirloom = previous.gear.filter { it.quality != Quality.ORANGE }.maxBy { it.strength }
            val reborn = CultivationEngine.transition(previous, CultivationEngine.reincarnate(previous, heirloom.id, previous.world))
            val result = play(42, cadence, r, reborn)
            assertTrue(6 in result.state.bosses)
            println("pacing R=$r cadence=$cadence clearMinutes=${result.minutes}")
        }
    }
    @Test fun ordinaryCycleIsBoundedAndHigherMapsRetainMeaningfulIncome() {
        val maxed = CultivationState(lastMillis = 0, level = 18, gear = emptyList())
        val first = CultivationEngine.hourlyStones(maxed)
        val last = CultivationEngine.hourlyStones(maxed.copy(map = 6, gear = listOf(
            Gear(1, GearSlot.WEAPON, 6, Quality.PURPLE, 200), Gear(2, GearSlot.ROBE, 6, Quality.PURPLE, 200)),
            equipped = mapOf(GearSlot.WEAPON to 1, GearSlot.ROBE to 2)))
        assertTrue(last > first)
        assertTrue(CultivationEngine.hourlyCultivationTenths(maxed.copy(map = 6)) > CultivationEngine.hourlyCultivationTenths(maxed))
        assertTrue(CultivationEngine.battle(maxed).cycleMillis in 18_000..60_000)
    }
}
