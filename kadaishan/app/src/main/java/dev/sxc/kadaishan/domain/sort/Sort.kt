package dev.sxc.kadaishan.domain.sort

import dev.sxc.kadaishan.model.SortClause
import dev.sxc.kadaishan.model.SortSpec
import kotlin.random.Random

// ═══ 排序白名单 → SQL ORDER BY（架构 §6 #3：SortSpec.Random 是独立 shuffle 路径，不是 SQL 排序子句）═══

/** DATE_TAKEN 排序表达式：date_taken 可空（架构 §2 空值策略），缺拍摄时间回退 date_added（秒 → 毫秒）。 */
private const val DATE_TAKEN_EXPR = "CASE WHEN date_taken IS NULL THEN date_added*1000 ELSE date_taken END"

/** SqlOrder → 白名单内的 ORDER BY 子句；Random → 稳定 _ID 序（内存 shuffle 由 fisherYates 独立完成）。 */
fun SortSpec.toOrderBy(): String = when (this) {
    is SortSpec.SqlOrder -> when (clause) {
        SortClause.FILE_SIZE_DESC -> "size DESC"
        SortClause.DATE_TAKEN_DESC -> "$DATE_TAKEN_EXPR DESC"
        SortClause.DATE_TAKEN_ASC -> "$DATE_TAKEN_EXPR ASC"
    }
    SortSpec.Random -> "_ID ASC"
}

/** 纯 Fisher-Yates 洗牌（架构 §8 可测性口径：Random 构造注入，不为测试新增接口）。
 *  返回新列表，不修改入参 items；同种子同结果（可重放）。 */
internal fun <T> fisherYates(items: List<T>, random: Random): List<T> {
    val shuffled = items.toMutableList()
    for (i in shuffled.indices.reversed()) {
        if (i == 0) break
        val j = random.nextInt(i + 1)
        val tmp = shuffled[i]
        shuffled[i] = shuffled[j]
        shuffled[j] = tmp
    }
    return shuffled
}
