package dev.sxc.kadaishan.ui.boards

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.view.TextureView
import androidx.compose.runtime.getValue
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
import dev.sxc.kadaishan.domain.session.BoardSession
import dev.sxc.kadaishan.domain.sort.fisherYates
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.MediaItem
import dev.sxc.kadaishan.model.MediaKey
import dev.sxc.kadaishan.model.MediaQuery
import dev.sxc.kadaishan.model.SortSpec
import dev.sxc.kadaishan.model.VisibleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

// ═══ 视频板状态持有者（PRD §4.2 抖音式删视频；架构 §2 状态归属表：视频水印/Snackbar/计数 = 本类 dwell 计时器）═══
//
// 导航模型（视频板自维护回看，与 BoardSession 兼容）：
//  · visited + viewIndex = 当前显示 visited[viewIndex]；
//  · 上滑：viewIndex < lastIndex → ++；否则取 session.current（null：roundEnded→轮末态 / 否则→空态），
//    advance() 消耗、visited 追加、viewIndex 移到末尾；
//  · 下滑：viewIndex--（到头无效）；回看不产生新条目、不碰 session；
//  · 池尽判据：session.current == null && !session.roundEnded()。
// 删除按钮 = 完整 toggle（任何可见卡含回看）：id ∈ markedIds → unmark（无停留）；否则 mark →
//  停留 videoDwellMs 显示水印/计数，随后按 mark 分支自动跳下一个；dwell 计时在本类协程，取消安全。
// main 单写者（架构 §2 并发约定）；本类不持久化（进程存活期）。

