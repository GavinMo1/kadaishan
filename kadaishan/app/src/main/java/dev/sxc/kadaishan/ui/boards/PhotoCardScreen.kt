package dev.sxc.kadaishan.ui.boards

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.sxc.kadaishan.data.ExifReader
import dev.sxc.kadaishan.data.MediaDeleter
import dev.sxc.kadaishan.data.PendingSettlementRepository
import dev.sxc.kadaishan.data.SettingsRepository
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.BoardAction
import dev.sxc.kadaishan.model.ExifSummary
import dev.sxc.kadaishan.model.Gesture
import dev.sxc.kadaishan.model.GestureMapping
import dev.sxc.kadaishan.model.MediaItem
import dev.sxc.kadaishan.ui.kit.Daylight
import dev.sxc.kadaishan.ui.kit.DragHintBadge
import dev.sxc.kadaishan.ui.kit.InfoSheet
import dev.sxc.kadaishan.ui.kit.PearlButton
import dev.sxc.kadaishan.ui.kit.PillButton
import dev.sxc.kadaishan.ui.kit.Spinner
import dev.sxc.kadaishan.ui.kit.daylightBackground
import dev.sxc.kadaishan.ui.kit.onMediaChip
import dev.sxc.kadaishan.ui.kit.productShadow
import dev.sxc.kadaishan.ui.kit.rememberHaptics
import dev.sxc.kadaishan.ui.kit.rememberReducedMotion
import dev.sxc.kadaishan.ui.settle.SettlementPanel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// ═══ 图片板块（PRD §4.1 大图卡片流）═══
// 一次一张全屏大图（AsyncImage，model = ImageRequest(item.uri)，parchment 占位 + 200ms crossfade），滑动即切换、无确认层；
// 左/右/上滑全部经 settings.gestureMapping（图片板专属；默认 左=标记删除、右=跳过、上=信息卡）；
// 下滑固定 = 撤回（不进映射表；栈式可连撤至本轮开头，无可撤时轻拒绝触觉）；
// 手势须从内侧起始区开始（禁边缘手势）；顶层 inset 补偿由 MainActivity/AppRoot 统一做；
// 有标记的轮末/池尽进入复核；无标记自动继续，池尽停在完成空态。

