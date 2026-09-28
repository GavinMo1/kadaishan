package dev.sxc.kadaishan.ui.boards

import android.graphics.Matrix
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import dev.sxc.kadaishan.ui.kit.Daylight
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

// ═══ 视频板块（PRD §4.2 抖音式：全屏播放 + 标记交互；media3 播放核心单实例归本文件）═══
// 全屏播放：单 ExoPlayer（VideoCardViewModel 持有，onCleared 释放）+ TextureView surface，
//  滑入自动播放、默认静音、点按暂停/继续（点按层与删除按钮 overlay 分离）；
// 首帧海报（AsyncImage + coil-video VideoFrameDecoder，model = item.uri）作底图：
//  surface 未就绪 / 坏文件 / 加载中均透出海报，不黑屏。
// 上/下滑固定导航（不读 gestureMapping），手势须从内侧起始区开始（禁边缘手势）；
// 删除按钮与手势区分离；右上角 撤回 + 信息 + 小喇叭；顶层 inset 补偿由 MainActivity/AppRoot 统一做。

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
    val reducedMotion = rememberReducedMotion()
    // M4：轮末自动弹结算（含 0 标记 → 面板空态「本轮无删除项」）；面板关闭后轮末屏留「查看结算」重入口
    LaunchedEffect(vm.roundEnded, vm.currentId) {
        if (vm.roundEnded && vm.currentId == null) settlementOpen = true
    }

    // 进入 → 恢复播放；离开 → 停播 + 卸 surface + 取消 dwell 计时（会话状态按进程存活期保留）
    DisposableEffect(Unit) {
        vm.onScreenVisible()
        onDispose {
            vm.onScreenPaused()
            vm.attachSurface(null)
            vm.cancelDwell()
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
                // 手势热路径零分配：阈值/边界仅在进入时算一次，拖动只累加标量
                val edgePx = 24.dp.toPx()
                val thresholdPx = 56.dp.toPx()
                var total = 0f
                var valid = false
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        total = 0f
                        // 起点必须在内侧起始区（四边各留 24dp，禁边缘手势）
                        valid = offset.x >= edgePx && offset.x <= size.width - edgePx &&
                            offset.y >= edgePx && offset.y <= size.height - edgePx
                    },
                    onVerticalDrag = { _, dragAmount ->
                        if (valid) total += dragAmount
                    },
                    onDragEnd = {
                        if (valid && !vm.infoOpen) {
                            when {
                                total <= -thresholdPx -> { navDir = 1; vm.next(); haptics.tick(); onInteract() }   // 上滑 = 下一个
                                total >= thresholdPx -> { navDir = -1; vm.prev(); haptics.tick(); onInteract() }  // 下滑 = 上一个
                            }
                        }
                        valid = false
                    },
                    onDragCancel = {
                        valid = false
                        total = 0f
                    },
                )
            },
    ) {
        // ① 首帧海报（底图：surface 未就绪 / 坏文件 / 加载中兜底；与画面同为等比 Fit）
        // 换卡垂直转场：surface 单实例不动，海报层随 navDir 滑入滑出（surface 透明期海报即转场画面）
        AnimatedContent(
            targetState = currentId,
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(snap()) togetherWith fadeOut(snap())
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

        // ④ 点按层：播放区点按 = 暂停/继续（删除按钮/撤回/信息等上层先命中并消费自身点击 → 不触发暂停）
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(vm) {
                    detectTapGestures {
                        if (!vm.infoOpen) {
                            haptics.tap()
                            vm.togglePlayPause()
                        }
                    }
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

        // ③ 右上角 chrome（黑透圆片系，压任意海报都可读；撤回 false 置灰）
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DarkChip(text = "撤回", onClick = vm::undoMark, enabled = vm.undoAvailable)
            Spacer(modifier = Modifier.width(8.dp))
            DarkChip(text = "信息", onClick = { vm.infoOpen = !vm.infoOpen })
            Spacer(modifier = Modifier.width(8.dp))
            // 右上角小喇叭：默认静音 → 点开出声（黑透圆片 + 语义描述）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Daylight.surfaceBlack.copy(alpha = 0.4f))
                    .clickable { vm.toggleMute() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (vm.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (vm.muted) "开声音" else "关声音",
                    tint = Daylight.onDark,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ④ 「已标记删除」水印（停留期间显示于当前卡）+ 常驻计数（PRD §4.2 水印+计数）
        if (vm.dwellActive && vm.dwellId != null && vm.dwellId == currentId) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(Daylight.radiusLg))
                    .background(Daylight.surfaceBlack.copy(alpha = 0.72f))
                    .padding(horizontal = 26.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "已标记删除",
                    color = Daylight.onDark,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.011).em,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "已标记 ${vm.markedCount} 项",
                    color = Daylight.onDark.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        // ⑤ 删除按钮（toggle；与手势区分离，右下角；白色药丸 = 视频板的主键）
        PillButton(
            text = if (currentId != null && currentId in vm.markedIds) "取消标记" else "标记删除",
            onClick = {
                val willMark = currentId != null && currentId !in vm.markedIds
                if (willMark) haptics.confirm() else haptics.reject()
                vm.toggleMark()
            },
            enabled = currentId != null,
            container = Daylight.onDark,
            content = Daylight.surfaceBlack,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .statusBarsPadding()
                .padding(end = 20.dp, bottom = 36.dp),
        )

        // ⑤.5 底部进度条（画面之上、手势条余量内；横向拖动 seek，不影响上下滑翻页）
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
                    text = "上滑下一个 · 下滑上一个 · 点按暂停",
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

/** 深色媒体面上的文字圆片：黑 @40% + 白字（压任意海报可读），radiusSm。 */@Composable
private fun DarkChip(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(Daylight.radiusSm))
            .background(Daylight.surfaceBlack.copy(alpha = if (enabled) 0.4f else 0.2f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Daylight.onDark.copy(alpha = if (enabled) 0.95f else 0.5f),
            fontSize = 14.sp,
            letterSpacing = (-0.016).em,
        )
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
