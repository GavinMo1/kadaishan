package dev.sxc.kadaishan.ui.boards

import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sxc.kadaishan.data.SettingsRepository
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.domain.session.BoardSession
import dev.sxc.kadaishan.domain.sort.fisherYates
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.BoardAction
import dev.sxc.kadaishan.model.MediaItem
import dev.sxc.kadaishan.model.MediaKey
import dev.sxc.kadaishan.model.MediaQuery
import dev.sxc.kadaishan.model.SortSpec
import dev.sxc.kadaishan.model.VisibleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

// ═══ 图片板块状态持有者（PRD §4.1 大图卡片流）═══
//
// 导航模型（比视频板简单：无回看栈，滑动即动作、无确认层）：
//  · currentId = session.current（单卡直读，动作即消耗）；
//  · 左/右/上滑 → GestureMapping 动作（Screen 侧解析后调 onAction）：
//    MARK_DELETE → session.mark(id) 消耗前进；SKIP → session.skip(id) 消耗前进（会话级出池）；
//    SHOW_INFO → infoOpen 浮层（不消耗，关闭后回到当前卡）；下滑（DOWN）不绑定 → 无动作；
//  · 撤回：session.undo()（false = 栈空置灰），连续撤回拨回本轮开头（undoDepth = 置灰判据镜像）；
//  · 池尽判据：session.current == null && !session.roundEnded()；
//  · 加载失败/被外部删除 → advance() 自动跳过不崩溃；换排序 → 新 BoardSession 重建。
// main 单写者（架构 §2 并发约定）；本类不持久化（进程存活期；标记只存内存）。

class PhotoCardViewModel(
    private val board: Board,
    private val source: MediaSource,
    private val settings: SettingsRepository,
) : ViewModel() {

    // —— 对外可观察状态 ——
    /** 会话已构建（false = 首次/重建取数中 → UI 显示加载）。 */
    var ready by mutableStateOf(false)
        private set

    /** 当前展示项（= session.current 快照；null = 轮末态或池尽空态，由 roundEnded 区分）。 */
    var currentId by mutableStateOf<MediaKey?>(null)
        private set

    /** id → 详情（加载完成后才可渲染大图/信息卡）；数据按 id 稳定，跨重建保留。 */
    var items by mutableStateOf<Map<MediaKey, MediaItem>>(emptyMap())
        private set

    /** 本轮标记数（= session.markedCount 快照）。 */
    var markedCount by mutableStateOf(0)
        private set

    /** 撤回按钮可用（false 置灰）；undoDepth = 会话撤回栈深度镜像。 */
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

    private var session: BoardSession? = null
    private var sort: SortSpec = SortSpec.Random
    private var undoDepth = 0

    init {
        viewModelScope.launch {
            // 先取落盘配置再建会话（每轮数量 / 排序）
            sort = settings.sortStrategy.first()
            currentRoundSize = settings.perBoardRoundSize.first()[board]
                ?: SettingsRepository.defaultRoundSize(board)
            rebuild()
            // 换排序 → 新 BoardSession 重建
            settings.sortStrategy.collect { s ->
                if (s != sort) {
                    sort = s
                    rebuild()
                }
            }
        }
        // 详情加载：失效/被外部删除 → 自动跳过不崩溃
        viewModelScope.launch {
            snapshotFlow { currentId }.collect { id -> loadItem(id) }
        }
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
        if (!ready) return // 重建取数中：旧会话不可再动作
        val id = s.current ?: return
        s.mark(id)
        if (id !in s.markedIds) { // 会话按设计拒绝（轮末/池尽）→ 纯无操作
            sync()
            return
        }
        undoDepth++
        refresh()
    }

    /** 跳过（保留）：消耗当前项 = 会话级出池，前进到下一张。 */
    private fun skipCurrent() {
        val s = session ?: return
        if (!ready) return
        val id = s.current ?: return
        s.skip(id)
        undoDepth++
        refresh()
    }

    // —— 撤回（右上角；连续撤回到本轮开头）——

    fun undo() {
        val s = session ?: return
        if (!ready) return // 重建取数中：旧会话不可再动作（按钮此时已置灰，双保险）
        if (!s.undo()) {
            sync() // 栈空（镜像兜底，保持两栈一致）
            return
        }
        if (undoDepth > 0) undoDepth--
        refresh() // 指针已拨回被撤项 → currentId 直接显示该卡
    }

    // —— 轮次（PRD §4.4）——

    /** 轮末态「开始下一轮」：startNewRound + 重建会话（新排序快照 / Random 重洗）。 */
    fun startNextRound() {
        val s = session ?: return
        if (!s.roundEnded()) return
        s.startNewRound() // 合同字面：旧会话先开新轮（标记与撤回栈复位）
        viewModelScope.launch { rebuild() }
    }

    /** 轮末结算快照入口；复制后交给结算页，不能把会话的活集合泄露出去。 */
    fun markedSnapshot(): List<MediaKey> = session?.markedIds?.toList().orEmpty()

    /** 构造/重建会话（初始进入 / 换排序 / 开始下一轮共用）：
     *  idOrder = source.ids(query) 按 sortStrategy 定序（Random → fisherYates），
     *  currentId/undoDepth 复位；空池 → 空态（currentId=null, roundEnded=false）。 */
    private suspend fun rebuild() {
        ready = false
        currentId = null
        undoDepth = 0
        var order = source.ids(photoQuery())
        if (sort == SortSpec.Random) order = fisherYates(order, Random.Default)
        session = BoardSession(order, currentRoundSize)
        refresh()
        ready = true
    }

    private fun photoQuery(): MediaQuery = MediaQuery(
        board = board,
        sort = sort,
        matchTrashed = MediaStore.MATCH_EXCLUDE,
        // visibility 为数据层预留位（MediaStoreDataSource 不消费）；板块内容按权限范围由查询结果天然决定
        visibility = VisibleScope.FULL,
    )

    // —— 详情加载（失败/被外部删除 → 自动跳过不崩溃）——

    private suspend fun loadItem(id: MediaKey?) {
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
            if (currentId == id) items = items + (id to loaded)
            return
        }
        if (currentId == id) skipBroken() // 展示期间失效 → 自动跳过不崩溃
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
        roundEnded = s?.roundEnded() ?: false
        undoAvailable = undoDepth > 0
    }
}
