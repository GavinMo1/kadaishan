package dev.sxc.kadaishan.domain.cultivation

import org.junit.Assert.*
import org.junit.Test

class CultivationEngineTest {
    private fun fresh(seed: Long = 42) = CultivationState(lastMillis = 0, seed = seed)
    private fun catch(s: CultivationState, now: Long): CultivationState {
        var r = s
        do { r = CultivationEngine.catchUp(r, now) } while (CultivationEngine.pending(r, now) > 0)
        return r
    }
    @Test fun fifteenDaysAreFullyAwardedAndReplayDoesNotAwardTwice() {
        val time = 15L * 24 * 60 * 60_000
        val result = catch(fresh(), time)
        assertEquals(21_600, result.report.minutes)
        assertEquals(21_600, result.sequence)
        assertEquals(21_600, result.report.wins)
        assertEquals(432_300, result.cultivationTenths)
        assertTrue(result.stones >= 43_200)
        assertEquals(result, catch(result, time))
    }
    @Test fun differentBatchSizesAndForegroundHaveIdenticalRewardsAndRandomness() {
        val original = fresh()
        val offline = catch(original, 480 * 60_000L + 17_000)
        var foreground = original
        for (m in 1..480) foreground = CultivationEngine.catchUp(foreground, m * 60_000L)
        foreground = CultivationEngine.catchUp(foreground, 480 * 60_000L + 17_000)
        assertEquals(offline, foreground)
        var tiny = original
        while (CultivationEngine.pending(tiny, 480 * 60_000L + 17_000) > 0) tiny = CultivationEngine.catchUp(tiny, 480 * 60_000L + 17_000, 7)
        assertEquals(offline, tiny)
    }
    @Test fun lossesStillTrainButGiveNoLootAndPreservePity() {
        val s = fresh().copy(map = 6, unlockedMap = 6, dryWins = 19, dullDrops = 49)
        val result = CultivationEngine.simulate(s, 30)
        assertFalse(CultivationEngine.battle(s).won)
        assertEquals(0, result.report.wins)
        assertEquals(0, result.report.drops)
        assertEquals(19, result.dryWins)
        assertEquals(49, result.dullDrops)
        assertEquals(5700, result.cultivationTenths)
    }
    @Test fun equipmentAndQualityGuaranteesApplyToBonusBattlesToo() {
        val s = fresh().copy(dryWins = 19, dullDrops = 49)
        val result = CultivationEngine.simulate(s, 1)
        assertEquals(1, result.report.drops)
        assertEquals(0, result.dryWins)
        assertEquals(0, result.dullDrops)
        assertTrue(result.gear.any { it.id > 0 && it.quality.ordinal >= Quality.PURPLE.ordinal })
    }
    @Test fun clockRollbackAndPausedTimeGiveNoFreeMinutesAndKeepRemainder() {
        val s = CultivationEngine.catchUp(fresh(), 59_000)
        assertEquals(0, s.report.minutes)
        assertEquals(s, CultivationEngine.catchUp(s, 1_000))
        val paused = CultivationEngine.catchUp(s.copy(paused = true), 15 * 86_400_000L)
        assertEquals(59_000, paused.carryMillis)
        val resumed = CultivationEngine.catchUp(paused.copy(paused = false), paused.lastMillis + 1000)
        assertEquals(1, resumed.report.minutes)
    }
    @Test fun breakthroughsStopAtMajorRealmGateAndPreserveExcessXp() {
        val s = fresh().copy(cultivationTenths = 10_000_000)
        val stopped = CultivationEngine.breakthrough(s, true)
        assertEquals(9, stopped.level)
        assertTrue(stopped.cultivationTenths > 0)
        val after = CultivationEngine.breakthrough(stopped.copy(bosses = setOf(3)), true)
        assertEquals(18, after.level)
    }
    @Test fun oneTimeBossRewardCannotBeFarmed() {
        val s = fresh().copy(level = 18)
        val (first, result) = CultivationEngine.challenge(s)
        assertTrue(result.won)
        assertEquals(40, first.stones)
        assertEquals(2, first.unlockedMap)
        assertEquals(first, CultivationEngine.challenge(first).first)
    }
    @Test fun enhancementIsAttachedToSlotAndSurvivesEquipmentChange() {
        val new = Gear(10, GearSlot.WEAPON, 2, Quality.BLUE, 8)
        val s = fresh().copy(stones = 20, gear = fresh().gear + new)
        val upgraded = CultivationEngine.upgrade(s, GearSlot.WEAPON)
        val equipped = CultivationEngine.equip(upgraded, 10)
        assertEquals(1, equipped.enhancements[GearSlot.WEAPON])
        assertEquals(18, CultivationEngine.stats(equipped).first)
        assertEquals(0, equipped.stones)
    }
    @Test fun unequippedHeirloomIsNeverDissolvedAndCanBeInheritedAgain() {
        val heirloom = Gear(50, GearSlot.WEAPON, 1, Quality.WHITE, 1)
        val others = (1..8).map { Gear(it.toLong(), GearSlot.WEAPON, 2, Quality.BLUE, 10L + it) }
        val s = fresh().copy(level = 18, bosses = setOf(6), gear = others + heirloom,
            equipped = mapOf(GearSlot.WEAPON to 8L), heirloomId = 50)
        val trimmed = CultivationEngine.equip(s, 7)
        assertTrue(trimmed.gear.any { it.id == 50L })
        assertEquals(5, trimmed.gear.size)
        val reborn = CultivationEngine.reincarnate(trimmed, 50, trimmed.world)
        assertEquals(50L, reborn.heirloomId)
        assertEquals(50L, reborn.equipped[GearSlot.WEAPON])
    }
    @Test fun reincarnationResetsOnlyThisLifeAndPreservesRandomnessAndCooldowns() {
        val s = catch(fresh(), 21_600 * 60_000L).copy(level = 18, bosses = (1..6).toSet(), map = 6, unlockedMap = 6,
            stones = 1234, enhancements = mapOf(GearSlot.WEAPON to 10), giftMillis = 1000, consumedRound = 27)
        val inherited = s.gear.first()
        val r = CultivationEngine.reincarnate(s, inherited.id, s.world)
        assertEquals(1, r.level); assertEquals(0, r.cultivationTenths); assertEquals(123, r.stones)
        assertEquals(1, r.reincarnations); assertEquals(2, r.world)
        assertEquals(1, r.map); assertTrue(r.bosses.isEmpty()); assertTrue(r.enhancements.isEmpty())
        assertEquals(s.sequence, r.sequence); assertEquals(s.seed, r.seed)
        assertEquals(s.dryWins, r.dryWins); assertEquals(s.dullDrops, r.dullDrops)
        assertEquals(s.lastMillis, r.lastMillis); assertEquals(s.giftMillis, r.giftMillis)
        assertEquals(27, r.consumedRound)
        try { CultivationEngine.reincarnate(r, inherited.id, s.world); fail("stale rebirth accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun permanentXpFractionSurvivesChunking() {
        val s = fresh().copy(reincarnations = 1, cultivationTenths = 0)
        val once = CultivationEngine.simulate(s, 17)
        var split = s
        repeat(17) { split = CultivationEngine.simulate(split, 1) }
        assertEquals(374, once.cultivationTenths)
        assertEquals(once, split)
        val r = fresh().copy(reincarnations = 5)
        assertEquals(12, CultivationEngine.stats(r).first - 4)
        assertEquals(100, CultivationEngine.stats(r).second)
    }
    @Test fun thirtyDaysHaveNoCapAndInventoryRemainsBounded() {
        val result = catch(fresh(), 43_200 * 60_000L)
        assertEquals(43_200, result.report.minutes)
        assertTrue(result.gear.size <= 13)
        assertEquals(result.report.drops + fresh().gear.size, result.report.dissolved + result.gear.size)
    }
    @Test fun roundGiftIsIdempotentAcrossOldEventReplaysAndPausedRounds() {
        val a = CultivationEngine.completedRound(fresh(), 1, true, 0)
        assertEquals(30, a.report.minutes)
        assertEquals(a, CultivationEngine.completedRound(a, 1, true, 86_400_000))
        val b = CultivationEngine.completedRound(a, 2, true, 86_400_000)
        assertEquals(60, b.report.minutes)
        assertEquals(b, CultivationEngine.completedRound(b, 1, true, 172_800_000))
        val paused = CultivationEngine.completedRound(b.copy(paused = true), 3, true, 172_800_000)
        assertEquals(3, paused.consumedRound)
        assertEquals(b.giftMillis, paused.giftMillis)
        val resumed = CultivationEngine.completedRound(paused.copy(paused = false), 4, true, 172_800_000)
        assertEquals(90, resumed.report.minutes)
    }
    @Test fun browsingAndUndoneDecisionsGiveNoBonusAndStillConsumeEvent() {
        val a = CultivationEngine.completedRound(fresh(), 1, false, 0)
        assertEquals(0, a.report.minutes)
        assertEquals(a, CultivationEngine.completedRound(a, 1, true, 0))
        val b = CultivationEngine.completedRound(a, 2, true, 0)
        assertEquals(30, b.report.minutes)
        val c = CultivationEngine.completedRound(b, 3, true, 86_399_999)
        assertEquals(30, c.report.minutes)
        assertEquals(c, CultivationEngine.completedRound(c, 3, true, 86_400_000))
    }
    @Test fun explicitPhotoAndVideoKeepsAreValidButBrowsingAndUndoAreNot() {
        val photo = dev.sxc.kadaishan.domain.session.BoardSession(listOf(1, 2), 1)
        assertFalse(photo.hasRoundDecision())
        photo.skip(1)
        assertTrue(photo.hasRoundDecision())
        photo.undo()
        assertFalse(photo.hasRoundDecision())
        val video = dev.sxc.kadaishan.domain.session.BoardSession(listOf(1, 2), 1)
        video.advance()
        assertFalse(video.hasRoundDecision())
        video.keepDisplayed(1)
        assertTrue(video.hasRoundDecision())
        video.startNewRound()
        assertFalse(video.hasRoundDecision())
    }
    @Test fun lowFrequencyPlayerCanClearFirstLifeWithoutEverEquippingOrangeGear() {
        var s = fresh()
        var visits = 0
        while (6 !in s.bosses && visits < 60) {
            s = CultivationEngine.simulate(s, 720)
            // Discard orange drops to prove they are not necessary for progression.
            s = s.copy(gear = s.gear.filter { it.quality != Quality.ORANGE })
            repeat(6) {
                GearSlot.entries.forEach { slot ->
                    val best = s.gear.filter { it.slot == slot }.maxByOrNull { it.strength }
                    if (best != null) s = CultivationEngine.equip(s, best.id)
                    while ((s.enhancements[slot] ?: 0) < 10) {
                        val n = (s.enhancements[slot] ?: 0) + 1
                        if (s.stones < 20L * n * n) break
                        s = CultivationEngine.upgrade(s, slot)
                    }
                }
                s = CultivationEngine.breakthrough(s, true)
                val map = (1..s.unlockedMap).last { s.level >= 1 + (it - 1) * 3 }
                s = CultivationEngine.selectMap(s, map)
                if (s.level >= s.map * 3) s = CultivationEngine.challenge(s).first
            }
            visits++
        }
        assertTrue("Two visits daily should clear the six maps within 30 days", 6 in s.bosses)
        assertTrue(s.gear.none { it.quality == Quality.ORANGE })
    }
}