/** 图片板块契约签名（宿主 MainActivity 按位置调用：board, source, settings, exifReader）。 */
@Composable
fun PhotoCardScreen(
    board: Board,
    source: MediaSource,
    settings: SettingsRepository,
    exifReader: ExifReader,
    deleter: MediaDeleter,
    pendingSettlement: PendingSettlementRepository,
    history: dev.sxc.kadaishan.domain.session.ReviewHistory,
    gestureHints: GestureHintViewModel,
    hintActive: Boolean,
    onOpenTrash: () -> Unit = {},
    onToggleChrome: () -> Unit = {},
    onInteract: () -> Unit = {},
) {
    // 按板块分实例：PHOTO/SCREENSHOT/SAVED 共用本屏，ViewModelStoreOwner 为 Activity 级，
    // 不带 key 会让三板块串会话（board 查询错板）；key 分实例后切板回来会话状态保留。
    val vm: PhotoCardViewModel = viewModel(
        key = "photo-card:${board.name}",
        factory = viewModelFactory {
            initializer { PhotoCardViewModel(board, source, settings, history) }
        },
    )

    // 手势映射（图片板专属；热路径直接查表，映射变化由 pointerInput 键重建捕获）
    val mapping by settings.gestureMapping.collectAsState(initial = GestureMapping.default())
    // 设置「允许永久删除」快照（锁定 → 结算面板不出现永久删除开关）
    val permanentAllowed by settings.permanentDeleteUnlocked.collectAsState(initial = true)
    val context = LocalContext.current
    // 切图零闪烁：解码预热——未来 5 项预解码进 Coil 内存缓存（命中内存缓存即时渲染、crossfade 跳过）
    // key 含 items.size：预取入缓存（size 变化）时重跑一次，补上当前卡切入时尚未加载完成的未来项
    val imageLoader = remember(context) { ImageLoader.Builder(context).build() }
    LaunchedEffect(vm.currentId, vm.items.size) {
        vm.upcomingUris(5).forEach { uri ->
            imageLoader.enqueue(ImageRequest.Builder(context).data(uri).build())
        }
    }
    // M1：saveable —— 旋转/配置重建不丢面板状态（VM 存活，标记仍在会话里）
    var settlementOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.actionError) {
        vm.actionError?.let { message ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
            if (vm.actionError == message) vm.dismissActionError()
        }
    }

    val screenScope = rememberCoroutineScope()

    // —— iOS 质感行为层 ——
    val haptics = rememberHaptics()
    val reducedMotion = rememberReducedMotion()
    // 跟手位移：拖动直写目标值，弹簧追踪（onDrag 非挂起上下文调不了 snapTo；目标归零即自动回弹）
    var followXTarget by remember { mutableStateOf(0f) }
    var followYTarget by remember { mutableStateOf(0f) }
    val followX by animateFloatAsState(
        targetValue = followXTarget,
        animationSpec = if (reducedMotion) snap() else Daylight.settle(),
        label = "followX",
    )
    val followY by animateFloatAsState(
        targetValue = followYTarget,
        animationSpec = if (reducedMotion) snap() else Daylight.settle(),
        label = "followY",
    )
    // 出场方向：最近一次触发换卡的手势主轴符号（左 = -1，右 = +1；上滑不换卡不参与）
    var exitDir by remember { mutableStateOf(1) }
    // 换卡转场轴：false = 横向（左右滑正常前进）、true = 纵向（下拉撤回 → 被撤卡自底回入）
    var swapVertical by remember { mutableStateOf(false) }
    // 甩出速度接续：松手速度越快退场飞得越远（行程系数 1.0~1.8，转场 lambda 内读）
    var exitBoost by remember { mutableStateOf(1f) }
    // 照片缩放态：>1x 时屏蔽滑卡/撤回/信息手势（平移优先，iOS 照片口径）；换卡复位
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(vm.currentId) { zoomed = false }

    // 零删除轮不中断浏览；依赖 ready 防止重建过程被误判为池尽。
    LaunchedEffect(vm.ready, vm.roundEnded, vm.currentId) {
        if (vm.ready && vm.currentId == null) {
            if (vm.markedCount > 0) settlementOpen = true
            else if (vm.roundEnded) vm.startNextRound(preserveUndo = true)
        }
    }

    BackHandler(enabled = vm.infoOpen) { vm.infoOpen = false }

    val item: MediaItem? = vm.currentId?.let { vm.items[it] }
    val showGestureHint = boardGestureHintVisible(
        hints = gestureHints,
        board = board.name,
        eligible = hintActive && vm.ready && item != null && !vm.infoOpen && !settlementOpen,
        interaction = Triple(vm.currentId, vm.seenCount, vm.markedCount),
    )


    // 信息卡设备型号（按需 suspend 读取；信息卡打开且条目就绪时才发起）
    var exif by remember(vm.currentId) { mutableStateOf<ExifSummary?>(null) }
    LaunchedEffect(vm.infoOpen, item) {
        exif = if (vm.infoOpen && item != null) exifReader.read(item) else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .daylightBackground()
            .pointerInput(mapping, vm) {
                // 手势热路径零分配：阈值/边界仅在进入时算一次，拖动只累加标量；
                // VelocityTracker 单实例随 pointerInput 生命周期创建——松手取甩出速度做退场接续
                val tracker = VelocityTracker()
                val edgePx = 24.dp.toPx()
                val thresholdPx = 56.dp.toPx()
                var totalX = 0f
                var totalY = 0f
                var valid = false
                detectDragGestures(
                    onDragStart = { offset ->
                        totalX = 0f
                        totalY = 0f
                        tracker.resetTracking()
                        // 起点必须在内侧起始区（四边各留 24dp，禁边缘手势）；照片放大时让位平移
                        valid = !zoomed &&
                            offset.x >= edgePx && offset.x <= size.width - edgePx &&
                            offset.y >= edgePx && offset.y <= size.height - edgePx
                    },
                    onDrag = { change, dragAmount ->
                        if (valid) {
                            totalX += dragAmount.x
                            totalY += dragAmount.y
                            tracker.addPosition(change.uptimeMillis, change.position)
                            // 跟手：位移 16% + 轻微倾斜（弹簧追踪，直写目标无分配）
                            followXTarget = (totalX * 0.16f).coerceIn(-72f, 72f)
                            followYTarget = (totalY * 0.16f).coerceIn(-72f, 72f)
                        }
                    },
                    onDragEnd = {
                        val flickX = tracker.calculateVelocity().x
                        tracker.resetTracking()
                        if (valid && !vm.infoOpen) {
                            // 主轴判定 → Gesture（DOWN 专属 = 撤回，不进映射表）
                            val gesture: Gesture? = if (abs(totalX) >= abs(totalY)) when {
                                totalX <= -thresholdPx -> Gesture.LEFT
                                totalX >= thresholdPx -> Gesture.RIGHT
                                else -> null
                            } else when {
                                totalY <= -thresholdPx -> Gesture.UP
                                totalY >= thresholdPx -> Gesture.DOWN
                                else -> null
                            }
                            if (gesture == Gesture.DOWN) {
                                // 下拉撤回（栈式，可连撤至本轮开头）；无可撤 = 轻拒绝触觉
                                if (vm.undoAvailable) {
                                    haptics.tick()
                                    swapVertical = true // 被撤卡自底回入
                                    vm.undo()
                                    onInteract()
                                } else {
                                    haptics.reject()
                                }
                            } else {
                                val action = gesture?.let { mapping.map[it] }
                                if (action != null) {
                                    when (action) {
                                        BoardAction.MARK_DELETE -> haptics.confirm()
                                        BoardAction.SKIP -> haptics.tick()
                                        BoardAction.SHOW_INFO -> haptics.tap()
                                    }
                                    // 退场方向随手势：左滑退左、右滑退右（上滑开信息卡不换卡）；
                                    // 速度接续：甩得越快退场行程越长（fling 弹簧）
                                    if (gesture == Gesture.LEFT) exitDir = -1
                                    if (gesture == Gesture.RIGHT) exitDir = 1
                                    if (gesture == Gesture.LEFT || gesture == Gesture.RIGHT) {
                                        exitBoost = 1f + (abs(flickX) / 12000f).coerceIn(0f, 0.8f)
                                    }
                                    swapVertical = false
                                    vm.onAction(action)
                                    onInteract()   // 干活自动收起顶栏（查看器式收纳）
                                }
                            }
                        }
                        valid = false
                        // 回弹：目标归零，弹簧自动收回
                        followXTarget = 0f
                        followYTarget = 0f
                    },
                    onDragCancel = {
                        valid = false
                        totalX = 0f
                        totalY = 0f
                        followXTarget = 0f
                        followYTarget = 0f
                    },
                )
            }
            // 查看器式收纳：单击照片切换顶栏显隐（tap 与拖动手势互不消费）
            .pointerInput(onToggleChrome) {
                detectTapGestures(onTap = { onToggleChrome() })
            },
    ) {
        // ① 大图 / 终态
        when {
            // 加载语汇统一：Spinner + 弱化文案（与视频板 / 结算面板 LoadingContent 同构；瞬态缺行只显 Spinner）
            !vm.ready -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spinner()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "加载中…",
                    color = Daylight.inkSub,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            vm.currentId != null && item == null -> Spinner(
                modifier = Modifier.align(Alignment.Center),
            )

            item != null -> AnimatedContent(
                targetState = item,
                transitionSpec = {
                    if (reducedMotion) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else if (swapVertical) {
                        // 撤回：被撤卡自底回入、当前卡让位上移（下拉动作的镜像）
                        (slideInVertically(Daylight.spatial()) { h -> h / 3 } + fadeIn(Daylight.settle()))
                            .togetherWith(
                                slideOutVertically(Daylight.spatial()) { h -> -h / 4 } + fadeOut(Daylight.settle()),
                            )
                    } else {
                        // 授权动效（本 App 唯一作者化时刻）：旧卡沿手势方向退场（fling 弹簧 +
                        // 松手速度接续的行程系数）、新卡对侧缓入
                        (slideInHorizontally(Daylight.spatial()) { w -> exitDir * -w / 4 } + fadeIn(Daylight.settle()))
                            .togetherWith(
                                slideOutHorizontally(Daylight.fling()) { w -> (exitDir * w / 3f * exitBoost).toInt() } + fadeOut(Daylight.settle()),
                            )
                    }
                },
                label = "photo-card",
            ) { shown ->
                Box(modifier = Modifier.fillMaxSize()) {
                    MediaCard(
                        item = shown,
                        imageLoader = imageLoader,
                        seenCount = vm.seenCount,
                        totalInRound = vm.currentRoundSize,
                        onZoomChange = { zoomed = it },
                        // 跟手变换：graphicsLayer 惰性读取动画状态，逐帧不重组
                        modifier = Modifier.graphicsLayer {
                            translationX = followX
                            translationY = followY
                            rotationZ = followX * 0.12f
                        },
                    )
                    // 方向语义浮标：拖动越接近判定阈值，卡侧渐显当前映射的动作（左/右各自取色）
                    dragBadge(mapping.map[Gesture.LEFT])?.let { (label, bg, fg) ->
                        DragHintBadge(
                            text = label, bg = bg, fg = fg, side = -1, follow = { followX },
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp),
                        )
                    }
                    dragBadge(mapping.map[Gesture.RIGHT])?.let { (label, bg, fg) ->
                        DragHintBadge(
                            text = label, bg = bg, fg = fg, side = 1, follow = { followX },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
                        )
                    }
                    // 下拉撤回浮标（仅撤回栈非空时出现；无可撤不教这个动作）
                    if (vm.undoAvailable) {
                        DragHintBadge(
                            text = "撤回", bg = Daylight.canvas, fg = Daylight.inkStrong,
                            side = 1, follow = { followY }, vertical = true,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                        )
                    }
                }
            }

            // 有标记轮末的兜底过渡页：正常流程由 LaunchedEffect 自动打开复核，
            // 此屏只在用户主动关闭面板后兜底作重入口，不再停等点「查看结算」
            vm.roundEnded -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "本轮已处理 ${vm.currentRoundSize} 项",
                    color = Daylight.ink,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "标记 ${vm.markedCount} 项",
                    color = Daylight.inkSub,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(28.dp))
                PillButton(
                    text = "查看结算",
                    onClick = { settlementOpen = true },
                )
                Spacer(modifier = Modifier.height(10.dp))
                PearlButton(
                    text = "开始下一轮",
                    onClick = { vm.startNextRound() },
                )
            }

            // M2 池尽态（原「没有更多内容」死端分支）：文案 + 双按钮，禁止零按钮死局
            else -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "暂时没有待整理的照片",
                    color = Daylight.ink,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(modifier = Modifier.height(28.dp))
                if (vm.markedCount > 0) PillButton(
                    text = "查看待删除项",
                    onClick = { settlementOpen = true },
                ) else PillButton(
                    text = "检查新照片",
                    onClick = { vm.startNextRound() },
                )
                if (vm.markedCount == 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    PearlButton(
                        text = "重置图片进度",
                        onClick = { vm.reviewKeptAgain() },
                    )
                }
            }
        }

        // ② 每次打开 App 的共享一次提示，沿用当前手势映射。
        if (showGestureHint) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(Daylight.pillRadius))
                    .background(Daylight.chipTranslucent)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = hintText(mapping),
                    color = Daylight.ink,
                    fontSize = 13.sp,
                )
            }
        }

        // ③ 信息抽屉（kit 共享件）：当前媒体头图 + 分组行；下滑/点遮罩/系统返回关闭
        if (vm.infoOpen) {
            InfoSheet(
                onDismiss = { vm.infoOpen = false },
                rows = infoRows(item, exif),
                thumbModel = item?.uri,
                thumbContentDescription = item?.displayName?.ifBlank { "未命名媒体" },
                thumbLoader = imageLoader,
            )
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(snackbar, Modifier.navigationBarsPadding().padding(bottom = 88.dp))
    }

    // 终态才渲染（轮末会话必 currentId=null；池尽同）：既对齐面板语义，
    // 也防 saveable 残留 true 在进程重建后盖住新一轮卡片（重建期 ready=false 同样拦下）
    if (settlementOpen && vm.ready && vm.currentId == null) {
        SettlementPanel(
            markedIds = vm.markedSnapshot(),
            totalInRound = vm.seenCount,
            source = source,
            deleter = deleter,
            pendingStore = pendingSettlement,
            onKeep = { history.setKept(it, true) },
            onCompleted = { message, canRestore ->
                settlementOpen = false
                vm.startNextRound()
                screenScope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (snackbar.showSnackbar(
                            message = message,
                            actionLabel = if (canRestore) "找回" else null,
                            duration = SnackbarDuration.Short,
                            withDismissAction = true,
                        ) == SnackbarResult.ActionPerformed) onOpenTrash()
                }
            },
            onNextRound = {
                settlementOpen = false
                vm.startNextRound()
            },
            onDismiss = { settlementOpen = false },
            // 异常结果页统一出口；复核阶段取消仍由 onDismiss 回到轮末。
            onExitRound = {
                settlementOpen = false
                vm.startNextRound()
            },
            permanentAllowed = permanentAllowed,
        )
    }
}

