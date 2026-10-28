package dev.sxc.kadaishan.domain.cultivation

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToLong

enum class GearSlot(val label: String) { WEAPON("法器"), ROBE("法衣"), CHARM("灵佩") }
enum class Quality(val label: String, val tenths: Int) {
    WHITE("白", 10), BLUE("蓝", 14), PURPLE("紫", 20), ORANGE("橙", 28)
}
enum class Practice(val label: String) { SWORD("御剑诀"), LIFE("长生诀") }
data class Gear(val id: Long, val slot: GearSlot, val tier: Int, val quality: Quality, val strength: Long) {
    val name: String get() = listOf("竹", "青锋", "雾隐", "寒玉", "赤霞", "引雷")[tier - 1] +
        when (slot) { GearSlot.WEAPON -> "剑"; GearSlot.ROBE -> "衣"; GearSlot.CHARM -> "佩" }
}
data class Battle(val won: Boolean, val turns: Int, val playerHp: Long, val enemyHp: Long)
data class Earnings(
    val minutes: Long = 0, val wins: Long = 0, val cultivationTenths: Long = 0,
    val stones: Long = 0, val drops: Long = 0, val dissolved: Long = 0,
    val bestQuality: Int = -1,
)
data class CultivationState(
    val version: Int = 1, val enabled: Boolean = true, val paused: Boolean = false,
    val level: Int = 1, val cultivationTenths: Long = 300, val stones: Long = 0,
    val map: Int = 1, val unlockedMap: Int = 1, val bosses: Set<Int> = emptySet(),
    val practice: Practice = Practice.SWORD, val enhancements: Map<GearSlot, Int> = emptyMap(),
    val gear: List<Gear> = listOf(Gear(-1, GearSlot.WEAPON, 1, Quality.WHITE, 4), Gear(-2, GearSlot.ROBE, 1, Quality.WHITE, 4)),
    val equipped: Map<GearSlot, Long> = mapOf(GearSlot.WEAPON to -1L),
    val heirloomId: Long? = null, val reincarnations: Long = 0, val world: Long = 1,
    val highestLevel: Int = 1, val collection: Set<String> = emptySet(),
    val seed: Long = 1, val sequence: Long = 0, val dryWins: Int = 0, val dullDrops: Int = 0,
    val lastMillis: Long, val carryMillis: Long = 0,
    val giftMillis: Long? = null, val consumedRound: Long = 0,
    val report: Earnings = Earnings(),
)

