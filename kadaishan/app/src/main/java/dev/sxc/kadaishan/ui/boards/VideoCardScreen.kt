package dev.sxc.kadaishan.ui.boards

import android.graphics.Matrix
import android.view.TextureView
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import dev.sxc.kadaishan.ui.kit.Daylight
import dev.sxc.kadaishan.ui.kit.DragHintBadge
import dev.sxc.kadaishan.ui.kit.InfoSheet
import dev.sxc.kadaishan.ui.kit.OnDarkButton
import dev.sxc.kadaishan.ui.kit.PillButton
import dev.sxc.kadaishan.ui.kit.Spinner
import dev.sxc.kadaishan.ui.kit.rememberHaptics
import dev.sxc.kadaishan.ui.kit.rememberReducedMotion
import dev.sxc.kadaishan.data.ExifReader
import dev.sxc.kadaishan.data.MediaDeleter
import dev.sxc.kadaishan.data.PendingSettlementRepository
import dev.sxc.kadaishan.data.SettingsRepository
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.model.ExifSummary
import dev.sxc.kadaishan.model.MediaItem
import dev.sxc.kadaishan.ui.settle.SettlementPanel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// ═══ 视频板块（PRD §4.2 抖音式：全屏播放 + 滑动标记；media3 播放核心单实例归本文件）═══
// 全屏播放：单 ExoPlayer（VideoCardViewModel 持有，onCleared 释放）+ TextureView surface，
//  滑入自动播放、默认静音、点按暂停/继续（点按层与其它 overlay 分离）；
// 首帧海报（AsyncImage + coil-video VideoFrameDecoder，model = item.uri）作底图：
//  surface 未就绪 / 坏文件 / 加载中均透出海报，不黑屏。
// 手势：左滑 = 标记删除（已标记卡再左滑 = 取消标记）+ 前进；右滑 = 保留 + 前进；上/下滑 = 下/上一个；
//  点按 = 暂停/继续，长按 = 信息抽屉（纵向手势被翻页占用，信息的隐藏入口）；恒静音（无声开关）。
//  横向 = 标记语汇（与图片板一致），纵向 = 翻页导航（抖音式）；标记成功发 Snackbar「已标记第 N 个 · 撤回」。
//  进度条带内横向拖动 = seek（子层消费优先，父层手势自动取消）；带外横向滑动 = 标记手势。
// 已标记卡左上角红点圆牌（回看可辨）；顶层 inset 补偿由 MainActivity/AppRoot 统一做。

