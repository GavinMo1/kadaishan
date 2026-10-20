package dev.sxc.kadaishan.ui.boards

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import dev.sxc.kadaishan.data.SettingsRepository
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.domain.session.RoundAdvance
import dev.sxc.kadaishan.domain.session.SessionController
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.MediaItem
import dev.sxc.kadaishan.model.MediaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ═══ 视频板状态持有者（PRD §4.2 抖音式删视频；架构 §2 状态归属表：视频 Snackbar/计数 = 本类）═══
//
// 导航模型（视频板自维护回看，与 BoardSession 兼容）：
//  · visited + viewIndex = 当前显示 visited[viewIndex]；
//  · 上滑 / 右滑（保留）：viewIndex < lastIndex → ++；否则取 session.current（null：roundEnded→轮末态 / 否则→空态），
//    advance() 消耗、visited 追加、viewIndex 移到末尾；
//  · 下滑：viewIndex--（到头无效）；回看不产生新条目、不碰 session；
//  · 池尽判据：session.current == null && !session.roundEnded()。
// 左滑 = 标记删除并随滑动前进（已标记卡再左滑 = 取消标记，toggle）；标记成功发 Snackbar「已标记第 N 个 · 撤回」
//  （撤回 = unmark 该 id；深撤回走「下滑回看 + 再左滑取消」，无按钮）。恒静音（无声开关）。
//  可见卡必在已展示窗口内（fetchNext 时已消耗）→ session.mark 走回看分支：纯状态标记，指针/轮计数不动。
// main 单写者（架构 §2 并发约定）；本类不持久化（进程存活期）。

