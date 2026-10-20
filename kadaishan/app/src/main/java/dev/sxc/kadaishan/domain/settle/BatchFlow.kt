package dev.sxc.kadaishan.domain.settle

import dev.sxc.kadaishan.model.SnapItem

// ═══ 批次推进纯状态机（架构 §8 可测性口径：状态 + 事件 → 状态 + 副作用描述）═══
//
// 结算删除与回收站找回/清空共用的批次队列 + 失败降档（原两套 UI 内联实现收编于此）。
// 不含任何系统调用：构建 IntentSender / 启动系统弹窗 / 回查 / 暂存落盘全在调用方。
//
// 调用契约：
//  · current() 取当前批 → 发送；用户确认 → advance()；构建/发送期失败 → degradeCurrent()；
//  · degradeCurrent: Retrying = 已原位拆为更小子批（重发 current()）；
//    Skipped(整批) = 拆无可拆已跳过，调用方决定计失败（回收站口径）或交回查裁决（结算页口径）；
//  · 用户拒绝 → abortRest(当前批大小)：当前批及剩余全部计失败并不再发批（拒绝后续弹只会连续弹窗）；
//  · finished = 全部批次已消耗 → 调用方进入回查/收尾。

/** 降档结果。 */
sealed interface DegradeResult {
    /** 已原位拆为子批：重发 current() 即可。子批尺寸严格递减，递归降档必终止。 */
    data object Retrying : DegradeResult

    /** 拆无可拆（≤ [BatchFlow.FALLBACK_MIN] 项）：该批已从队列摘除并随结果返回。 */
    data class Skipped(val batch: List<SnapItem>) : DegradeResult
}

class BatchFlow(
    items: List<SnapItem>,
    batchCap: Int,
) {
    init {
        require(batchCap > 0) { "batchCap 必须为正：$batchCap" }
    }

    private val batches: MutableList<List<SnapItem>> = items.chunked(batchCap).toMutableList()

    /** 当前批下标（单调前进；降档拆批时不推进——子批原位替换）。 */
    var index: Int = 0
        private set

    /** 已计失败项数（验活剔除 / 拆无可拆 / 用户拒绝的累计口径）。 */
    var failedCount: Int = 0
        private set

    /** 当前队列内批次数（降档拆批会增多；展示「第 i/N 批」用）。 */
    val totalBatches: Int
        get() = batches.size

    val finished: Boolean
        get() = index >= batches.size

    /** 当前待发送批；null = 已耗尽（finished）。 */
    fun current(): List<SnapItem>? = batches.getOrNull(index)

    /** 当前批已成功发出（用户确认）→ 推进下一批。 */
    fun advance() {
        if (!finished) index++
    }

    /** 计失败 n 项（验活剔除 / Skipped 批 / 自定义口径）。 */
    fun countFailed(n: Int) {
        if (n > 0) failedCount += n
    }

    /** 当前批构建/发送失败：降档拆批（PRD §5.2 逐档减半、下限 [FALLBACK_MIN]、每档只重试 1 次——
     *  重试次数由「尺寸严格递减必终止」结构性保证，无需计数器）。 */
    fun degradeCurrent(): DegradeResult {
        val batch = batches.getOrNull(index) ?: return DegradeResult.Skipped(emptyList())
        if (batch.size <= FALLBACK_MIN) {
            batches.removeAt(index) // index 不动 → 指向下一批
            return DegradeResult.Skipped(batch)
        }
        val subs = batch.chunked(maxOf(batch.size / 2, FALLBACK_MIN))
        batches.removeAt(index)
        batches.addAll(index, subs)
        return DegradeResult.Retrying
    }

    /** 用户拒绝：当前批（outgoingSize 项）及剩余批全部计失败，流程终止（不再发批）。 */
    fun abortRest(outgoingSize: Int) {
        countFailed(outgoingSize + batches.drop(index + 1).sumOf { it.size })
        index = batches.size
    }

    companion object {
        /** 降档下限（PRD §5.2）：≤5 项不可再拆。 */
        const val FALLBACK_MIN = 5
    }
}