/** 视频板块契约签名（宿主 MainActivity 按位置调用）。 */
@Composable
fun VideoCardScreen(
    source: MediaSource,
    settings: SettingsRepository,
    exifReader: ExifReader,
    deleter: MediaDeleter,
    pendingSettlement: PendingSettlementRepository,
    onToggleChrome: () -> Unit = {},
    onInteract: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: VideoCardViewModel = viewModel(
        factory = viewModelFactory {
            initializer { VideoCardViewModel(source, settings, context.applicationContext) }
        },
    )
    val imageLoader = remember(context) {
        // 视频帧解码必须注册到本 ImageLoader 组件（coil 默认单例不含 VideoFrameDecoder）
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }
    var settlementOpen by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    // 设置「允许永久删除」快照（锁定 → 结算面板不出现永久删除开关）
    val permanentAllowed by settings.permanentDeleteUnlocked.collectAsState(initial = true)
    // 换卡方向（转场用）：上滑下一个 = 新卡自底入，下滑回看 = 自顶入
    var navDir by remember { mutableStateOf(1) }
    // 换卡轴：true = 横向（左删/右留，与图片板同语汇），false = 纵向（翻页导航）
    var navAxis by remember { mutableStateOf(false) }
    // 甩出速度接续：横向退场行程系数（1.0~1.8，转场 lambda 内读）
    var exitBoost by remember { mutableStateOf(1f) }
    val reducedMotion = rememberReducedMotion()
    // 横向拖动浮标跟手（画面本体不位移：播放中平移画面观感撕裂，浮标渐显已足够反馈）
    var followXTarget by remember { mutableStateOf(0f) }
    val followX by animateFloatAsState(
        targetValue = followXTarget,
        animationSpec = if (reducedMotion) snap() else Daylight.settle(),
        label = "vFollowX",
    )
    // M4：轮末自动弹结算（含 0 标记 → 面板空态「本轮无删除项」）；面板关闭后轮末屏留「查看结算」重入口
    LaunchedEffect(vm.roundEnded, vm.currentId) {
        if (vm.roundEnded && vm.currentId == null) settlementOpen = true
    }

    // 进入 → 恢复播放；离开 → 停播 + 卸 surface（会话状态按进程存活期保留）
    DisposableEffect(Unit) {
        vm.onScreenVisible()
        onDispose {
            vm.onScreenPaused()
            vm.attachSurface(null)
        }
    }

    BackHandler(enabled = vm.infoOpen) { vm.infoOpen = false }

    val currentId = vm.currentId
    val poster: MediaItem? = currentId?.let { vm.items[it] }

    // 跳后 Snackbar「已标记第 N 个 · 撤回」（Short = 4s ≥ 1.5s；撤回 = unmark 该 id）
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm.snack) {
        val event = vm.snack ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "已标记第 ${event.count} 个",
            actionLabel = "撤回",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoSnack(event.id)
        vm.ackSnack(event.seq)
    }

    // 信息卡设备型号（按需读取；卡片打开且详情就绪时才发起）
    var exif by remember(currentId) { mutableStateOf<ExifSummary?>(null) }
    LaunchedEffect(vm.infoOpen, poster) {
        exif = if (vm.infoOpen && poster != null) exifReader.read(poster) else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Daylight.surfaceBlack)
            .pointerInput(vm) {
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
                        // 起点必须在内侧起始区（四边各留 24dp，禁边缘手势）
                        valid = offset.x >= edgePx && offset.x <= size.width - edgePx &&
                            offset.y >= edgePx && offset.y <= size.height - edgePx
                    },
                    onDrag = { change, dragAmount ->
                        if (valid) {
                            totalX += dragAmount.x
                            totalY += dragAmount.y
                            tracker.addPosition(change.uptimeMillis, change.position)
                            // 浮标跟手（16% 截幅）；进度条带内横向拖动被子层 seek 消费 → 本层手势自动取消，不误标
                            followXTarget = (totalX * 0.16f).coerceIn(-72f, 72f)
                        }
                    },
                    onDragEnd = {
                        val flickX = tracker.calculateVelocity().x
                        tracker.resetTracking()
                        if (valid && !vm.infoOpen) {
                            if (abs(totalX) >= abs(totalY)) {
                                // 横向 = 标记语汇（与图片板一致：左删右留）；速度接续进退场行程
                                when {
                                    totalX <= -thresholdPx -> {
                                        navAxis = true; navDir = -1
                                        exitBoost = 1f + (abs(flickX) / 12000f).coerceIn(0f, 0.8f)
                                        haptics.confirm(); vm.markCurrentAndAdvance(); onInteract()
                                    }
                                    totalX >= thresholdPx -> {
                                        navAxis = true; navDir = 1
                                        exitBoost = 1f + (abs(flickX) / 12000f).coerceIn(0f, 0.8f)
                                        haptics.tick(); vm.next(); onInteract()
                                    }
                                }
                            } else {
                                // 纵向 = 翻页导航（抖音式）
                                when {
                                    totalY <= -thresholdPx -> { navAxis = false; navDir = 1; haptics.tick(); vm.next(); onInteract() }   // 上滑 = 下一个
                                    totalY >= thresholdPx -> { navAxis = false; navDir = -1; haptics.tick(); vm.prev(); onInteract() }  // 下滑 = 上一个
                                }
                            }
                        }
                        valid = false
                        followXTarget = 0f
                    },
                    onDragCancel = {
                        valid = false
                        totalX = 0f
                        totalY = 0f
                        followXTarget = 0f
                    },
                )
            },
    ) {
        // ① 首帧海报（底图：surface 未就绪 / 坏文件 / 加载中兜底；与画面同为等比 Fit）
        // 换卡转场：surface 单实例不动，海报层随导航方向滑入滑出（surface 透明期海报即转场画面）；
        // 横向（左删右留，图片板同语汇）/ 纵向（翻页导航）按触发手势取轴
        AnimatedContent(
            targetState = currentId,
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                } else if (navAxis) {
                    // 横向退场 = fling 弹簧 + 松手速度接续的行程系数（与图片板同手感）
                    (slideInHorizontally(Daylight.spatial()) { w -> navDir * -w / 4 } + fadeIn(Daylight.settle()))
                        .togetherWith(
                            slideOutHorizontally(Daylight.fling()) { w -> (navDir * w / 3f * exitBoost).toInt() } + fadeOut(Daylight.settle()),
                        )
                } else {
                    (slideInVertically(Daylight.spatial()) { h -> navDir * h / 3 } + fadeIn(Daylight.settle()))
                        .togetherWith(
                            slideOutVertically(Daylight.spatial()) { h -> -navDir * h / 4 } + fadeOut(Daylight.settle()),
                        )
                }
            },
            label = "video-card",
        ) { id ->
            Box(modifier = Modifier.fillMaxSize()) {
                id?.let { key -> vm.items[key] }?.let { item ->
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.displayName.ifBlank { "未命名媒体" },
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // ② 播放 surface（TextureView：出帧前透明 → 底图透出；overlay 正常叠画在其上）
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { tv ->
                    tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        applyFitTransform(tv, vm.videoWidth, vm.videoHeight)
                    }
                }
            },
            update = { tv ->
                vm.attachSurface(tv)
                applyFitTransform(tv, vm.videoWidth, vm.videoHeight)
            },
            modifier = Modifier.matchParentSize(),
        )

        // ③ 终态 / 加载画面（画在 surface 之上）
        when {
            !vm.ready -> Text(
                text = "加载中…",
                modifier = Modifier.align(Alignment.Center),
                color = Daylight.onDark.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyLarge,
            )

            currentId != null && poster == null -> Spinner(
                modifier = Modifier.align(Alignment.Center),
            )

            // buffering 态：海报静止与暂停的可区分（等帧指示）
            currentId != null && vm.buffering -> Spinner(
                modifier = Modifier.align(Alignment.Center),
            )

            currentId == null && vm.roundEnded -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "本轮已处理 ${vm.currentRoundSize} 项",
                    color = Daylight.onDark,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "标记 ${vm.markedCount} 项",
                    color = Daylight.onDark.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(28.dp))
                PillButton(
                    text = "查看结算",
                    onClick = { settlementOpen = true },
                )
                Spacer(modifier = Modifier.height(10.dp))
                OnDarkButton(
                    text = "开始下一轮",
                    onClick = { vm.startNextRound() },
                )
            }

            // M4 池尽未满轮：零按钮死局消除（查看结算 / 开始下一轮）
            currentId == null -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "池已尽 · 没有更多内容",
                    color = Daylight.onDark,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(modifier = Modifier.height(28.dp))
                PillButton(
                    text = "查看结算",
                    onClick = { settlementOpen = true },
                )
                Spacer(modifier = Modifier.height(10.dp))
                OnDarkButton(
                    text = "开始下一轮",
                    onClick = { vm.startNextRound() },
                )
            }
        }

        // ④ 点按层：点按 = 暂停/继续；长按 = 信息抽屉（视频板纵向手势被翻页占用，信息的隐藏入口）
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(vm) {
                    detectTapGestures(
                        onTap = {
                            if (!vm.infoOpen) {
                                haptics.tap()
                                vm.togglePlayPause()
                            }
                        },
                        onLongPress = {
                            if (!vm.infoOpen) {
                                haptics.tap()
                                vm.infoOpen = true
                            }
                        },
                    )
                },
        )

        // 暂停指示：中央半透播放符（缓冲态由等帧指示承接，不叠加；播放中不显示）
        if (currentId != null && vm.ready && vm.paused && !vm.buffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Daylight.surfaceBlack.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "已暂停，点按继续",
                    tint = Daylight.onDark,
                    modifier = Modifier.size(34.dp),
                )
            }
        }

        // ③ 已标记指示（左上角红点圆牌）：按钮退役后回看可辨的唯一标记态信号
        if (currentId != null && currentId in vm.markedIds) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 12.dp)
                    .clip(RoundedCornerShape(Daylight.pillRadius))
                    .background(Daylight.surfaceBlack.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Daylight.danger),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "已标记",
                    color = Daylight.onDark,
                    fontSize = 13.sp,
                )
            }
        }

        // ④.5 横向拖动浮标（左删右留，与图片板同色语义；渐显即手势教学）
        DragHintBadge(
            text = "删除", bg = Daylight.danger, fg = Daylight.onPrimary, side = -1, follow = { followX },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp),
        )
        DragHintBadge(
            text = "保留", bg = Daylight.canvas, fg = Daylight.inkStrong, side = 1, follow = { followX },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
        )

        // ⑤ 底部进度条（画面之上、手势条余量内；横向拖动 seek，不影响上下滑翻页）
        if (currentId != null) {
            VideoScrubber(
                positionMs = vm.positionMs,
                durationMs = vm.durationMs,
                onSeek = { f ->
                    haptics.tick()
                    vm.seekTo(f)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }

        // 首轮首卡一次性操作提示：首次导航/标记（visited 增长或指针移动）后条件自然失效，无需状态位
        if (vm.ready && currentId != null && vm.visitedIds.size <= 1 && vm.viewIndex == 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Daylight.surfaceBlack.copy(alpha = 0.5f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = "左滑删除 · 右滑保留 · 长按信息",
                    color = Daylight.onDark,
                    fontSize = 13.sp,
                )
            }
        }

        // ⑥ Snackbar（M8：上移避开右下删除钮）
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
        )

        // ⑦ 信息抽屉（kit 共享件）：当前视频头图（Coil 视频帧）+ 分组行；下滑/点遮罩/系统返回关闭
        if (vm.infoOpen) {
            InfoSheet(
                onDismiss = { vm.infoOpen = false },
                rows = infoRows(poster, exif),
                thumbModel = poster?.uri,
                thumbContentDescription = poster?.displayName?.ifBlank { "未命名媒体" },
                thumbLoader = imageLoader,
            )
        }
    }

    if (settlementOpen) {
        SettlementPanel(
            markedIds = vm.markedSnapshot(),
            totalInRound = vm.currentRoundSize,
            source = source,
            deleter = deleter,
            pendingStore = pendingSettlement,
            onNextRound = {
                settlementOpen = false
                vm.startNextRound()
            },
            onDismiss = { settlementOpen = false },
            onExitRound = {
                settlementOpen = false
                vm.startNextRound()
            },
            permanentAllowed = permanentAllowed,
        )
    }
}