class VideoCardViewModel(
    private val source: MediaSource,
    private val settings: SettingsRepository,
    context: Context, // 仅取 applicationContext（防 Activity 泄漏）
) : ViewModel() {

    /** 自动跳后 Snackbar 事件（seq 保证同值可重复触发；撤回 = unmark(id)）。 */
    data class SnackEvent(val seq: Int, val count: Int, val id: MediaKey)

    // —— 对外可观察状态 ——
    /** 会话已构建（false = 首次/重建取数中 → UI 显示加载）。 */
    var ready by mutableStateOf(false)
        private set

    /** 已取到的 id 序（每项 = 一次会话消耗）；下标即展示位。 */
    var visitedIds by mutableStateOf<List<MediaKey>>(emptyList())
        private set

    /** 当前显示 visited[viewIndex]；可越界到 visited.size = 轮末/空态（past-end）。 */
    var viewIndex by mutableStateOf(0)
        private set

    /** id → 详情（加载完成后才可渲染海报/信息卡）。数据按 id 稳定，跨重建保留。 */
    var items by mutableStateOf<Map<MediaKey, MediaItem>>(emptyMap())
        private set

    /** = session.markedIds 快照（每次会话动作后同步）。 */
    var markedIds by mutableStateOf<Set<MediaKey>>(emptySet())
        private set

    /** 撤回按钮可用（镜像会话撤回栈深度；false 置灰）。 */
    var undoAvailable by mutableStateOf(false)
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

    /** dwell 水印：标记停留中（dwellId = 被标记卡）。 */
    var dwellActive by mutableStateOf(false)
        private set

    var dwellId by mutableStateOf<MediaKey?>(null)
        private set

    var infoOpen by mutableStateOf(false)

    var snack by mutableStateOf<SnackEvent?>(null)
        private set

    /** 声音开关（PRD §4.2 默认静音；右上角小喇叭切换）。 */
    var muted by mutableStateOf(true)
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

    private var session: BoardSession? = null
    private var sort: SortSpec = SortSpec.Random
    private var dwellMs: Int = SettingsRepository.DEFAULT_DWELL_MS

    // —— 播放（PRD §4.2；单 ExoPlayer 实例，onCleared 释放）——
    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null
    private var preparedUri: Uri? = null
    private var surface: TextureView? = null

    /** 屏幕是否在前台（离屏不停播会白耗电；排序变化等离屏导航只装片不播）。 */
    private var visible = false

    /** 会话撤回栈镜像（id 序）：置灰判据 + 撤回后拨回被撤项的下标。 */
    private val markStack = ArrayDeque<MediaKey>()
    private var dwellJob: Job? = null
    private var snackSeq = 0

    init {
        viewModelScope.launch {
            // 先取落盘配置再建会话（每轮数量 / 排序）。
            // M11（拍板）：设置变更 = 下一轮生效——startNextRound 时重读对比，轮内不 rebuild、不清标记。
            sort = settings.sortStrategy.first()
            currentRoundSize = settings.perBoardRoundSize.first()[Board.VIDEO]
                ?: SettingsRepository.defaultRoundSize(Board.VIDEO)
            rebuild()
        }
        viewModelScope.launch {
            settings.videoDwellMs.collect { dwellMs = it }
        }
        // 详情加载 + 装片播放：失效/坏文件/被外部删除 → 自动前进不崩溃
        viewModelScope.launch {
            snapshotFlow { currentId }.collect { id -> prepareCard(id) }
        }
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

    /** 小喇叭：静音 ↔ 出声。 */
    fun toggleMute() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
    }

    /** 播放区点按 = 暂停/继续（删除按钮等 overlay 消费自身点击，不会落到这里）。 */
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
        p.volume = if (muted) 0f else 1f
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
                    next()
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

    /** 上滑 = 下一个。 */
    fun next() {
        cancelDwell()
        nextStep()
    }

    /** 下滑 = 上一个（到头无效；回看不碰 session）。 */
    fun prev() {
        cancelDwell()
        if (viewIndex > 0) viewIndex--
    }

    /** 上滑主体（不含取消 dwell —— dwell 自动跳转复用）。 */
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

    // —— 删除按钮 toggle（任何可见卡，含回看）——

    fun toggleMark() {
        val s = session ?: return
        if (!ready) return // 重建取数中：旧会话不可再动作
        val id = currentId ?: return
        cancelDwell()
        if (id in s.markedIds) {
            // 取消标记：立即生效、无停留
            s.unmark(id)
            markStack.remove(id)
            sync()
            return
        }
        val consumeJump = s.current == id // mark 是否走「当前项被消耗」分支
        s.mark(id)
        if (id !in s.markedIds) {
            // 会话按设计拒绝（轮末 / 非本窗口，BoardSession「轮末标记亦无操作」）→ 纯无操作
            sync()
            return
        }
        markStack.addLast(id)
        sync()
        scheduleDwell(id, consumeJump)
    }

    /** 标记停留：水印 + 计数，dwell 后按 mark 分支自动跳下一个，再发 Snackbar（≥1.5s）。 */
    private fun scheduleDwell(id: MediaKey, consumeJump: Boolean) {
        dwellJob?.cancel()
        dwellActive = true
        dwellId = id
        dwellJob = viewModelScope.launch {
            delay(dwellMs.toLong())
            dwellActive = false
            dwellId = null
            if (consumeJump) fetchNext() else nextStep()
            sync()
            snack = SnackEvent(++snackSeq, markedCount, id)
        }
    }

    /** 取消标记/切卡/离开共用：取消未完成的 dwell 计时。 */
    fun cancelDwell() {
        dwellJob?.cancel()
        dwellJob = null
        dwellActive = false
        dwellId = null
    }

    // —— 撤回（右上角；栈式，false 置灰）——

    fun undoMark() {
        cancelDwell()
        if (!ready) return
        val s = session ?: return
        val id = markStack.removeLastOrNull() ?: return
        if (!s.undo()) { // 镜像兜底：会话栈空时恢复镜像，两栈保持一致
            markStack.addLast(id)
            return
        }
        // 与会话指针回拨对齐：展示拨回被撤项（指针不降不升语义见 BoardSession.undo）
        val idx = visitedIds.indexOf(id)
        if (idx >= 0) viewIndex = idx
        sync()
    }

    fun ackSnack(seq: Int) {
        if (snack?.seq == seq) snack = null
    }

    /** Snackbar「撤回」动作（PRD §4.2：撤回 = unmark 该 id，指针不动）。 */
    fun undoSnack(id: MediaKey) {
        val s = session ?: return
        if (s.unmark(id)) {
            markStack.remove(id)
            sync()
        }
    }

    // —— 轮次（PRD §4.4）——

    /** 轮末态「开始下一轮」：同会话 startNewRound（PRD §4.4 跳过项会话级出池、随机序不重洗，不重建
     *  BoardSession）；设置变更在此轮界生效（每轮数量/排序 = 下一轮生效）。池尽未满轮 → 重扫池子开新轮。 */
    fun startNextRound() {
        val s = session
        viewModelScope.launch {
            // 设置热生效：轮界重读，变了才走重建口（init/设置变更/池尽三口之一）
            val newSort = settings.sortStrategy.first()
            val newSize = settings.perBoardRoundSize.first()[Board.VIDEO]
                ?: SettingsRepository.defaultRoundSize(Board.VIDEO)
            if (s == null || newSort != sort || newSize != currentRoundSize || !s.roundEnded()) {
                sort = newSort
                currentRoundSize = newSize
                rebuild()
                return@launch
            }
            cancelDwell()
            s.startNewRound() // 指针不回拨：新轮消费下一段，旧轮项（含跳过）不重入
            markStack.clear()
            visitedIds = emptyList()
            viewIndex = 0
            val first = s.current
            if (first != null) {
                s.advance()
                visitedIds = listOf(first)
            }
            sync()
        }
    }

    /** 轮末结算快照入口；复制以隔离 BoardSession 的活集合。 */
    fun markedSnapshot(): List<MediaKey> = session?.markedIds?.toList().orEmpty()

    /** 构造/重建会话（初始进入 / 换排序 / 开始下一轮共用）：
     *  idOrder = source.ids(query) 排序序 + SortSpec.Random 时 fisherYates；visited/viewIndex/标记/镜像重置，
     *  并消费首条进入 visited（首屏即有卡；空池 → 空态）。 */
    private suspend fun rebuild() {
        cancelDwell()
        ready = false
        markStack.clear()
        visitedIds = emptyList()
        viewIndex = 0
        var order = source.ids(videoQuery())
        if (sort == SortSpec.Random) order = fisherYates(order, Random.Default)
        val s = BoardSession(order, currentRoundSize)
        session = s
        markStack.clear() // 取数期间镜像不得残留（旧会话帧随会话一并作废）
        val first = s.current
        if (first != null) {
            s.advance()
            visitedIds = listOf(first)
            viewIndex = 0
        }
        sync()
        ready = true
    }

    private fun videoQuery(): MediaQuery = MediaQuery(
        board = Board.VIDEO,
        sort = sort,
        matchTrashed = MediaStore.MATCH_EXCLUDE,
        // visibility 为数据层预留位（MediaStoreDataSource 不消费）；板块内容按权限范围由查询结果天然决定
        visibility = VisibleScope.FULL,
    )

    // —— 详情加载（失败/被外部删除 → 自动前进不崩溃）+ 播放装片 ——

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
                if (currentId == id) next() // 展示期间失效 → 自动前进
                return
            }
            if (currentId == id) items = items + (id to loaded)
            loaded
        }
        if (visible && currentId == id) play(resolved)
    }

    // —— 会话 → UI 状态同步 ——

    private fun sync() {
        val s = session
        markedIds = s?.markedIds?.toSet() ?: emptySet()
        roundEnded = s?.roundEnded() ?: false
        undoAvailable = markStack.isNotEmpty()
    }
}
