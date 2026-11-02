package dev.sxc.kadaishan.domain.cultivation

import java.math.BigInteger

/** Source-owned fractional rewards; no wall clock, Android, or media dependencies. */
object CultivationProgression {
    private fun big(value: Long) = BigInteger.valueOf(value)
    private fun saturated(value: BigInteger) = value.min(big(Long.MAX_VALUE)).toLong()
    fun ratio(value: Long, numerator: Long, denominator: Long): Long =
        saturated(big(value).multiply(big(numerator)).divide(big(denominator)))
    fun rank(s: CultivationState, p: Practice): Int = s.manualLevels[p] ?: 0
    private fun percent(s: CultivationState, p: Practice, start: Long): Long =
        if (s.practice == p) start + 5L * (rank(s, p) - 1) else 100L

    data class Income(val base: Long, val kill: Long, val stones: Long,
        val baseRemainder: Int, val killRemainder: Int, val stoneRemainder: Int)
    fun income(s: CultivationState, minutes: Long, wins: Long, xp: Long, coins: Long): Income {
        val factor = big(xp).multiply(big(s.reincarnations).add(big(10)))
            .multiply(big(percent(s, Practice.ORIGIN, 130)))
        val base = factor.multiply(big(minutes)).multiply(big(5)).add(big(s.baseRemainder.toLong())).divideAndRemainder(big(500))
        val kill = factor.multiply(big(wins)).add(big(s.killRemainder.toLong())).divideAndRemainder(big(500))
        val stones = big(wins).multiply(big(coins)).multiply(big(percent(s, Practice.WEALTH, 140)))
            .add(big(s.stoneRemainder.toLong())).divideAndRemainder(big(100))
        return Income(saturated(base[0]), saturated(kill[0]), saturated(stones[0]), base[1].toInt(), kill[1].toInt(), stones[1].toInt())
    }

    internal fun manualVictory(s: CultivationState): CultivationState {
        if (CultivationEngine.random(s, 101, 100) != 0 && s.manualDryWins < 199)
            return s.copy(manualDryWins = s.manualDryWins + 1)
        val missing = Practice.entries.filter { rank(s, it) == 0 }
        val choices = missing.ifEmpty { Practice.entries }
        val p = choices[CultivationEngine.random(s, 103, choices.size)]
        val first = rank(s, p) == 0
        return s.copy(manualDryWins = 0,
            manualLevels = if (first) s.manualLevels + (p to 1) else s.manualLevels,
            insight = if (first) s.insight else CultivationEngine.add(s.insight, 1),
            report = s.report.copy(manualDrops = CultivationEngine.add(s.report.manualDrops, 1),
                insightDrops = CultivationEngine.add(s.report.insightDrops, if (first) 0 else 1),
                newManuals = if (first) s.report.newManuals + p else s.report.newManuals))
    }

    /** Only consumption-relevant state: time/report changes cannot invalidate an otherwise exact quote. */
    data class Wallet(val world: Long, val level: Int, val xp: Long, val stones: Long,
        val insight: Long, val levels: Map<Practice, Int>, val practice: Practice, val generation: Long)
    fun wallet(s: CultivationState) = Wallet(s.world, s.level, s.cultivationTenths, s.stones, s.insight,
        s.manualLevels, s.practice, s.rewardGeneration)
    data class Refinement(val expected: Wallet, val cost: Long, val xpTenths: Long)
    fun refinement(s: CultivationState, budget: Long): Refinement {
        require(s.version >= 3 && budget >= 0)
        if (s.level >= 18) return Refinement(wallet(s), 0, 0)
        val missing = (CultivationEngine.nextCost(s.level) * 10 - s.cultivationTenths).coerceAtLeast(0)
        val price = 4L + s.level
        val units = minOf((missing + 9) / 10, minOf(s.stones, budget) / price)
        return Refinement(wallet(s), units * price, units * 10)
    }
    fun refine(s: CultivationState, quote: Refinement): CultivationState {
        require(wallet(s) == quote.expected) { "资源或境界已更新，请检查新预览后确认" }
        require(quote.cost > 0 && refinement(s, quote.cost) == quote) { "当前无需炼化或灵石不足" }
        return s.copy(stones = s.stones - quote.cost, cultivationTenths = CultivationEngine.add(s.cultivationTenths, quote.xpTenths))
    }
    data class Training(val expected: Wallet, val practice: Practice, val target: Int, val cost: Long, val insight: Long)
    fun training(s: CultivationState, p: Practice, all: Boolean): Training {
        require(s.version >= 3)
        var n = rank(s, p); var stones = 0L; var insight = 0L
        if (n > 0) while (n < 20) {
            val next = n + 1; val price = 500L * next * next
            if (s.stones - stones < price || s.insight - insight < n) break
            stones += price; insight += n; n++
            if (!all) break
        }
        return Training(wallet(s), p, n, stones, insight)
    }
    fun train(s: CultivationState, quote: Training): CultivationState {
        require(wallet(s) == quote.expected) { "资源或功法已更新，请检查新预览后确认" }
        require(quote.cost > 0 && quote.cost <= s.stones && quote.insight <= s.insight &&
            trainingTo(s, quote.practice, quote.target) == quote) { "心得或灵石不足，或功法已满级" }
        return s.copy(stones = s.stones - quote.cost, insight = s.insight - quote.insight,
            manualLevels = s.manualLevels + (quote.practice to quote.target))
    }
    private fun trainingTo(s: CultivationState, p: Practice, target: Int): Training {
        val current = rank(s, p)
        if (current == 0 || target !in current + 1..20) return Training(wallet(s), p, current, 0, 0)
        val cost = (current + 1..target).sumOf { 500L * it * it }
        val insight = (current until target).sumOf { it.toLong() }
        return Training(wallet(s), p, target, cost, insight)
    }
    fun select(s: CultivationState, p: Practice): CultivationState {
        require(rank(s, p) > 0) { "尚未获得这本功法，挂机胜利有机会掉落" }
        return s.copy(practice = p)
    }
    fun description(p: Practice, level: Int): String {
        val n = level.coerceIn(1, 20)
        return when (p) {
            Practice.SWORD -> "每第三回合造成 ${200 + 5 * (n - 1)}% 攻击伤害"
            Practice.LIFE -> "每次攻击前回复最大生命的 ${12 + n - 1}%"
            Practice.ASSAULT -> "首回合造成 ${150 + 5 * (n - 1)}% 攻击伤害"
            Practice.BODY -> "反击伤害减少 ${20 + n - 1}%"
            Practice.ORIGIN -> "基础与击杀修为增加 ${30 + 5 * (n - 1)}%"
            Practice.WEALTH -> "普通战胜利灵石增加 ${40 + 5 * (n - 1)}%"
        }
    }
}