/**
 * 底部进度条（iOS 播放器语汇）：静息 = 3dp 细线（白 30% 轨 + 白进度）；
 * 按住 = 出 14dp 白拇 + 时间胶囊「0:12 / 1:03」，横向拖动 seek。
 * 垂直滑动不被本条消费 → 上下滑翻页不受影响。
 */
@Composable
private fun VideoScrubber(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var fraction by remember { mutableStateOf(0f) }
    val shown = when {
        scrubbing -> fraction
        durationMs > 0 -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (scrubbing && durationMs > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Daylight.surfaceBlack.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "${formatDuration((fraction * durationMs).toLong())} / ${formatDuration(durationMs)}",
                    color = Daylight.onDark,
                    fontSize = 12.sp,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(28.dp)
                .pointerInput(onSeek) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            scrubbing = true
                        },
                        onHorizontalDrag = { change, _ ->
                            // 绝对位置取值（标准拖动条手感；change.position.x 恒为 Float，不依赖回调签名版本）
                            change.consume()
                            fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeek(fraction)
                            scrubbing = false
                        },
                        onDragCancel = { scrubbing = false },
                    )
                },
        ) {
            val trackEnd = maxWidth - 14.dp
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Daylight.onDark.copy(alpha = 0.3f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(shown)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Daylight.onDark),
            )
            if (scrubbing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = trackEnd * shown)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Daylight.onDark),
                )
            }
        }
    }
}

