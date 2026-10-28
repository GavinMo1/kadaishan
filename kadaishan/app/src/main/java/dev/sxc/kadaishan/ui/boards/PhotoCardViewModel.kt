package dev.sxc.kadaishan.ui.boards

import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sxc.kadaishan.data.SettingsRepository
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.domain.session.RoundAdvance
import dev.sxc.kadaishan.domain.session.SessionController
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.BoardAction
import dev.sxc.kadaishan.model.MediaItem
import dev.sxc.kadaishan.model.MediaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ═══ 图片板块状态持有者（PRD §4.1 大图卡片流）═══
//
// 导航模型（比视频板简单：无回看栈，滑动即动作、无确认层）：
//  · currentId = session.current（单卡直读，动作即消耗）；
//  · 左/右/上滑 → GestureMapping 动作（Screen 侧解析后调 onAction）：
//    MARK_DELETE → session.mark(id) 消耗前进；SKIP → session.skip(id) 消耗前进，并持久化保留记录；
//    SHOW_INFO → infoOpen 浮层（不消耗，关闭后回到当前卡）；下滑（DOWN）不绑定 → 无动作；
//  · 撤回：下滑手势 → session.undo()（栈空 = 无效，UI 拒绝触觉），连续撤回拨回本轮开头，无标记自动切轮时也可回退上一轮；
//  · 池尽判据：session.current == null && !session.roundEnded()；
//  · 加载失败/被外部删除 → advance() 自动跳过不崩溃；
//  · 会话生命周期（构建/轮界决策/设置变更判定）= SessionController 单一来源（与视频板共享）；
//    每轮数量走热生效通道（init 起 live collect → session.updateRoundSize，当前轮立即按新值收尾）。
// main 单写者（架构 §2 并发约定）；会话与标记只存内存；保留记录委托 ReviewHistory 持久化。

