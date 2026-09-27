package dev.sxc.kadaishan.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sxc.kadaishan.data.SettingsRepository
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.BoardAction
import dev.sxc.kadaishan.model.Gesture
import dev.sxc.kadaishan.model.GestureMapping
import dev.sxc.kadaishan.model.SortClause
import dev.sxc.kadaishan.model.SortSpec
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 设置页（PRD §4.4 / §4.6 / §4.1；契约签名）。
 *  分组卡片；Flow 经 collectAsState 汇入，改动立即走 setter；手势冲突在构造校验处拒绝、行内红字不落盘。
 *  顶层 inset 补偿由 MainActivity/AppRoot 统一做，本组件不再套 insets。 */
@Composable
fun SettingsScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    // ① 手势映射：pending 为本次会话编辑态（冲突时保留尝试值以支持继续循环），落盘只认通过校验的构造。
    val storedMapping by repo.gestureMapping.collectAsState(initial = GestureMapping.default())
    var pendingMapping by remember { mutableStateOf<Map<Gesture, BoardAction>?>(null) }
    var gestureError by remember { mutableStateOf<String?>(null) }
    val mapping: Map<Gesture, BoardAction> = pendingMapping ?: storedMapping.map

    // ②~⑤
    val roundSizes by repo.perBoardRoundSize.collectAsState(initial = SettingsRepository.defaultRoundSizes())
    val sortStrategy by repo.sortStrategy.collectAsState(initial = SortSpec.Random)
    val dwellMs by repo.videoDwellMs.collectAsState(initial = SettingsRepository.DEFAULT_DWELL_MS)
    val permanentUnlocked by repo.permanentDeleteUnlocked.collectAsState(initial = false)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // 顶栏：返回按钮调 onBack
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(text = "设置", style = MaterialTheme.typography.titleLarge)
        }

        // ① 手势映射（图片板块）：左/右/上各一行，点按循环切换动作
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "手势映射（图片板块）", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                val actionOrder = listOf(BoardAction.MARK_DELETE, BoardAction.SKIP, BoardAction.SHOW_INFO)
                listOf(
                    Gesture.LEFT to "左滑",
                    Gesture.RIGHT to "右滑",
                    Gesture.UP to "上滑",
                ).forEach { (gesture, label) ->
                    val action = mapping[gesture] ?: BoardAction.SKIP
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = actionOrder[(actionOrder.indexOf(action) + 1) % actionOrder.size]
                                val candidate = mapping + (gesture to next)
                                try {
                                    val validated = GestureMapping(candidate) // 冲突在此拒绝（IAE）
                                    pendingMapping = candidate
                                    gestureError = null
                                    scope.launch { repo.setGestureMapping(validated) }
                                } catch (e: IllegalArgumentException) {
                                    gestureError = "两个手势不能映射同一动作" // 行内红字，不落盘
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(56.dp))
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = actionLabel(action),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                gestureError?.let { msg ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ② 每轮数量：四板块各一行滑条 1~300 + 当前值
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "每轮数量（1~300）", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Board.entries.forEach { board ->
                    val size = roundSizes[board] ?: SettingsRepository.defaultRoundSize(board)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = boardLabel(board), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(56.dp))
                        Slider(
                            value = size.toFloat(),
                            onValueChange = { v ->
                                scope.launch { repo.setRoundSize(board, v.roundToInt()) }
                            },
                            valueRange = 1f..300f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(text = "$size", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(40.dp))
                    }
                }
            }
        }

        // ③ 排序：单选 随机 / 大文件优先 / 最新优先 / 最早优先
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "排序", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                listOf(
                    "随机" to SortSpec.Random,
                    "大文件优先" to SortSpec.SqlOrder(SortClause.FILE_SIZE_DESC),
                    "最新优先" to SortSpec.SqlOrder(SortClause.DATE_TAKEN_DESC),
                    "最早优先" to SortSpec.SqlOrder(SortClause.DATE_TAKEN_ASC),
                ).forEach { (label, spec) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { scope.launch { repo.setSortStrategy(spec) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = sortStrategy == spec,
                            onClick = { scope.launch { repo.setSortStrategy(spec) } },
                        )
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // ④ 视频标记停留：滑条 0~2000ms + 数值
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "视频标记停留（0~2000ms）", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Slider(
                        value = dwellMs.toFloat(),
                        onValueChange = { v ->
                            scope.launch { repo.setVideoDwellMs(v.roundToInt()) }
                        },
                        valueRange = 0f..2000f,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Text(text = "${dwellMs}ms", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(64.dp))
                }
            }
        }

        // ⑤ 只读状态行：永久删除解锁标志
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (permanentUnlocked) {
                        "立即永久删除：已解锁"
                    } else {
                        "立即永久删除：未解锁（完成找回演练后解锁）"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun actionLabel(action: BoardAction): String = when (action) {
    BoardAction.MARK_DELETE -> "标记删除"
    BoardAction.SKIP -> "跳过"
    BoardAction.SHOW_INFO -> "查看信息"
}

private fun boardLabel(board: Board): String = when (board) {
    Board.PHOTO -> "照片"
    Board.SCREENSHOT -> "截图"
    Board.SAVED -> "收藏"
    Board.VIDEO -> "视频"
}
