package dev.sxc.kadaishan.model

import android.net.Uri

// ═══ 契约层（卡待删-架构设计.md v2.2 §2 逐字转写；改动 = 违反「契约先行」，须先改文档再改这里）═══

/** 媒体条目：默认 projection 一次取回。
 *  「预留位」= 数据类字段（带默认值），不是查询列；motionPhoto 须逐文件 XMP 解析，processedFlag 无 MediaStore 列。 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val dateTakenMs: Long?,
    val dateAddedSec: Long,
    val relativePath: String,
    val bucketName: String,
    val isTrashed: Boolean = false,
    val dateExpiresSec: Long? = null,
    // —— 预留位（默认不读，未来功能接入点）——
    val motionPhoto: Boolean? = null,
    val processedFlag: Boolean? = null,
    val sourceApp: String? = null,
)

// 空值策略（架构 §2）：cursor 可空列缺失时 width/height/sizeBytes/duration/dateTaken 取 0/null，
// 一律禁用作主判据（与 PRD §3.2 一致）；字符串列空串兜底。

/** MediaStore _ID；亦作分页游标（MediaKey）。 */
typealias MediaKey = Long

/** 板块（PRD §3.1 四板块）。 */
enum class Board { PHOTO, SCREENSHOT, SAVED, VIDEO }

/** 图片三分类（PRD §3.2；v1 分类仅图片）。 */
enum class ImageCategory { SCREENSHOT, CAMERA, SAVED }

/** 排序（PRD §4.4）：Random 是独立 shuffle 路径，不是 SQL 排序子句。
 *  注：架构 §6 草图写作 SqlOrder(clause: String)，落为枚举 SortClause —— 同一白名单的类型安全编码。 */
sealed interface SortSpec {
    data class SqlOrder(val clause: SortClause) : SortSpec
    data object Random : SortSpec
}

enum class SortClause { FILE_SIZE_DESC, DATE_TAKEN_DESC, DATE_TAKEN_ASC }

/** 权限可见性三分支（PRD §5.4）。 */
enum class VisibleScope { FULL, PARTIAL, DENIED }

/** 状态过滤（预留位：扩展 §2.4）。 */
enum class ProcessedFilter { ALL, PROCESSED, UNPROCESSED }

/** 查询条件（架构 §2）。matchTrashed 传 MediaStore.MATCH_EXCLUDE / MATCH_INCLUDE 常量值。
 *  excludePaths / processedFilter 为预留参数位，v1 不产生行为。 */
data class MediaQuery(
    val board: Board,
    val sort: SortSpec,
    val matchTrashed: Int,
    val visibility: VisibleScope,
    /** true = 统一图片板块：全部图片归「照片」板，分类降级为筛选器（PRD §3.1 设置项，缺省 true）。 */
    val mergeImages: Boolean = false,
    val excludePaths: List<String> = emptyList(),
    val processedFilter: ProcessedFilter = ProcessedFilter.ALL,
)

/** 分页结果。nextKey 语义见 MediaSource 注释；null = 已穷尽。 */
data class Page<T>(val items: List<T>, val nextKey: MediaKey?)

// —— 手势映射（PRD §4.1 / 架构 §6 #4）——

enum class Gesture { LEFT, RIGHT, UP, DOWN }

enum class BoardAction { MARK_DELETE, SKIP, SHOW_INFO }

/** 构造校验：Gesture 与 Action 双向单射；Gesture.DOWN 允许无绑定（默认即无绑定）。
 *  合法形态 = {LEFT, RIGHT, UP} ↔ 三动作的任意置换；冲突在保存前构造时即被拒绝。 */
data class GestureMapping(val map: Map<Gesture, BoardAction>) {
    init {
        require(map.keys == setOf(Gesture.LEFT, Gesture.RIGHT, Gesture.UP)) {
            "手势绑定必须恰好覆盖 左/右/上；下滑不绑定（PRD §4.1）"
        }
        require(map.values.toSet() == setOf(BoardAction.MARK_DELETE, BoardAction.SKIP, BoardAction.SHOW_INFO)) {
            "三个动作必须与手势一一对应，不允许两个手势映射同一动作（PRD §4.1）"
        }
    }

    /**
     * 两手势互换绑定（设置页点按 = 与当前持有目标动作的另一手势交换）。
     * 纯函数：交换仍是双射 → 构造校验不变（冲突在构造处拒绝）。
     */
    fun swapped(a: Gesture, b: Gesture): GestureMapping {
        if (a == b) return GestureMapping(map)
        val actionA = map[a]
        val actionB = map[b]
        require(actionA != null && actionB != null) { "只能交换已绑定的手势：$a / $b（PRD §4.1）" }
        return GestureMapping(map + (a to actionB) + (b to actionA))
    }

    companion object {
        /** 默认手势（PRD §4.1）：左=标记删除、右=跳过、上=查看信息、下=不绑定。 */
        fun default(): GestureMapping = GestureMapping(
            mapOf(
                Gesture.LEFT to BoardAction.MARK_DELETE,
                Gesture.RIGHT to BoardAction.SKIP,
                Gesture.UP to BoardAction.SHOW_INFO,
            )
        )
    }
}
