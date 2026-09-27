package dev.sxc.kadaishan.model

import android.net.Uri

/** 删除模式。回收站是产品默认且可恢复的路径。 */
enum class DeleteMode { TRASH, PERMANENT }

/** 结算快照：结果页只认这份不可变副本，不重新读取卡片会话。 */
data class SnapItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)

/** MATCH_INCLUDE 的专用三列查询结果。 */
data class RecheckRow(
    val id: Long,
    val isTrashed: Boolean,
    val dateExpiresSec: Long?,
)

enum class SettlementItemStatus { TRASHED, DELETED, NOT_EXECUTED, UNKNOWN }

data class SettlementItemResult(
    val item: SnapItem,
    val status: SettlementItemStatus,
    val dateExpiresSec: Long? = null,
)

/** 进程被系统杀死时保留的最小事实，不存未结算的卡片标记。 */
data class PendingSettlement(
    val snapshot: List<SnapItem>,
    val mode: DeleteMode,
    val batchesDone: Int,
    val totalBatches: Int,
)