/** 首卡提示文案（随手势映射动态生成，不写死默认序）。 */
private fun hintText(mapping: GestureMapping): String {
    val left = dragBadge(mapping.map[Gesture.LEFT])?.first ?: "标记删除"
    val right = dragBadge(mapping.map[Gesture.RIGHT])?.first ?: "跳过"
    return "左滑$left · 右滑$right · 下拉撤回"
}

/** 浮标样式按动作语义取色：删除 = 危险红；跳过 = 白底墨字（次级）；信息 = Action Blue。 */
private fun dragBadge(action: BoardAction?): Triple<String, Color, Color>? = when (action) {
    BoardAction.MARK_DELETE -> Triple("删除", Daylight.danger, Daylight.onPrimary)
    BoardAction.SKIP -> Triple("跳过", Daylight.canvas, Daylight.inkStrong)
    BoardAction.SHOW_INFO -> Triple("信息", Daylight.primary, Daylight.onPrimary)
    else -> null
}

/**
 * 大图卡：全幅留白（顶 = 状态栏 + 悬浮 chrome 高度，底 = 手势条余量）+ 影像唯一投影；
 * 底中轮内计数圆牌。文件信息不落卡面——上滑「信息」抽屉（kit.InfoSheet）承载。
 * 跟手位移 / 退场动画由调用侧经 modifier 注入（AnimatedContent 的 content lambda 内）。
 *
 * 缩放（iOS 照片口径）：双指捏合 1x–4x（焦点 = 双指中心）、双击 2.5x 放大/还原（焦点 = 点击点）、
 * 放大后单指平移（边界钳制不出空边）。缩放态经 [onZoomChange] 上报，宿主据此屏蔽滑卡手势；
 * 本卡随 AnimatedContent 换卡重组，缩放状态自然复位 1x。
 */