class VideoCardViewModel(
    private val source: MediaSource,
    private val settings: SettingsRepository,
    context: Context, // 仅取 applicationContext（防 Activity 泄漏）
    private val history: dev.sxc.kadaishan.domain.session.ReviewHistory,
) : ViewModel() {

    /** 自动跳后 Snackbar 事件（seq 保证同值可重复触发；撤回 = unmark(id)）。 */
    data class SnackEvent(val seq: Int, val count: Int, val id: MediaKey)

    // —— 对外可观察状态 ——
    /** 会话已构建（false = 首次/重建取数中 → UI 显示加载）。 */
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

    var ready by mutableStateOf(false)
        private set

    /** 已取到的 id 序（每项 = 一次会话消耗）；下标即展示位。 */
    var visitedIds by mutableStateOf<List<MediaKey>>(emptyList())
        private set

    /** 当前显示 visited[viewIndex]；可越界到 visited.size = 轮末/空态（past-end）。 */
    var viewIndex by mutableStateOf(0)
        private set

    /** id → 详情（加载完成后才可渲染海报/信息卡）。数据按 id 稳定，跨重建保留。
     *  mutableStateMapOf：写入 O(1)（历史实现每次插入全量复制 Map）。 */
    val items = mutableStateMapOf<MediaKey, MediaItem>()

    /** = session.markedIds 快照（每次会话动作后同步）。 */
    var markedIds by mutableStateOf<Set<MediaKey>>(emptySet())
        private set

    /** = session.roundEnded() 快照（past-end 时区分轮末态/空态）。 */
    var roundEnded by mutableStateOf(false)
        private set

    /** 播放缓冲中（STATE_BUFFERING；海报静止与暂停的可区分态）。 */
    var buffering by mutableStateOf(false)
        private set

    /** 本轮条数（会话 roundSize 快照；轮末态「本轮已处理 N 项」）。 */
    var currentRoundSize by mutableStateOf(SettingsRepository.defaultRoundSize(Board.VIDEO))
        private set

    var infoOpen by mutableStateOf(false)

    var snack by mutableStateOf<SnackEvent?>(null)
        private set

    /** 播放中（false = 暂停/未起播 → UI 中央播放指示；缓冲态由 buffering 单列）。 */
    var paused by mutableStateOf(true)
        private set

    /** 播放进度（500ms 轮询 → 底部进度条；scrub 期间 UI 用本地 fraction 展示，不回写）。 */
    var positionMs by mutableStateOf(0L)
        private set

    var durationMs by mutableStateOf(0L)
        private set

    /** 当前片源视频尺寸（渲染层算裁切变换；0 = 未知/未就绪）。 */
    var videoWidth by mutableStateOf(0)
        private set

    var videoHeight by mutableStateOf(0)
        private set

    val markedCount: Int
        get() = markedIds.size

    val currentId: MediaKey?
        get() = visitedIds.getOrNull(viewIndex)

    /** 海报解码预热口（Screen 侧）：未来 count 项中已加载行的 uri（与图片板同口径）。 */
    fun upcomingUris(count: Int) =
        session?.peekUpcoming(count + 1)?.mapNotNull { items[it]?.uri }.orEmpty()

    private val controller = SessionController(source, Board.VIDEO, MediaStore.MATCH_EXCLUDE, history = history, diagnostic = dev.sxc.kadaishan.data.ReviewDiagnostics::record)
    private val session get() = controller.session

    // —— 播放（PRD §4.2；单 ExoPlayer 实例，onCleared 释放）——
    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null
    private var preparedUri: Uri? = null
    private var surface: TextureView? = null

    /** 屏幕是否在前台（离屏不停播会白耗电；排序变化等离屏导航只装片不播）。 */
    private var visible = false

    /** 会话撤回栈已退役：撤回走 Snackbar（unmark 该 id）或「下滑回看 + 再左滑取消」。 */
    private var snackSeq = 0

    init {
        viewModelScope.launch {
            // 先取落盘配置再建会话（每轮数量 / 排序）。
            // 排序变更 = 下一轮生效（startNextRound 轮界重读对比，轮内不 rebuild、不清标记）。
            controller.applySettings(settings.sortStrategy.first(), newMerged = true) // VIDEO 板无合并概念，仅占位
            applyRoundSize(settings.perBoardRoundSize.first()[Board.VIDEO] ?: SettingsRepository.defaultRoundSize(Board.VIDEO))
            rebuild()
        }
        // 每轮数量热生效（PRD §4.4）：live collect → 会话 roundSize 即时同步——当前轮立即按新值收尾
        viewModelScope.launch {
            settings.perBoardRoundSize.collect { map ->
                applyRoundSize(map[Board.VIDEO] ?: SettingsRepository.defaultRoundSize(Board.VIDEO))
            }
        }
        // 详情加载 + 装片播放：失效/坏文件/被外部删除 → 自动前进不崩溃；行预取未来 5 项合并一次查询
        viewModelScope.launch {
            snapshotFlow { currentId }.collect { id ->
                prepareCard(id)
                prefetchUpcoming()
            }
        }
    }

    /** 每轮数量唯一同步口：VM 快照与会话 roundSize 一起改（幂等；init 前 session 为 null 只改快照）。
     *  currentId 由回看栈 viewIndex 派生、不随 session 自翻 —— 热调后必须手工对齐导航位：
     *  · 缩到已消耗值以内（轮末）：停在最新卡时推 past-end，结算触发条件（roundEnded && currentId==null）
     *    才成立；回看中（viewIndex < lastIndex）不打断，前进到 frontier 由 fetchNext 自然落 past-end；
     *  · 轮末再放大（复活）：正处 past-end 时拉回 frontier 取新卡（fetchNext 内含 sync，直接返回）。 */
    private fun applyRoundSize(size: Int) {
        currentRoundSize = size
        val s = session
        if (s != null) {
            s.updateRoundSize(size)
            when {
                s.roundEnded() && viewIndex >= visitedIds.lastIndex ->
                    viewIndex = visitedIds.size
                !s.roundEnded() && viewIndex == visitedIds.size && visitedIds.isNotEmpty() -> {
                    fetchNext()
                    return
                }
            }
        }
        sync()
    }

    // —— 播放（PRD §4.2：滑入自动播放、默认静音、点按暂停/继续；单 ExoPlayer）——

    /** 屏幕进入：恢复当前卡播放（离屏时只装片不播）。 */
    fun onScreenVisible() {
        visible = true
        currentId?.let { items[it] }?.let { play(it) }
    }

    /** 屏幕离开：停播（实例保留，回屏续播）。 */
    fun onScreenPaused() {
        visible = false
        player?.playWhenReady = false
    }

    /** 渲染层挂载（UI 的 TextureView；同实例幂等）。 */
    fun attachSurface(view: TextureView?) {
        if (surface === view) return
        surface = view
        player?.setVideoTextureView(view)
    }

    /** 播放区点按 = 暂停/继续。 */
    fun togglePlayPause() {
        if (!visible || currentId == null) return
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
    }

    /** 进度条拖动收口：fraction ∈ [0,1)，上探 duration-1ms 防误触播完自动跳卡。 */
    fun seekTo(fraction: Float) {
        val p = player ?: return
        val d = p.duration
        if (d <= 0) return
        p.seekTo((fraction.coerceIn(0f, 0.999f) * d).toLong())
    }

    private fun ensurePlayer(): ExoPlayer? {
        player?.let { return it }
        val p = ExoPlayer.Builder(appContext).build()
        p.volume = 0f // 恒静音（PRD §4.2：无声开关，清理场景声音为干扰项）
        // PRD §4.2 口径：播完自动跳下一条（不循环）；回看走「下滑 = 上一个」。
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED && visible && currentId != null) {
                    next() // 播完推进（与上滑同语义；到轮末/池尽即止）
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                preparedUri = null // 失效源强制下次重装；坏文件 → 既有 load-failure 自动前进
                if (visible && currentId != null && player?.playbackState == Player.STATE_IDLE) {
                    if (!durableAction.busy) nextStep()
                }
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoWidth = size.width
                videoHeight = size.height
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                paused = !isPlaying
            }
        })
        surface?.let { p.setVideoTextureView(it) }
        player = p
        // 进度轮询（单实例一次；500ms 粒度够 UI 细线用，scrub 期间 UI 本地插值不闪跳）
        viewModelScope.launch {
            while (true) {
                val cur = player
                if (cur != null && visible) {
                    positionMs = cur.currentPosition.coerceAtLeast(0L)
                    val d = cur.duration
                    if (d > 0) durationMs = d
                }
                delay(500)
            }
        }
        return p
    }

    /** 进卡装片：同源只续播；换源 setMediaItem + prepare + play。 */
    private fun play(item: MediaItem) {
        val p = ensurePlayer() ?: return
        if (preparedUri != item.uri) {
            p.setMediaItem(ExoMediaItem.fromUri(item.uri))
            p.prepare()
            preparedUri = item.uri
            videoWidth = 0
            videoHeight = 0
            positionMs = 0
            durationMs = 0
        } else if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0) // 播完后回看：从头重播
        }
        p.playWhenReady = true
    }

    private fun stopPlayback() {
        player?.stop()
        preparedUri = null
    }

    override fun onCleared() {
        super.onCleared()
        player?.release()
        player = null
    }

    // —— 导航 ——

    /** 上滑 / 右滑（保留）= 下一个。 */
    fun next() {
        if (!ready || durableAction.busy) return
        val id = currentId ?: return
        persistThen({ if (id !in markedIds) history.setKept(setOf(id), true) }) { nextStep() }
    }

    /** 下滑 = 上一个（到头无效；回看不碰 session）。 */
    fun prev() {
        if (!durableAction.busy && viewIndex > 0) viewIndex--
    }

    /** 前进主体（上滑 / 右滑 / 左滑标记共用）。 */
    private fun nextStep() {
        if (visitedIds.isNotEmpty() && viewIndex < visitedIds.lastIndex) {
            viewIndex++
            return
        }
        fetchNext()
    }

    /** 取 session.current 进 visited（轮末/池尽 → past-end，由 roundEnded 区分两态）。 */
    private fun fetchNext() {
        val s = session ?: return
        val cur = s.current
        if (cur == null) {
            viewIndex = visitedIds.size
            sync()
            return
        }
        s.advance()
        visitedIds = visitedIds + cur
        viewIndex = visitedIds.lastIndex
        sync()
    }

    // —— 左滑标记（toggle + 随滑动前进）——

    /** 左滑 = 标记删除并前进（与滑动位移一致）；已标记卡再左滑 = 取消标记（不发 Snackbar）。
     *  可见卡必在已展示窗口内（fetchNext 时已消耗）→ session.mark 走回看分支：纯状态标记，指针/轮计数不动。 */
    fun markCurrentAndAdvance() {
        val s = session ?: return
        if (!ready || durableAction.busy) return // 重建取数中：旧会话不可再动作
        val id = currentId ?: return
        val keeping = id in s.markedIds
        persistThen({ history.setKept(setOf(id), keeping) }) {
            if (keeping) {
                s.unmark(id)
            } else {
                s.mark(id)
                if (id in s.markedIds) {
                    sync()
                    snack = SnackEvent(++snackSeq, markedCount, id)
                }
            }
            nextStep()
            sync()
        }
    }

    fun ackSnack(seq: Int) {
        if (snack?.seq == seq) snack = null
    }

    /** Snackbar「撤回」动作（PRD §4.2：撤回 = unmark 该 id，停留当前卡）。 */
    fun undoSnack(id: MediaKey) {
        val s = session ?: return
        if (id !in s.markedIds) return
        persistThen({ history.setKept(setOf(id), true) }) {
            s.unmark(id)
            sync()
        }
    }

    // —— 轮次（PRD §4.4）——

    /** 轮末态「开始下一轮」：轮界决策（同会话开新轮 / 重建）= SessionController.decideNextRound 单一来源
     *  （须在 startNewRound 之前判定）；排序变更在此轮界生效（每轮数量已热生效，不参与重建判定）。 */
    fun startNextRound() {
        if (!ready || durableAction.busy) return
        ready = false
        val s = session
        viewModelScope.launch {
            val changed = controller.applySettings(settings.sortStrategy.first(), newMerged = true)
            if (s == null || controller.decideNextRound(changed) == RoundAdvance.REBUILD) {
                rebuild() // 设置已变更 / 池尽重扫
                return@launch
            }
            s.startNewRound() // 指针不回拨：新轮消费下一段，旧轮项（含跳过）不重入
            visitedIds = emptyList()
            viewIndex = 0
            val first = s.current
            if (first != null) {
                s.advance()
                visitedIds = listOf(first)
            }
            sync()
            ready = true
        }
    }

    /** 轮末结算快照入口；复制以隔离 BoardSession 的活集合。 */
    fun markedSnapshot(): List<MediaKey> = session?.markedIds?.toList().orEmpty()

    fun reviewKeptAgain() {
        if (!ready || durableAction.busy) return
        ready = false
        viewModelScope.launch {
            try {
                history.clear()
                rebuild()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ready = true
                actionError = "保留记录未能重置，请重试"
            }
        }
    }

    /** 构造/重建会话（初始进入 / 换排序 / 开始下一轮共用）：
     *  构建细节（ids 快照 + 随机洗牌 + 查询构造）= SessionController 单一来源；
     *  visited/viewIndex/标记/镜像重置，并消费首条进入 visited（首屏即有卡；空池 → 空态）。 */
    private suspend fun rebuild() {
        ready = false
        visitedIds = emptyList()
        viewIndex = 0
        val s = controller.rebuild(currentRoundSize)
        val first = s.current
        if (first != null) {
            s.advance()
            visitedIds = listOf(first)
            viewIndex = 0
        }
        sync()
        ready = true
    }

    // —— 详情加载（失败/被外部删除 → 自动前进不崩溃）+ 播放装片 ——

    /** 行预取：会话指针后的未来 5 项（指针已越过当前展示卡，peekUpcoming 不含当前项）中
     *  未缓存的合并为一次 queryByIds，结果全部入缓存 —— 否则 upcomingUris 恒空、海报预热空转。 */
    private suspend fun prefetchUpcoming() {
        val missing = session?.peekUpcoming(5).orEmpty().filter { !items.containsKey(it) }
        if (missing.isEmpty()) return
        val loaded = try {
            source.queryByIds(missing, MediaStore.MATCH_EXCLUDE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // 预取失败不致命：滑到时走 prepareCard 正常路径
        }
        if (loaded.isNotEmpty()) items.putAll(loaded.associateBy { it.id })
    }

    private suspend fun prepareCard(id: MediaKey?) {
        if (id == null) {
            stopPlayback() // 轮末/空态：停播
            return
        }
        val cached = items[id]
        val resolved: MediaItem = if (cached != null) {
            cached
        } else {
            val loaded = try {
                source.queryByIds(listOf(id), MediaStore.MATCH_EXCLUDE).firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // 读取异常按加载失败处理（PRD §5.5 失效项口径）
            }
            if (loaded == null) {
                if (currentId == id && !durableAction.busy) nextStep() // 展示期间失效 → 自动前进
                return
            }
            if (currentId == id) items[id] = loaded
            loaded
        }
        if (visible && currentId == id) play(resolved)
    }

    // —— 会话 → UI 状态同步 ——

    private fun sync() {
        val s = session
        markedIds = s?.markedIds?.toSet() ?: emptySet()
        roundEnded = s?.roundEnded() ?: false
    }
}
