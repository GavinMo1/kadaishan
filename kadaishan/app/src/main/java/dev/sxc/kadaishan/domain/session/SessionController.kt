package dev.sxc.kadaishan.domain.session

import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.domain.sort.fisherYates
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.MediaKey
import dev.sxc.kadaishan.model.MediaQuery
import dev.sxc.kadaishan.model.SortSpec
import dev.sxc.kadaishan.model.VisibleScope
import kotlin.random.Random
import dev.sxc.kadaishan.domain.selection.smartOrder
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// ═══ 会话生命周期控制器（图片/视频两个卡片 VM 的共享层；架构 §2 BoardSession 的持有与轮界决策）═══
//
// 单源职责（两个 VM 历史上各写一份、已发生漂移——本类即收编点）：
//  · 轮初设置同步与变更判定（排序/板块模式变更 = 必须重建；每轮数量走热调通道，不参与）；
//  · 会话构造：ids 全量快照 + Random 时 fisherYates（注入 Random 保可测）；
//  · 「开始下一轮」轮界决策：设置已变更 / 无会话 / 池已耗尽 → 重建；否则同会话 startNewRound。
// 纯 JVM：不依赖 android.*（matchTrashed 由构造注入，MediaStore 常量在 UI/数据层边界取值）。

/** 轮界决策结果（「开始下一轮」调用方按此分支）。 */
enum class RoundAdvance {
    /** 同会话 startNewRound：指针不回拨，跳过项/已展示项结构性不重入，随机序不重洗。 */
    START_NEW_ROUND,

    /** 重建会话：设置已变更 / 无会话 / 池已耗尽（重扫池子）。 */
    REBUILD,
}

class SessionController(
    private val source: MediaSource,
    private val board: Board,
    /** 板块查询的回收站过滤口径（UI 侧传 MediaStore.MATCH_EXCLUDE；注入以保纯 JVM）。 */
    private val matchTrashed: Int,
    /** 随机源注入（架构 §8 可测性口径；生产传 Random.Default）。 */
    private val random: Random = Random.Default,
    private val history: ReviewHistory? = null,
    private val diagnostic: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    var session: BoardSession? = null
        private set

    var sort: SortSpec = SortSpec.Random
        private set

    private var mergedImages = true

    /** 轮初设置同步：返回 true = 排序或板块模式已变更 → 必须重建（BoardSession 排序快照不可变）。 */
    fun applySettings(newSort: SortSpec, newMerged: Boolean): Boolean {
        val changed = newSort != sort || newMerged != mergedImages
        sort = newSort
        mergedImages = newMerged
        return changed
    }

    /** 构造新会话（初始进入 / 设置变更 / 池尽重扫共用）：ids 快照 + Random 时 fisherYates。 */
    suspend fun rebuild(roundSize: Int, latestRoundSize: () -> Int = { roundSize }): BoardSession {
        val now = clock()
        val timeZone = zone()
        val kept = history?.keptIds().orEmpty()
        val order = if (sort == SortSpec.Smart && board != Board.VIDEO) {
            val rows = source.selectionRows(query())
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                smartOrder(rows.filterNot { context.ensureActive(); it.id in kept }, now, timeZone, random) { context.ensureActive() }
            }
        } else {
            val effectiveQuery = query().let { if (it.sort == SortSpec.Smart) it.copy(sort = SortSpec.Random) else it }
            val candidates = source.ids(effectiveQuery).filterNot { it in kept }
            if (effectiveQuery.sort == SortSpec.Random) fisherYates(candidates, random) else candidates
        }
        currentCoroutineContext().ensureActive()
        diagnostic("rebuild board=$board kept=${kept.size} candidates=${order.size} first=${order.take(5)}")
        val s = BoardSession(order, latestRoundSize())
        session = s
        return s
    }

    /** Reset only accessible live media of the current kind; image categories reset together. */
    suspend fun resetKeptForMediaKind() {
        val target = if (board == Board.VIDEO) Board.VIDEO else Board.PHOTO
        val ids = source.ids(query().copy(board = target, mergeImages = target == Board.PHOTO))
        history?.setKept(ids.toSet(), false)
    }

    /** 「开始下一轮」决策：设置已变更 / 无会话 / 本轮未正常收尾（池尽中断）→ 重建；否则同会话开新轮。
     *  START_NEW_ROUND 后若 session.current == null（池子恰好耗尽），由调用方决定重扫或呈空态。 */
    fun decideNextRound(settingsChanged: Boolean): RoundAdvance {
        val s = session ?: return RoundAdvance.REBUILD
        return if (settingsChanged || !s.roundEnded()) RoundAdvance.REBUILD else RoundAdvance.START_NEW_ROUND
    }

    private fun query(): MediaQuery = MediaQuery(
        board = board,
        sort = sort,
        matchTrashed = matchTrashed,
        // visibility 为数据层预留位（MediaStoreDataSource 不消费）；板块内容按权限范围由查询结果天然决定
        visibility = VisibleScope.FULL,
        // 统一图片板块（PRD §3.1 设置项）：仅「照片」板吸收全部图片；分板模式维持分类过滤
        mergeImages = mergedImages && board == Board.PHOTO,
    )
}