@Composable
private fun MediaCard(
    item: MediaItem,
    imageLoader: ImageLoader,
    seenCount: Int,
    totalInRound: Int,
    onZoomChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize()
            .padding(
                start = 12.dp, end = 12.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 48.dp + 12.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 14.dp,
            )
            .productShadow(RoundedCornerShape(Daylight.cardRadius))
            .clip(RoundedCornerShape(Daylight.cardRadius))
            .background(Daylight.parchment),
    ) {
        dev.sxc.kadaishan.ui.kit.ZoomableMediaImage(
            item = item, imageLoader = imageLoader,
            onZoomChange = onZoomChange,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .onMediaChip(RoundedCornerShape(Daylight.pillRadius))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = "$seenCount / $totalInRound",
                color = Daylight.ink,
                fontSize = 12.sp,
            )
        }
    }
}

/** 信息卡字段（PRD §4.1 图片口径：拍摄时间/尺寸/文件大小/分辨率/所在目录/设备型号；缺失显「—」）。 */
private fun infoRows(item: MediaItem?, exif: ExifSummary?): List<Pair<String, String>> {
    val missing = "—"
    val dimensions = item?.takeIf { it.width > 0 && it.height > 0 }
    val make = exif?.make?.takeIf { it.isNotBlank() }
    val model = exif?.model?.takeIf { it.isNotBlank() }
    val device = listOfNotNull(make, model).joinToString(" ").ifBlank { missing }
    return listOf(
        "拍摄时间" to (item?.dateTakenMs?.let { formatDate(it) } ?: missing),
        "尺寸" to (dimensions?.let { "${it.width} × ${it.height}" } ?: missing),
        "文件大小" to (item?.sizeBytes?.takeIf { it > 0 }?.let { formatSize(it) } ?: missing),
        "分辨率" to (dimensions?.let { "%.1f 万像素".format(it.width.toLong() * it.height / 10_000.0) } ?: missing),
        "所在目录" to (item?.let { it.relativePath.ifBlank { it.bucketName } }?.takeIf { it.isNotBlank() } ?: missing),
        "设备型号" to device,
    )
}

private fun formatDate(ms: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(ms))

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