/** 信息卡字段（PRD §4.1 视频口径 + §4.2：拍摄时间/尺寸/文件大小/分辨率/所在目录/设备型号/时长；缺失显「—」）。 */
private fun infoRows(item: MediaItem?, exif: ExifSummary?): List<Pair<String, String>> {
    val missing = "—"
    val dimensions = item?.takeIf { it.width > 0 && it.height > 0 }
    val make = exif?.make?.takeIf { it.isNotBlank() }
    val model = exif?.model?.takeIf { it.isNotBlank() }
    val device = listOfNotNull(make, model).joinToString(" ").ifBlank { missing }
    return listOf(
        "拍摄时间" to (item?.dateTakenMs?.let { formatDate(it) } ?: missing),
        "尺寸" to (dimensions?.let { "${it.width} × ${it.height}" } ?: missing),
        "文件大小" to (item?.sizeBytes?.let { formatSize(it) } ?: missing),
        "分辨率" to (dimensions?.let { "%.1f 万像素".format(it.width.toLong() * it.height / 10_000.0) } ?: missing),
        "所在目录" to (item?.let { it.relativePath.ifBlank { it.bucketName } }?.takeIf { it.isNotBlank() } ?: missing),
        "设备型号" to device,
        "时长" to (item?.durationMs?.takeIf { it > 0 }?.let { formatDuration(it) } ?: missing),
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

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 视频 → TextureView 等比适配（黑底信箱，不裁切）：竖屏源近满屏、横屏/录屏完整呈现。
 * 仅在片源尺寸 / 布局变化时计算，不在手势或逐帧热路径上。
 */
private fun applyFitTransform(tv: TextureView, videoW: Int, videoH: Int) {
    val viewW = tv.width
    val viewH = tv.height
    if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) {
        tv.setTransform(Matrix()) // 尺寸未知 → 恢复默认映射
        return
    }
    val scale = minOf(viewW.toDouble() / videoW, viewH.toDouble() / videoH)
    val sx = (videoW * scale) / viewW
    val sy = (videoH * scale) / viewH
    tv.setTransform(
        Matrix().apply {
            setScale(sx.toFloat(), sy.toFloat(), viewW / 2f, viewH / 2f)
        },
    )
}
