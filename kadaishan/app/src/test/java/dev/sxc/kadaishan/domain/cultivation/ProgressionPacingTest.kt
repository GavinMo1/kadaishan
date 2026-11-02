package dev.sxc.kadaishan.domain.cultivation

import org.junit.Assert.*
import org.junit.Test

/** Actual deterministic loot, not probability averages; two visits/day, never equips orange. */
class ProgressionPacingTest {
    private data class Run(val minutes: Int, val collectedAt: Int, val upgradesAt: Int, val spentRefining: Long,
        val spentTraining: Long, val state: CultivationState)
    private fun run(seed: Long, enhanced: Boolean, inherited: CultivationState? = null): Run {
        var s = inherited ?: CultivationState(seed = seed, lastMillis = 0)
        var minute = 0; var collected = 0; var upgrades = 0
        var refining = 0L; var training = 0L
        fun change(next: CultivationState) { s = CultivationEngine.transition(s, next) }
        while (6 !in s.bosses && minute < 43_200) {
            s = CultivationEngine.simulate(s, 720); minute += 720
            if (collected == 0 && s.manualLevels.size == 6) collected = minute
            repeat(6) {
                GearSlot.entries.forEach { slot ->
                    val best = s.gear.filter { it.slot == slot && it.quality != Quality.ORANGE }.maxByOrNull { it.strength }
                    if (best != null && s.equipped[slot] != best.id) change(CultivationEngine.equip(s, best.id))
                    while ((s.enhancements[slot] ?: 0) < 10) {
                        val n = (s.enhancements[slot] ?: 0) + 1
                        if (s.stones < 20L * n * n) break
                        change(CultivationEngine.upgrade(s, slot))
                    }
                }
                if (enhanced) {
                    val best = s.manualLevels.keys.filter { CultivationEngine.battle(s.copy(practice = it)).won }
                        .maxByOrNull { CultivationEngine.hourlyCultivationTenths(s.copy(practice = it)) }
                    if (best != null) change(CultivationProgression.select(s, best))
                    val q = CultivationProgression.training(s, s.practice, true)
                    if (q.cost > 0) { training += q.cost; change(CultivationProgression.train(s, q)) }
                    val refine = CultivationProgression.refinement(s, 10000)
                    if (refine.cost > 0) { refining += refine.cost; change(CultivationProgression.refine(s, refine)) }
                }
                change(CultivationEngine.breakthrough(s, true))
                val bestMap = (1..s.unlockedMap).filter { s.level >= 1 + (it - 1) * 3 }
                    .lastOrNull { CultivationEngine.battle(s.copy(map = it)).won } ?: 1
                if (s.map != bestMap) change(CultivationEngine.selectMap(s, bestMap))
                if (s.level >= s.map * 3 && s.map !in s.bosses) {
                    if (enhanced) {
                        val bossManual = s.manualLevels.keys.firstOrNull { CultivationEngine.battle(s.copy(practice = it), boss = true).won }
                        if (bossManual != null) change(CultivationProgression.select(s, bossManual))
                    }
                    change(CultivationEngine.challenge(s).first)
                }
            }
            if (upgrades == 0 && GearSlot.entries.all { (s.enhancements[it] ?: 0) == 10 }) upgrades = minute
        }
        assertTrue("seed=$seed enhanced=$enhanced must clear", 6 in s.bosses)
        assertTrue(s.gear.none { it.quality == Quality.ORANGE && s.equipped[it.slot] == it.id })
        return Run(minute, collected, upgrades, refining, training, s)
    }
    private fun median(values: List<Int>) = values.sorted()[49]
    @Test fun hundredSeedsMeasureTwoVisitsPerDayWithAndWithoutNewSpendingAndManuals() {
        for (enhanced in listOf(false, true)) {
            val runs = (1L..100).map { run(it, enhanced) }
            val times = runs.map { it.minutes }.sorted()
            println("ECONOMY enhanced=$enhanced seeds=100 clear minutes p10/median/p90=${times[9]}/${times[49]}/${times[89]}" +
                " manualsAll median=${median(runs.map { it.collectedAt })} fullSlots median=${median(runs.map { it.upgradesAt })}" +
                " refineSpentMedian=${runs.map { it.spentRefining }.sorted()[49]} trainingSpentMedian=${runs.map { it.spentTraining }.sorted()[49]}" +
                " finalStoneMedian=${runs.map { it.state.stones }.sorted()[49]}")
            if (enhanced) assertTrue("target median 2–4 days", times[49] in 2880..5760)
            else assertTrue("no special random manual required", times[49] <= 10080)
            assertTrue(runs.all { it.collectedAt in 1..1440 })
        }
        val done = run(42, true).state
        for (r in listOf(1L, 5L)) {
            val previous = done.copy(reincarnations = r - 1)
            val heirloom = previous.gear.filter { it.quality != Quality.ORANGE }.maxBy { it.strength }
            val reborn = CultivationEngine.transition(previous, CultivationEngine.reincarnate(previous, heirloom.id, previous.world))
            val next = run(42, true, reborn)
            println("ECONOMY reincarnation=$r clearMinutes=${next.minutes} inheritedManualLevels=${reborn.manualLevels.values.sum()}")
            assertTrue(next.minutes <= 5760)
        }
    }
}
