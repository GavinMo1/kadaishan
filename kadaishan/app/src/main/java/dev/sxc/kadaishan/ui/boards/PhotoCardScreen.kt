package dev.sxc.kadaishan.ui.boards

import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import dev.sxc.kadaishan.ui.kit.GhostChip
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
// 左/右/上滑全部经 settings.gestureMapping（图片板专属；默认 左=标记删除、右=跳过、上=信息卡、下=不绑定）；
// 手势须从内侧起始区开始（禁边缘手势）；右上角 撤回（undo false 置灰）；顶层 inset 补偿由 MainActivity/AppRoot 统一做；
// 轮末（含 0 标记）自动弹结算（M4）；池尽 = 「池已尽 · 已看 N 项」+ 查看结算/开始下一轮双按钮（M2，禁死局）。

/** 图片板块契约签名（宿主 MainActivity 按位置调用：board, source, settings, exifReader）。 */
@Composable
fun PhotoCardScreen(
    board: Board,
    source: MediaSource,
    settings: SettingsRepository,
    exifReader: ExifReader,
    deleter: MediaDeleter,
    pendingSettlement: PendingSettlementRepository,
    onToggleChrome: () -> Unit = {},
    onInteract: () -> Unit = {},
) {
    // 按板块分实例：PHOTO/SCREENSHOT/SAVED 共用本屏，ViewModelStoreOwner 为 Activity 级，
    // 不带 key 会让三板块串会话（board 查询错板）；key 分实例后切板回来会话状态保留。
    val vm: PhotoCardViewModel = viewModel(
        key = "photo-card:${board.name}",
        factory = viewModelFactory {
            initializer { PhotoCardViewModel(board, source, settings) }
        },
    )

    // 手势映射（图片板专属；热路径直接查表，映射变化由 pointerInput 键重建捕获）
    val mapping by settings.gestureMapping.collectAsState(initial = GestureMapping.default())
    // 设置「允许永久删除」快照（锁定 → 结算面板不出现永久删除开关）
    val permanentAllowed by settings.permanentDeleteUnlocked.collectAsState(initial = true)
    val context = LocalContext.current
    // 切图零闪烁：解码预热——未来 5 项预解码进 Coil 内存缓存（命中内存缓存即时渲染、crossfade 跳过）
    val imageLoader = remember(context) { ImageLoader.Builder(context).build() }
    LaunchedEffect(vm.currentId, vm.items) {
        vm.upcomingUris(5).forEach { uri ->
            imageLoader.enqueue(ImageRequest.Builder(context).data(uri).build())
        }
    }
    // M1：saveable —— 旋转/配置重建不丢面板状态（VM 存活，标记仍在会话里）
    var settlementOpen by rememberSaveable { mutableStateOf(false) }

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

    // M4 轮末自动弹结算：消费满每轮数量即开面板，0 标记不拦截（空态由 S4 面板承接）。
    // roundEnded 翻 true 触发一次；用户关闭后不重复弹（key 未变 effect 不重跑），
    // 轮末过渡屏的「查看结算」作重入口；开新轮 roundEnded 归 false，下轮结束再次触发。
    LaunchedEffect(vm.roundEnded) {
        if (vm.roundEnded) settlementOpen = true
    }

    BackHandler(enabled = vm.infoOpen) { vm.infoOpen = false }

    val item: MediaItem? = vm.currentId?.let { vm.items[it] }

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
                // 手势热路径零分配：阈值/边界仅在进入时算一次，拖动只累加标量
                val edgePx = 24.dp.toPx()
                val thresholdPx = 56.dp.toPx()
                var totalX = 0f
                var totalY = 0f
                var valid = false
                detectDragGestures(
                    onDragStart = { offset ->
                        totalX = 0f
                        totalY = 0f
                        // 起点必须在内侧起始区（四边各留 24dp，禁边缘手势）
                        valid = offset.x >= edgePx && offset.x <= size.width - edgePx &&
                            offset.y >= edgePx && offset.y <= size.height - edgePx
                    },
                    onDrag = { _, dragAmount ->
                        if (valid) {
                            totalX += dragAmount.x
                            totalY += dragAmount.y
                            // 跟手：位移 16% + 轻微倾斜（弹簧追踪，直写目标无分配）
                            followXTarget = (totalX * 0.16f).coerceIn(-72f, 72f)
                            followYTarget = (totalY * 0.16f).coerceIn(-72f, 72f)
                        }
                    },
                    onDragEnd = {
                        if (valid && !vm.infoOpen) {
                            // 主轴判定 → Gesture → 映射表取动作（DOWN 未绑定 → null → 无动作）
                            val gesture: Gesture? = if (abs(totalX) >= abs(totalY)) when {
                                totalX <= -thresholdPx -> Gesture.LEFT
                                totalX >= thresholdPx -> Gesture.RIGHT
                                else -> null
                            } else when {
                                totalY <= -thresholdPx -> Gesture.UP
                                totalY >= thresholdPx -> Gesture.DOWN
                                else -> null
                            }
                            val action = gesture?.let { mapping.map[it] }
                            if (action != null) {
                                when (action) {
                                    BoardAction.MARK_DELETE -> haptics.confirm()
                                    BoardAction.SKIP -> haptics.tick()
                                    BoardAction.SHOW_INFO -> haptics.tap()
                                }
                                // 退场方向随手势：左滑退左、右滑退右（上滑开信息卡不换卡）
                                if (gesture == Gesture.LEFT) exitDir = -1
                                if (gesture == Gesture.RIGHT) exitDir = 1
                                vm.onAction(action)
                                onInteract()   // 干活自动收起顶栏（查看器式收纳）
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
            !vm.ready -> Text(
                text = "加载中…",
                modifier = Modifier.align(Alignment.Center),
                color = Daylight.inkStrong,
                style = MaterialTheme.typography.bodyLarge,
            )

            vm.currentId != null && item == null -> Spinner(
                modifier = Modifier.align(Alignment.Center),
            )

            item != null -> AnimatedContent(
                targetState = item,
                transitionSpec = {
                    if (reducedMotion) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else {
                        // 授权动效（本 App 唯一作者化时刻）：旧卡沿手势方向退场、新卡对侧缓入
                        (slideInHorizontally(Daylight.spatial()) { w -> exitDir * -w / 4 } + fadeIn(Daylight.settle()))
                            .togetherWith(
                                slideOutHorizontally(Daylight.spatial()) { w -> exitDir * w / 3 } + fadeOut(Daylight.settle()),
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
                            text = label, bg = bg, fg = fg, side = -1, followX = { followX },
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp),
                        )
                    }
                    dragBadge(mapping.map[Gesture.RIGHT])?.let { (label, bg, fg) ->
                        DragHintBadge(
                            text = label, bg = bg, fg = fg, side = 1, followX = { followX },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
                        )
                    }
                }
            }

            // 轮末纯过渡（M4）：正常流由上面的 LaunchedEffect 自动弹结算（含 0 标记），
            // 此屏只在用户主动关闭面板后兜底作重入口，不再停等点「查看结算」
            vm.roundEnded -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "本轮已处理 ${vm.currentRoundSize} 项",
                    color = Daylight.ink,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
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
                    text = "池已尽 · 已看 ${vm.seenCount} 项",
                    color = Daylight.ink,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
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
        }

        // ② 右上角动作圆片（信息 / 撤回；撤回 false 置灰）——悬浮 chrome 之下、卡内右上
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 68.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostChip(text = "信息", onClick = { vm.infoOpen = true })
            Spacer(modifier = Modifier.width(8.dp))
            GhostChip(text = "撤回", onClick = vm::undo, enabled = vm.undoAvailable)
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

    // 终态才渲染（轮末会话必 currentId=null；池尽同）：既对齐面板语义，
    // 也防 saveable 残留 true 在进程重建后盖住新一轮卡片（重建期 ready=false 同样拦下）
    if (settlementOpen && vm.ready && vm.currentId == null) {
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
            // M5：结果页「返回」「继续下一轮」共用——清标记进新轮（与 Video 侧一致）；
            // 划走/返回键（onDismissRequest→onDismiss）仍回轮末过渡屏，留「查看结算」重入口
            onExitRound = {
                settlementOpen = false
                vm.startNextRound()
            },
            permanentAllowed = permanentAllowed,
        )
    }
}

/**
 * 拖动方向浮标：随跟手进度渐显（alpha/缩放在 graphicsLayer 惰性读 followX，逐帧不重组），
 * 倾斜角与卡片同步。side = -1 左缘（响应左拖）、+1 右缘（响应右拖）。
 */
@Composable
private fun DragHintBadge(
    text: String,
    bg: Color,
    fg: Color,
    side: Int,
    followX: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .graphicsLayer {
                val x = followX()
                val progress = ((side * x - 14f) / 26f).coerceIn(0f, 1f)
                alpha = progress
                val s = 0.9f + 0.1f * progress
                scaleX = s
                scaleY = s
                rotationZ = x * 0.12f
            }
            .clip(RoundedCornerShape(Daylight.pillRadius))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
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
 */
@Composable
private fun MediaCard(
    item: MediaItem,
    imageLoader: ImageLoader,
    seenCount: Int,
    totalInRound: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 58.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 14.dp,
            )
            .productShadow(RoundedCornerShape(Daylight.cardRadius))
            .clip(RoundedCornerShape(Daylight.cardRadius))
            .background(Daylight.parchment),
    ) {
        AsyncImage(
            // crossfade(200) + parchment 占位（占位挂在 ImageRequest 上：
            // AsyncImage.placeholder 只收 Painter，request.placeholder 会在 Loading 态透出），
            // 卡面 radiusMd 裁圆；contentDescription = displayName（无障碍朗读文件名）
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.uri)
                .placeholder(ColorDrawable(Daylight.parchment.toArgb()))
                .crossfade(200)
                .build(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            imageLoader = imageLoader,
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(Daylight.radiusMd)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .onMediaChip(RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 5.dp),
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
