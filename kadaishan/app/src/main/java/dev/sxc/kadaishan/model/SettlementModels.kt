package dev.sxc.kadaishan.model

import java.io.Serializable

/** 删除模式。回收站是产品默认且可恢复的路径。 */
enum class DeleteMode { TRASH, PERMANENT }

/** 永久删除单轮硬上限（PRD §4.3）：G0 后允许在 [30,50] 内校准，禁止放宽到 ≥150。 */
const val PERM_DELETE_CAP: Int = 30

/** 结算快照：结果页只认这份不可变副本，不重新读取卡片会话。
 *  uri 为字符串形态（契约层纯 JVM 禁 android.*；Android Uri 在 MediaDeleter/UI 边界转换）。
 *  Serializable：结算面板 rememberSaveable 跨旋转/重建保存（Bundle 白名单）。 */
data class SnapItem(
    val id: Long,
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
) : Serializable

/** MATCH_INCLUDE 的专用三列查询结果。 */
data class RecheckRow(
    val id: Long,
    val isTrashed: Boolean,
    val dateExpiresSec: Long?,
)

/** 回收站页数据行（纯 JVM，uri 为字符串形态；只读视图，不落任何文件副本）。
 *  dateExpiresSec = DATE_EXPIRES（epoch 秒），null 表示系统未给出到期时间。 */
data class TrashEntry(
    val item: SnapItem,
    val dateExpiresSec: Long?,
)

enum class SettlementItemStatus { TRASHED, DELETED, NOT_EXECUTED, UNKNOWN }

/** 结果项可序列化：结算面板 rememberSaveable 跨重建保存（Bundle 白名单）。 */
data class SettlementItemResult(
    val item: SnapItem,
    val status: SettlementItemStatus,
    val dateExpiresSec: Long? = null,
) : Serializable

/** 进程被系统杀死时保留的最小事实，不存未结算的卡片标记。 */
data class PendingSettlement(
    val snapshot: List<SnapItem>,
    val mode: DeleteMode,
    val batchesDone: Int,
    val totalBatches: Int,
)