/** Pure deterministic rules. No media, Android, network, animation, or real-time work. */
object CultivationEngine {
    val mapNames = listOf("竹林山径", "荒山古道", "迷雾泽地", "寒潭洞窟", "赤岩遗迹", "雷云峰")
    val monsterNames = listOf("山野木灵", "荒山石妖", "赤尾妖狐", "寒潭蛟影", "赤岩巨兽", "雷云鹏")
    private val hp = longArrayOf(40, 85, 155, 260, 420, 650)
    private val atk = longArrayOf(8, 16, 30, 48, 75, 110)
    private val xp = longArrayOf(2, 3, 5, 8, 12, 18)
    private val coins = longArrayOf(2, 4, 7, 11, 16, 23)
    private val bossCoins = longArrayOf(40, 80, 140, 220, 320, 460)
    const val MINUTE = 60_000L
    fun add(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b
    fun multiply(a: Long, b: Long): Long = if (a != 0L && b > Long.MAX_VALUE / a) Long.MAX_VALUE else a * b
    fun nextCost(level: Int): Long = when (level) {
        1 -> 30; 2 -> 45; 3 -> 70
        in 4..17 -> ceil(300 * 1.4.pow(level - 4)).toLong()
        else -> Long.MAX_VALUE
    }
    fun realm(s: CultivationState): String = if (s.level <= 9) "炼气${s.level}层" else "筑基${s.level - 9}层"
    private fun root(base: Long, r: Long): Long = add(base, multiply(base, r) / 20)
    fun stats(s: CultivationState): Pair<Long, Long> {
        var attack = root(10L + 3L * (s.level - 1), s.reincarnations)
        var health = root(80L + 20L * (s.level - 1), s.reincarnations)
        s.gear.filter { s.equipped[it.slot] == it.id }.forEach { g ->
            val factor = 10L + (s.enhancements[g.slot] ?: 0)
            val ga = if (g.slot == GearSlot.ROBE) 0 else g.strength
            val gh = when (g.slot) { GearSlot.WEAPON -> 0; GearSlot.ROBE -> multiply(g.strength, 8); GearSlot.CHARM -> multiply(g.strength, 4) }
            attack = add(attack, multiply(ga, factor) / 10)
            health = add(health, multiply(gh, factor) / 10)
        }
        return attack to health
    }
    fun battle(s: CultivationState, boss: Boolean = false): Battle {
        val (attack, maxHp) = stats(s)
        var player = maxHp
        var enemy = hp[s.map - 1] * if (boss) 2 else 1
        val damage = if (boss) atk[s.map - 1] * 3 / 2 else atk[s.map - 1]
        for (turn in 1..5) {
            if (s.practice == Practice.LIFE) player = minOf(maxHp, add(player, maxOf(1, maxHp / 100 * 12 + maxHp % 100 * 12 / 100)))
            val hit = if (s.practice == Practice.SWORD && turn % 3 == 0) multiply(attack, 2) else attack
            enemy = maxOf(0, enemy - hit)
            if (enemy == 0L) return Battle(true, turn, player, 0)
            player = maxOf(0, player - damage)
            if (player == 0L) return Battle(false, turn, 0, enemy)
        }
        return Battle(false, 5, player, enemy)
    }
    private fun random(s: CultivationState, salt: Long, bound: Int): Int {
        var x = s.seed xor (s.sequence * -7046029254386353131L) xor salt
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return (((x xor (x ushr 31)) ushr 1) % bound).toInt()
    }
    private val gearOrder = compareByDescending<Gear> { it.strength }
        .thenByDescending { it.quality.ordinal }.thenByDescending { it.tier }.thenBy { it.id }
    private fun trim(s: CultivationState): CultivationState {
        val kept = s.gear.filter { s.equipped[it.slot] == it.id || it.id == s.heirloomId }.toMutableList()
        GearSlot.entries.forEach { slot -> kept += s.gear.filter { it.slot == slot && it !in kept }.sortedWith(gearOrder).take(3) }
        val dissolved = s.gear.filter { it !in kept }
        val proceeds = dissolved.sumOf { it.tier.toLong() * it.quality.tenths * 4 / 10 }
        return s.copy(gear = kept, stones = add(s.stones, proceeds), report = s.report.copy(
            stones = add(s.report.stones, proceeds), dissolved = add(s.report.dissolved, dissolved.size.toLong())))
    }
    fun simulate(s: CultivationState, minutes: Long): CultivationState {
        require(minutes >= 0)
        if (minutes == 0L) return s
        val victory = battle(s).won
        val cultivation = multiply(multiply(minutes, xp[s.map - 1]), add(10, s.reincarnations))
        val income = if (victory) multiply(minutes, coins[s.map - 1]) else 0
        var result = s.copy(cultivationTenths = add(s.cultivationTenths, cultivation), stones = add(s.stones, income),
            report = s.report.copy(minutes = add(s.report.minutes, minutes), wins = add(s.report.wins, if (victory) minutes else 0),
                cultivationTenths = add(s.report.cultivationTenths, cultivation), stones = add(s.report.stones, income)))
        // Repository limits each batch, keeping cancellation/resumption bounded without capping rewards.
        var processed = 0L
        while (processed < minutes) {
            processed++
            if (result.sequence == Long.MAX_VALUE) return result
            result = result.copy(sequence = add(result.sequence, 1))
            if (victory) {
                if (random(result, 11, 100) < 5 || result.dryWins >= 19) {
                    val roll = random(result, 29, 100)
                    var quality = when { roll < 55 -> Quality.WHITE; roll < 90 -> Quality.BLUE; roll < 99 -> Quality.PURPLE; else -> Quality.ORANGE }
                    if (result.dullDrops >= 49 && quality.ordinal < 2) quality = Quality.PURPLE
                    val slot = GearSlot.entries[random(result, 19, 3)]
                    val strength = (4 * 1.65.pow(result.map - 1) * quality.tenths / 10 * (0.9 + random(result, 37, 3) * 0.1)).roundToLong()
                    val g = Gear(result.sequence, slot, result.map, quality, strength)
                    result = trim(result.copy(gear = result.gear + g, dryWins = 0,
                        dullDrops = if (quality.ordinal >= 2) 0 else result.dullDrops + 1,
                        collection = result.collection + "${g.tier}:${g.slot.name}:${g.quality.name}",
                        report = result.report.copy(drops = add(result.report.drops, 1), bestQuality = maxOf(result.report.bestQuality, quality.ordinal))))
                } else result = result.copy(dryWins = result.dryWins + 1)
            }
        }
        return result
    }
    fun catchUp(s: CultivationState, now: Long, batch: Long = 1440): CultivationState {
        require(batch in 1..1440)
        if (now < s.lastMillis) return s
        if (s.paused) return s.copy(lastMillis = now)
        val delta = add(now - s.lastMillis, s.carryMillis)
        val all = delta / MINUTE
        val n = minOf(all, batch)
        val result = simulate(s, n)
        return if (n == all) result.copy(lastMillis = now, carryMillis = delta % MINUTE)
        else result.copy(lastMillis = add(s.lastMillis, n * MINUTE - s.carryMillis), carryMillis = 0)
    }
    fun pending(s: CultivationState, now: Long): Long = if (s.paused || now < s.lastMillis) 0 else add(now - s.lastMillis, s.carryMillis) / MINUTE
    fun breakthrough(s: CultivationState, all: Boolean = false): CultivationState {
        var r = s
        while (r.level < 18 && (r.level != 9 || 3 in r.bosses)) {
            val cost = nextCost(r.level) * 10
            if (r.cultivationTenths < cost) break
            r = r.copy(level = r.level + 1, cultivationTenths = r.cultivationTenths - cost, highestLevel = maxOf(r.highestLevel, r.level + 1))
            if (!all) break
        }
        return r
    }
    fun selectMap(s: CultivationState, map: Int): CultivationState {
        require(map in 1..6 && map <= s.unlockedMap && s.level >= 1 + (map - 1) * 3) { "先提升境界并击败上一图守关妖兽" }
        return s.copy(map = map)
    }
    fun challenge(s: CultivationState): Pair<CultivationState, Battle> {
        require(s.level >= s.map * 3) { "达到第 ${s.map * 3} 层修为境界后可挑战" }
        val result = battle(s, true)
        if (!result.won || s.map in s.bosses) return s to result
        return s.copy(bosses = s.bosses + s.map, unlockedMap = minOf(6, maxOf(s.unlockedMap, s.map + 1)), stones = add(s.stones, bossCoins[s.map - 1])) to result
    }
    fun equip(s: CultivationState, id: Long): CultivationState {
        val g = s.gear.first { it.id == id }
        return trim(s.copy(equipped = s.equipped + (g.slot to id)))
    }
    fun upgrade(s: CultivationState, slot: GearSlot): CultivationState {
        val level = s.enhancements[slot] ?: 0
        require(level < 10) { "已强化至 +10" }
        val cost = 20L * (level + 1) * (level + 1)
        require(s.stones >= cost) { "灵石不足，需要 $cost" }
        return s.copy(stones = s.stones - cost, enhancements = s.enhancements + (slot to level + 1))
    }
    fun completedRound(s: CultivationState, sequence: Long, hasDecision: Boolean, now: Long): CultivationState {
        if (sequence <= s.consumedRound) return s
        val eligible = !s.paused && hasDecision && (s.giftMillis == null || now >= s.giftMillis && now - s.giftMillis >= 86_400_000)
        val consumed = s.copy(consumedRound = sequence)
        return if (eligible) simulate(consumed, 30).copy(giftMillis = now) else consumed
    }
    fun reincarnate(s: CultivationState, heirloom: Long, expectedWorld: Long): CultivationState {
        require(s.world == expectedWorld && s.level == 18 && 6 in s.bosses) { "本世达到筑基九层并通关雷云峰后可转世" }
        val chosen = s.gear.first { it.id == heirloom }
        val gear = if (chosen.slot == GearSlot.WEAPON) listOf(chosen) else listOf(chosen, Gear(-1, GearSlot.WEAPON, 1, Quality.WHITE, 4))
        return s.copy(level = 1, cultivationTenths = 0, stones = s.stones / 10, map = 1, unlockedMap = 1,
            bosses = emptySet(), enhancements = emptyMap(), gear = gear, equipped = gear.associate { it.slot to it.id },
            heirloomId = chosen.id, reincarnations = add(s.reincarnations, 1), world = add(s.world, 1), report = Earnings())
    }
}
