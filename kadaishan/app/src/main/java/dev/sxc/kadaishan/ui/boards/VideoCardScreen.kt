package dev.sxc.kadaishan.ui.boards

import android.graphics.Matrix
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
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
    var settlementOpen by remember { mutableStateOf(false) }

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
            .background(Color.Black)
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
                                total <= -thresholdPx -> vm.next()   // 上滑 = 下一个
                                total >= thresholdPx -> vm.prev()    // 下滑 = 上一个
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
        // ① 首帧海报（底图：surface 未就绪 / 坏文件 / 加载中兜底）
        poster?.let { item ->
            AsyncImage(
                model = item.uri,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }

        // ② 播放 surface（TextureView：出帧前透明 → 底图透出；overlay 正常叠画在其上）
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { tv ->
                    tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        applyCropTransform(tv, vm.videoWidth, vm.videoHeight)
                    }
                }
            },
            update = { tv ->
                vm.attachSurface(tv)
                applyCropTransform(tv, vm.videoWidth, vm.videoHeight)
            },
            modifier = Modifier.matchParentSize(),
        )

        // ③ 终态 / 加载画面（画在 surface 之上）
        when {
            !vm.ready -> Text(
                text = "加载中…",
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyLarge,
            )

            currentId != null && poster == null -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(36.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )

            currentId == null && vm.roundEnded -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "本轮已处理 ${vm.currentRoundSize} 项",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "标记 ${vm.markedCount} 项",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    if (vm.markedCount == 0) vm.startNextRound() else settlementOpen = true
                }) {
                    Text(if (vm.markedCount == 0) "开始下一轮" else "查看结算")
                }
            }

            currentId == null -> Text(
                text = "没有更多内容",
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        // ④ 点按层：播放区点按 = 暂停/继续（删除按钮/撤回/信息等上层先命中并消费自身点击 → 不触发暂停）
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(vm) {
                    detectTapGestures {
                        if (!vm.infoOpen) vm.togglePlayPause()
                    }
                },
        )

        // ② 计数（标记数，左上角常显；dwell 期间随 session.markedCount +1）
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "已标记 ${vm.markedCount}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // ③ 右上角：撤回（false 置灰）+ 信息
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 4.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = vm::undoMark, enabled = vm.undoAvailable) {
                Text("撤回")
            }
            TextButton(onClick = { vm.infoOpen = !vm.infoOpen }) {
                Text("信息")
            }
            // 右上角小喇叭：默认静音（🔇）→ 点开出声（🔊）
            TextButton(onClick = vm::toggleMute) {
                Text(if (vm.muted) "🔇" else "🔊")
            }
        }

        // ④ 「已标记删除」水印（停留期间显示于当前卡）
        if (vm.dwellActive && vm.dwellId != null && vm.dwellId == currentId) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "已标记删除",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // ⑤ 删除按钮（toggle；与手势区分离，右下角）
        Button(
            onClick = vm::toggleMark,
            enabled = currentId != null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Text(if (currentId != null && currentId in vm.markedIds) "取消标记" else "标记删除")
        }

        // ⑥ Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // ⑦ 信息卡浮层（最上层；点空白/关闭/系统返回退出）
        if (vm.infoOpen) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { vm.infoOpen = false },
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "信息", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.infoOpen = false }) {
                                Text("关闭")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        infoRows(poster, exif).forEach { (label, value) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.width(76.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = value,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
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

/** 视频 → TextureView 满屏裁切（居中 crop，抖音式铺满）。
 *  仅在片源尺寸 / 布局变化时计算，不在手势或逐帧热路径上。 */
private fun applyCropTransform(tv: TextureView, videoW: Int, videoH: Int) {
    val viewW = tv.width
    val viewH = tv.height
    if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) {
        tv.setTransform(Matrix()) // 尺寸未知 → 恢复默认映射
        return
    }
    val scale = maxOf(viewW.toDouble() / videoW, viewH.toDouble() / videoH)
    val sx = (videoW * scale) / viewW
    val sy = (videoH * scale) / viewH
    tv.setTransform(
        Matrix().apply {
            setScale(sx.toFloat(), sy.toFloat(), viewW / 2f, viewH / 2f)
        },
    )
}