class PhotoCardViewModel(
    private val board: Board,
    private val source: MediaSource,
    private val settings: SettingsRepository,
    private val history: dev.sxc.kadaishan.domain.session.ReviewHistory,
    private val cultivationRounds: dev.sxc.kadaishan.data.CultivationRoundBridge? = null,
) : ViewModel() {
    private var cultivationRound = cultivationRounds?.begin()
    fun completeCultivationRound() {
        if (ready && currentId == null) cultivationRounds?.complete(cultivationRound, session?.hasRoundDecision() == true)
    }

    private val durableAction = dev.sxc.kadaishan.domain.session.DurableReviewAction()
    var actionError by mutableStateOf<String?>(null)
        private set

    fun dismissActionError() { actionError = null }

    private fun persistThen(commit: suspend () -> Unit, apply: () -> Unit) {
        if (!ready || !durableAction.begin()) return
        viewModelScope.launch {
            try {
                durableAction.complete(commit, apply)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = "保留记录未能保存，请重试"
            }
        }
    }

    // —— 对外可观察状态 ——
    /** 会话已构建（false = 首次/重建取数中 → UI 显示加载）。 */
    var ready by mutableStateOf(false)
        private set

    /** 当前展示项（= session.current 快照；null = 轮末态或池尽空态，由 roundEnded 区分）。 */
    var currentId by mutableStateOf<MediaKey?>(null)
        private set

    /** id → 详情（加载完成后才可渲染大图/信息卡）；数据按 id 稳定，跨重建保留。
     *  mutableStateMapOf：写入 O(1)（历史实现每次插入全量复制 Map）。 */
    val items = mutableStateMapOf<MediaKey, MediaItem>()

    /** 本轮标记数（= session.markedCount 快照）。 */
    var markedCount by mutableStateOf(0)
        private set

    /** 本轮已看过数（= session 已消耗数快照；进度文案用）。 */
    var seenCount by mutableStateOf(0)
        private set

    /** 撤回可用（下滑手势判据）；= session.undoAvailable 快照（单一来源，无镜像计数）。 */
    var undoAvailable by mutableStateOf(false)
        private set

    /** = session.roundEnded() 快照（null 时区分轮末态/空态）。 */
    var roundEnded by mutableStateOf(false)
        private set

    /** 本轮条数（会话 roundSize 快照；轮末态「本轮已处理 N 项」）。 */
    var currentRoundSize by mutableStateOf(SettingsRepository.defaultRoundSize(board))
        private set

    /** 信息卡浮层开关（SHOW_INFO 打开；关闭不消耗）。 */
    var infoOpen by mutableStateOf(false)

    private val controller = SessionController(source, board, MediaStore.MATCH_EXCLUDE, history = history, diagnostic = dev.sxc.kadaishan.data.ReviewDiagnostics::record)
    private val session get() = controller.session

    init {
        viewModelScope.launch {
            // 先取落盘配置再建会话（每轮数量 / 排序 / 板块模式）
            applyRoundSettings()
            rebuild()
        }
        // 每轮数量热生效（PRD §4.4）：live collect → 会话 roundSize 即时同步——当前轮立即按新值收尾
        // （已消耗 ≥ 新值 → roundEnded → M4 自动弹结算）；排序/板块模式仍下一轮生效（重建会丢标记，轮内不动）
        viewModelScope.launch {
            settings.perBoardRoundSize.collect { map ->
                applyRoundSize(map[board] ?: SettingsRepository.defaultRoundSize(board))
            }
        }
        // 详情加载：失效/被外部删除 → 自动跳过不崩溃；行预取未来 5 项合并为一次查询（切图零等待，解码预热在 Screen 侧）
        viewModelScope.launch {
            snapshotFlow { currentId }.collect { id ->
                loadItem(id)
                prefetchUpcoming(id)
            }
        }
    }

    /** 每轮数量唯一同步口：VM 快照与会话 roundSize 一起改（幂等；init 前 session 为 null 只改快照）。
     *  必须 refresh（含 currentId 重读）而非 sync：缩到已消耗值以内时 session.current 翻 null，
     *  currentId 不同步会留僵尸卡 —— 结算渲染门（currentId == null）永不通过、手势全被会话拒绝。 */
    private fun applyRoundSize(size: Int) {
        currentRoundSize = size
        session?.updateRoundSize(size)
        refresh()
    }

    /** 轮初重读落盘设置（排序/板块模式；M11：改排序/模式无需杀进程，下一轮生效）。
     *  返回 true = 排序或板块模式已变更 → 必须换新会话（BoardSession 排序快照不可变）。
     *  每轮数量不在判定内：热生效通道已即时同步，不触发重建。 */
    private suspend fun applyRoundSettings(): Boolean {
        val newSort = settings.sortStrategy.first()
        applyRoundSize(settings.perBoardRoundSize.first()[board] ?: SettingsRepository.defaultRoundSize(board))
        return controller.applySettings(newSort, settings.imageBoardsMerged.first())
    }

    // —— 手势动作（GestureMapping 解析结果；Screen 只报动作不判方向）——

    fun onAction(action: BoardAction) {
        when (action) {
            BoardAction.MARK_DELETE -> markCurrent()
            BoardAction.SKIP -> skipCurrent()
            BoardAction.SHOW_INFO -> if (currentId != null) infoOpen = true
        }
    }

    /** 标记删除：当前项入标记集并消耗前进（BoardSession.mark 对当前项已含推进）。 */
    private fun markCurrent() {
        val s = session ?: return
        if (!ready || durableAction.busy) return // 重建取数中：旧会话不可再动作
        val id = s.current ?: return
        s.mark(id)
        if (id !in s.markedIds) { // 会话按设计拒绝（轮末/池尽）→ 纯无操作
            sync()
            return
        }
        refresh()
    }

    /** 跳过（保留）：消耗当前项并持久化保留决定，前进到下一张。 */
    private fun skipCurrent() {
        val s = session ?: return
        if (!ready || durableAction.busy) return
        val id = s.current ?: return
        persistThen({ history.setKept(setOf(id), true) }) {
            s.skip(id)
            refresh()
        }
    }

    // —— 撤回（含最近一次无标记自动切轮）——

    fun undo() {
        val s = session ?: return
        if (!ready || durableAction.busy) return // 重建取数中：旧会话不可再动作（按钮此时已置灰，双保险）
        val revoked = s.keptIdsRevokedByUndo()
        persistThen({ history.setKept(revoked, false) }) {
            s.undo()
            refresh()
        }
    }

    // —— 轮次（PRD §4.4）——

    /** 轮末/池尽「开始下一轮」（结算页 onNextRound 与池尽态按钮共用）：
     *  轮界决策（同会话开新轮 / 重建）= SessionController.decideNextRound 单一来源（须在 startNewRound
     *  之前判定——开新轮后 roundEnded 恒 false）；START_NEW_ROUND 后池子恰好耗尽 → 重扫；
     *  每轮数量已由热生效通道同步，不参与判定。 */
    fun startNextRound(preserveUndo: Boolean = false) {
        val s = session ?: return
        if (!ready || durableAction.busy) return
        cultivationRounds?.complete(cultivationRound, s.hasRoundDecision())
        cultivationRound = cultivationRounds?.begin()
        ready = false // 过渡期按加载态渲染，盖住轮末/池尽残影
        viewModelScope.launch {
            when (controller.decideNextRound(applyRoundSettings())) {
                RoundAdvance.START_NEW_ROUND -> {
                    s.startNewRound(preserveUndo = preserveUndo && s.markedCount == 0)
                    if (s.current == null && !preserveUndo) {
                        rebuild() // 池子恰好走完 → 重扫开新轮
                    } else {
                        refresh()
                        ready = true
                    }
                }
                RoundAdvance.REBUILD -> rebuild() // 设置已变更 / 池尽重扫
            }
        }
    }

    /** 轮末结算快照入口；复制后交给结算页，不能把会话的活集合泄露出去。 */
    fun markedSnapshot(): List<MediaKey> = session?.markedIds?.toList().orEmpty()

    fun reviewKeptAgain() {
        if (!ready || durableAction.busy || currentId != null || markedCount > 0) return
        ready = false
        viewModelScope.launch {
            try {
                controller.resetKeptForMediaKind()
                rebuild()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ready = true
                actionError = "保留记录未能重置，请重试"
            }
        }
    }

    /** 解码预热口（Screen 侧）：未来 count 项中已加载行的 uri（行预取见 init collector）。 */
    fun upcomingUris(count: Int) =
        session?.peekUpcoming(count + 1)?.mapNotNull { items[it]?.uri }.orEmpty()

    /** 构造/重建会话（初始进入 / 每轮设置变更 / 池尽重扫 —— 无「每轮重建」路径）：
     *  构建细节（ids 快照 + 随机洗牌 + 查询构造）= SessionController 单一来源；
     *  currentId 复位；空池 → 空态（currentId=null, roundEnded=false）。 */
    private suspend fun rebuild() {
        ready = false
        currentId = null
        controller.rebuild(currentRoundSize)
        refresh()
        ready = true
    }

    // —— 详情加载（失败/被外部删除 → 自动跳过不崩溃）——

    private suspend fun loadItem(id: MediaKey?) {
        dev.sxc.kadaishan.data.ReviewDiagnostics.record("shown board=Photo id=$id")
        if (id == null) return
        if (items.containsKey(id)) return
        val loaded = try {
            source.queryByIds(listOf(id), MediaStore.MATCH_EXCLUDE).firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // 读取异常按加载失败处理（PRD §5.5 失效项口径）
        }
        if (loaded != null) {
            // 无条件入缓存：预取项也必须落 items，否则 upcomingUris 恒空、解码预热空转、
            // 滑到该项时还要重查（历史 bug：gate 在 currentId 上导致预取全部白跑）
            items[id] = loaded
            return
        }
        if (currentId == id) skipBroken() // 展示期间失效 → 自动跳过不崩溃
    }

    /** 行预取：未来 5 项中未缓存的合并为一次 queryByIds（一次双集合查询替代逐项 2×cursor），
     *  结果全部入缓存供 upcomingUris / 滑到即取。失效项不在结果中，留待滑到时走 loadItem 的 skipBroken。 */
    private suspend fun prefetchUpcoming(current: MediaKey?) {
        val missing = session?.peekUpcoming(6).orEmpty()
            .filter { it != current && !items.containsKey(it) }
        if (missing.isEmpty()) return
        val loaded = try {
            source.queryByIds(missing, MediaStore.MATCH_EXCLUDE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // 预取失败不致命：滑到时走 loadItem 正常路径
        }
        if (loaded.isNotEmpty()) items.putAll(loaded.associateBy { it.id })
    }

    /** 当前项取不到详情 → 无标记消耗地前进一格（不入撤回栈、不入标记集）。 */
    private fun skipBroken() {
        val s = session ?: return
        s.advance()
        refresh()
    }

    // —— 会话 → UI 状态同步 ——

    /** 动作/撤回后：currentId 拨到 session.current 并同步轮次/标记/置灰快照。 */
    private fun refresh() {
        currentId = session?.current
        sync()
    }

    private fun sync() {
        val s = session
        markedCount = s?.markedCount ?: 0
        seenCount = s?.consumed ?: 0
        roundEnded = s?.roundEnded() ?: false
        undoAvailable = s?.undoAvailable ?: false
    }
}
