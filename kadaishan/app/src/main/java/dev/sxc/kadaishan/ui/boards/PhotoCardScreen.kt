package dev.sxc.kadaishan.ui.boards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import dev.sxc.kadaishan.data.ExifReader
import dev.sxc.kadaishan.data.MediaDeleter
import dev.sxc.kadaishan.data.PendingSettlementRepository
import dev.sxc.kadaishan.data.SettingsRepository
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.ExifSummary
import dev.sxc.kadaishan.model.Gesture
import dev.sxc.kadaishan.model.GestureMapping
import dev.sxc.kadaishan.model.MediaItem
import dev.sxc.kadaishan.ui.settle.SettlementPanel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// ═══ 图片板块（PRD §4.1 大图卡片流）═══
// 一次一张全屏大图（AsyncImage，model = item.uri），滑动即切换、无确认层；
// 左/右/上滑全部经 settings.gestureMapping（图片板专属；默认 左=标记删除、右=跳过、上=信息卡、下=不绑定）；
// 手势须从内侧起始区开始（禁边缘手势）；右上角 撤回（undo false 置灰）；顶层 inset 补偿由 MainActivity/AppRoot 统一做。

/** 图片板块契约签名（宿主 MainActivity 按位置调用：board, source, settings, exifReader）。 */
@Composable
fun PhotoCardScreen(
    board: Board,
    source: MediaSource,
    settings: SettingsRepository,
    exifReader: ExifReader,
    deleter: MediaDeleter,
    pendingSettlement: PendingSettlementRepository,
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
    var settlementOpen by remember { mutableStateOf(false) }

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
            .background(Color.Black)
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
                            if (action != null) vm.onAction(action)
                        }
                        valid = false
                    },
                    onDragCancel = {
                        valid = false
                        totalX = 0f
                        totalY = 0f
                    },
                )
            },
    ) {
        // ① 大图 / 终态
        when {
            !vm.ready -> Text(
                text = "加载中…",
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyLarge,
            )

            vm.currentId != null && item == null -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(36.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )

            item != null -> AsyncImage(
                model = item.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )

            vm.roundEnded -> Column(
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

            else -> Text(
                text = "没有更多内容",
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        // ② 计数（标记数，左上角常显）
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

        // ③ 右上角：撤回（false 置灰）
        TextButton(
            onClick = vm::undo,
            enabled = vm.undoAvailable,
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Text("撤回")
        }

        // ④ 信息卡浮层（最上层；五字段+设备型号，缺失显「—」；点空白/关闭/系统返回退出）
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
                        infoRows(item, exif).forEach { (label, value) ->
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
