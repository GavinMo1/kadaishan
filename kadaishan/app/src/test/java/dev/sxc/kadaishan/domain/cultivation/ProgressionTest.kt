package dev.sxc.kadaishan.domain.cultivation

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ProgressionTest {
    private fun fresh() = CultivationState(lastMillis = 0, seed = 42)
    private fun manual(p: Practice, n: Int) = fresh().copy(practice = p,
        manualLevels = Practice.entries.associateWith { if (it == p) n else 1 })

    @Test fun combatManualLevelsActuallyChangeTraceAndKeepOriginalLevelOne() {
        for (p in listOf(Practice.SWORD, Practice.LIFE)) {
            val current = manual(p, 1)
            assertEquals(CultivationEngine.battle(current.copy(version = 2), captureTrace = true),
                CultivationEngine.battle(current, captureTrace = true))
        }
        val old = manual(Practice.SWORD, 1).copy(map = 3, level = 4)
        val maxSword = old.copy(manualLevels = old.manualLevels + (Practice.SWORD to 20))
        val a = CultivationEngine.battle(old, captureTrace = true)
        val b = CultivationEngine.battle(maxSword, captureTrace = true)
        assertTrue(b.trace[2].damage > a.trace[2].damage)
        val assault = CultivationEngine.battle(manual(Practice.ASSAULT, 20), captureTrace = true)
        assertEquals(34, assault.trace.first().damage)
        assertTrue(assault.trace.first().assault)
        val body = CultivationEngine.battle(manual(Practice.BODY, 20), captureTrace = true)
        assertEquals(4, body.trace.first().retaliation)
        assertEquals(4, body.trace.first().prevented)
        val life = CultivationEngine.battle(manual(Practice.LIFE, 20).copy(map = 2), captureTrace = true)
        assertEquals(16, life.trace[1].healing)
    }

    @Test fun utilityManualsAffectOnlyTheirNamedSourcesAtBothRanks() {
        val plain = CultivationEngine.simulate(fresh(), 3)
        for (n in listOf(1, 20)) {
            val origin = CultivationEngine.simulate(manual(Practice.ORIGIN, n), 3)
            val wealth = CultivationEngine.simulate(manual(Practice.WEALTH, n), 3)
            assertTrue(origin.cultivationTenths > plain.cultivationTenths)
            assertEquals(plain.stones, origin.stones)
            assertTrue(wealth.stones > plain.stones)
            assertEquals(plain.cultivationTenths, wealth.cultivationTenths)
            assertEquals(plain.gear, wealth.gear)
        }
        val s = manual(Practice.ORIGIN, 20).copy(stones = 1000, cultivationTenths = 0)
        assertEquals(300, CultivationProgression.refinement(s, 1000).xpTenths) // no multiplier on purchased XP
    }

    @Test fun winsAddExperienceButFailuresGiveOnlyUncappedBaseAndNoManualPity() {
        val won = CultivationEngine.simulate(fresh(), 3)
        assertEquals(60, won.report.baseCultivation)
        assertEquals(20, won.report.killCultivation)
        assertEquals(380, won.cultivationTenths)
        val lost = CultivationEngine.simulate(fresh().copy(map = 6, manualDryWins = 199), 100)
        assertEquals(18000, lost.report.baseCultivation)
        assertEquals(0, lost.report.killCultivation)
        assertEquals(0, lost.report.manualDrops)
        assertEquals(199, lost.manualDryWins)
    }

    @Test fun twoHundredVictoryGuaranteeCollectsMissingBeforeDuplicatesAndPreservesEquipmentStream() {
        var s = fresh()
        val seen = mutableSetOf<Practice>()
        repeat(4) {
            s = CultivationProgression.manualVictory(s.copy(manualDryWins = 199, sequence = s.sequence + 1))
            val new = s.manualLevels.keys - setOf(Practice.SWORD, Practice.LIFE) - seen
            assertEquals(1, new.size); seen += new
            assertEquals(0, s.manualDryWins)
        }
        assertEquals(6, s.manualLevels.size)
        assertEquals(0, s.insight)
        val duplicate = CultivationProgression.manualVictory(s.copy(manualDryWins = 199, sequence = s.sequence + 1))
        assertEquals(1, duplicate.insight)
        assertEquals(1, duplicate.report.insightDrops)
        assertEquals(5, duplicate.report.manualDrops)
        for (seed in 1L..30) {
            val start = fresh().copy(seed = seed)
            val after = CultivationEngine.simulate(start, 480)
            val old = CultivationEngine.simulate(start.copy(version = 2), 480)
            assertEquals(old.gear, after.gear)
            assertEquals(old.dryWins, after.dryWins)
            assertEquals(old.dullDrops, after.dullDrops)
            assertEquals(old.sequence, after.sequence)
            assertEquals(6, after.manualLevels.size) // 800 guaranteed wins, three minutes is five wins
        }
    }

    @Test fun thirtyDaysAndArbitraryChunksHaveExactlyTheSameWalletReportLootAndAllRemainders() {
        for (p in listOf(Practice.ORIGIN, Practice.WEALTH, Practice.BODY)) {
            val initial = manual(p, 2).copy(reincarnations = 1, baseRemainder = 71, killRemainder = 431, stoneRemainder = 77)
            val once = CultivationEngine.simulate(initial, 43_200)
            val random = Random(8)
            var split = initial; var left = 43_200L
            while (left > 0) {
                val batch = minOf(left, random.nextLong(1, 1441))
                split = CultivationEngine.simulate(split, batch); left -= batch
            }
            assertEquals(once, split)
            var foreground = initial
            repeat(43_200) { foreground = CultivationEngine.simulate(foreground, 1) }
            assertEquals(once, foreground)
            assertEquals(43_200, once.report.minutes)
        }
    }

    @Test fun reviewFractionCounterexampleIsStableAndReportClearingNeverDiscardsFractions() {
        val initial = manual(Practice.ORIGIN, 2).copy(reincarnations = 1)
        val once = CultivationEngine.simulate(initial, 3)
        var split = initial
        repeat(3) { split = CultivationEngine.simulate(split, 1) }
        assertEquals(once, split)
        val cleared = once.copy(report = Earnings())
        val a = CultivationEngine.simulate(cleared, 1)
        val b = CultivationEngine.simulate(once, 1)
        assertEquals(b.cultivationTenths, a.cultivationTenths)
        assertEquals(b.baseRemainder, a.baseRemainder)
        assertEquals(b.killRemainder, a.killRemainder)
    }

    @Test fun refinementUsesExistingWealthSupportsFractionalGapAndNeverSkipsGates() {
        val wealthy = fresh().copy(level = 17, stones = 160000, cultivationTenths = 0)
        val q = CultivationProgression.refinement(wealthy, Long.MAX_VALUE)
        assertEquals(159999, q.cost)
        assertEquals(76190, q.xpTenths)
        assertEquals(1, CultivationProgression.refine(wealthy, q).stones)
        val fractional = fresh().copy(stones = 1000, cultivationTenths = 299)
        val small = CultivationProgression.refinement(fractional, Long.MAX_VALUE)
        assertEquals(5, small.cost); assertEquals(10, small.xpTenths)
        val converted = CultivationProgression.refine(fractional, small)
        assertEquals(9, CultivationEngine.breakthrough(converted).cultivationTenths)
        assertEquals(Earnings(), converted.report)
        assertEquals(0, CultivationProgression.refinement(fractional.copy(stones = 4), 1000).cost)
        assertEquals(0, CultivationProgression.refinement(wealthy.copy(level = 18), Long.MAX_VALUE).cost)
        val gate = wealthy.copy(level = 9, cultivationTenths = 0)
        val filled = CultivationProgression.refine(gate, CultivationProgression.refinement(gate, Long.MAX_VALUE))
        assertEquals(9, CultivationEngine.breakthrough(filled, true).level)
        assertEquals(0, CultivationProgression.refinement(filled, Long.MAX_VALUE).cost)
    }

    @Test fun staleAndReplayedSpendingQuotesCannotChargeTwice() {
        val s = fresh().copy(stones = 160000, insight = 190, cultivationTenths = 0)
        val q = CultivationProgression.refinement(s, 1000)
        val bought = CultivationProgression.refine(s, q)
        assertThrows { CultivationProgression.refine(bought, q) }
        assertThrows { CultivationProgression.refine(s.copy(world = 2), q) }
        val train = CultivationProgression.training(s, Practice.SWORD, false)
        val trained = CultivationProgression.train(s, train)
        assertThrows { CultivationProgression.train(trained, train) }
        assertThrows { CultivationProgression.train(s.copy(insight = 0), train) }
        assertThrows { CultivationProgression.select(s, Practice.ORIGIN) }
    }

    @Test fun batchTrainingMatchesSingleUpgradesAndConsumesExactlyDefinedBudget() {
        val initial = fresh().copy(stones = 1434500, insight = 190)
        val batch = CultivationProgression.train(initial, CultivationProgression.training(initial, Practice.SWORD, true))
        var single = initial
        repeat(19) { single = CultivationProgression.train(single, CultivationProgression.training(single, Practice.SWORD, false)) }
        assertEquals(batch, single)
        assertEquals(20, batch.manualLevels[Practice.SWORD])
        assertEquals(0, batch.stones); assertEquals(0, batch.insight)
        assertEquals(0, CultivationProgression.training(batch, Practice.SWORD, true).cost)
        assertEquals(0, CultivationProgression.training(initial.copy(insight = 0), Practice.SWORD, true).cost)
    }

    @Test fun rebirthKeepsAllManualProgressAndResetsOnlyLifeFractions() {
        val s = manual(Practice.ORIGIN, 20).copy(level = 18, bosses = setOf(6), insight = 47, manualDryWins = 199,
            baseRemainder = 499, killRemainder = 499, stoneRemainder = 99, stones = 160000)
        val r = CultivationEngine.reincarnate(s, -1, s.world)
        assertEquals(s.manualLevels, r.manualLevels); assertEquals(s.practice, r.practice)
        assertEquals(s.insight, r.insight); assertEquals(199, r.manualDryWins)
        assertEquals(0, r.baseRemainder); assertEquals(0, r.killRemainder); assertEquals(0, r.stoneRemainder)
        assertEquals(16000, r.stones); assertEquals(Earnings(), r.report)
    }

    @Test fun maximumNumericInputsRemainNonnegativeAndReportUsesSaturatingSourceSum() {
        val s = manual(Practice.ORIGIN, 20).copy(reincarnations = Long.MAX_VALUE,
            cultivationTenths = Long.MAX_VALUE - 1, stones = Long.MAX_VALUE - 1)
        val a = CultivationEngine.simulate(s, 1440)
        assertEquals(Long.MAX_VALUE, a.cultivationTenths)
        assertTrue(a.stones > 0)
        assertEquals(CultivationEngine.add(CultivationEngine.add(a.report.baseCultivation, a.report.killCultivation),
            a.report.legacyCultivation), a.report.cultivationTenths)
        assertTrue(a.baseRemainder in 0..499 && a.killRemainder in 0..499)
    }

    @Test fun bonusesAndRealClockUseTheSameNewRewardsWithoutAdvancingWallTime() {
        val initial = manual(Practice.WEALTH, 2).copy(stoneRemainder = 99, killRemainder = 499)
        val gift = CultivationEngine.completedRound(initial, 1, true, 0)
        val ordinary = CultivationEngine.simulate(initial, 30)
        assertEquals(ordinary.copy(consumedRound = 1, giftMillis = 0,
            report = ordinary.report.copy(bonusMinutes = 30)), gift)
        assertEquals(initial.lastMillis, gift.lastMillis)
        assertEquals(gift, CultivationEngine.completedRound(gift, 1, true, 90_000_000))
        val paused = CultivationEngine.catchUp(gift.copy(paused = true), 30L * 86_400_000)
        assertEquals(gift.manualLevels, paused.manualLevels)
        assertEquals(gift.insight, paused.insight); assertEquals(gift.report, paused.report)
        assertEquals(gift.baseRemainder, paused.baseRemainder)
    }

    @Test fun allManualRanksPreserveCombatMonotonicityWhenEquipmentImproves() {
        for (p in Practice.entries) for (rank in listOf(1, 20)) for (map in 1..6) {
            val start = manual(p, rank).copy(map = map, gear = listOf(
                Gear(1, GearSlot.WEAPON, map, Quality.BLUE, 70), Gear(2, GearSlot.ROBE, map, Quality.BLUE, 50)),
                equipped = mapOf(GearSlot.WEAPON to 1L, GearSlot.ROBE to 2L))
            val before = CultivationEngine.battle(start)
            val after = CultivationEngine.battle(start.copy(gear = start.gear.map { it.copy(strength = it.strength + 1) }))
            if (before.won) { assertTrue(after.won); assertTrue(after.cycleMillis <= before.cycleMillis) }
            assertTrue(after.trace.isEmpty())
        }
    }

    @Test fun cancellingNewOfflineSimulationDoesNotMutateOriginalAndKeepsBoundedCheckpoints() {
        val initial = fresh()
        var checks = 0
        try {
            CultivationEngine.simulate(initial, 43_200, checkActive = {
                if (++checks == 4) throw java.util.concurrent.CancellationException("leave game")
            })
            fail("ignored cancellation")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(4, checks)
        assertEquals(0, initial.sequence); assertEquals(0, initial.report.manualDrops)
        assertEquals(2, initial.manualLevels.size)
    }

    private fun assertThrows(action: () -> Unit) {
        try { action(); fail("invalid operation accepted") } catch (_: IllegalArgumentException) { }
    }
}
