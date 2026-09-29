package dev.sxc.kadaishan.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sxc.kadaishan.data.SettingsRepository
import dev.sxc.kadaishan.ui.kit.Daylight
import dev.sxc.kadaishan.ui.kit.GroupCard
import dev.sxc.kadaishan.ui.kit.NavTitleBar
import dev.sxc.kadaishan.ui.kit.SectionLabel
import dev.sxc.kadaishan.ui.kit.daylightBackground
import dev.sxc.kadaishan.ui.kit.rememberHaptics
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.BoardAction
import dev.sxc.kadaishan.model.Gesture
import dev.sxc.kadaishan.model.GestureMapping
import dev.sxc.kadaishan.model.SortClause
import dev.sxc.kadaishan.model.SortSpec
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 设置页（PRD §4.4 / §4.6 / §4.1；契约签名）。
 *  iOS 分组列表：SectionLabel（灰小字）+ GroupCard（米白分组容器）+ 行分隔线；
 *  Flow 经 collectAsState 汇入，改动立即走 setter；手势冲突在构造校验处拒绝、行内红字不落盘。
 *  子页自管 insets（顶 = 状态栏，底 = 手势条）。 */
@Composable
fun SettingsScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    // ① 手势映射：pending 为本次会话编辑态（冲突时保留尝试值以支持继续循环），落盘只认通过校验的构造。
    val storedMapping by repo.gestureMapping.collectAsState(initial = GestureMapping.default())
    var pendingMapping by remember { mutableStateOf<Map<Gesture, BoardAction>?>(null) }
    var gestureError by remember { mutableStateOf<String?>(null) }
    val mapping: Map<Gesture, BoardAction> = pendingMapping ?: storedMapping.map

    // ②~④
    val roundSizes by repo.perBoardRoundSize.collectAsState(initial = SettingsRepository.defaultRoundSizes())
    val sortStrategy by repo.sortStrategy.collectAsState(initial = SortSpec.Random)
    val permanentUnlocked by repo.permanentDeleteUnlocked.collectAsState(initial = true)
    val imageBoardsMerged by repo.imageBoardsMerged.collectAsState(initial = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .daylightBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        NavTitleBar(title = "设置", onBack = onBack)

        // ① 手势映射（图片板块）：左/右/上各一行，点按 = 与当前持有目标动作的另一手势交换（保持双射）
        SectionLabel("手势映射 · 图片板块")
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            val actionOrder = listOf(BoardAction.MARK_DELETE, BoardAction.SKIP, BoardAction.SHOW_INFO)
            listOf(
                Gesture.LEFT to "左滑",
                Gesture.RIGHT to "右滑",
                Gesture.UP to "上滑",
            ).forEachIndexed { index, (gesture, label) ->
                val action = mapping[gesture] ?: BoardAction.SKIP
                if (index > 0) RowDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable {
                            // 点按 = 与当前持有目标动作的另一手势交换。
                            // 循环 next 直接覆盖会撞双射校验（必抛 IAE）；交换保持双射，正常交互不再触发红字。
                            val next = actionOrder[(actionOrder.indexOf(action) + 1) % actionOrder.size]
                            val holder = mapping.entries.firstOrNull { it.value == next }?.key
                            if (holder != null && holder != gesture) {
                                try {
                                    val validated = GestureMapping(mapping).swapped(gesture, holder)
                                    pendingMapping = validated.map
                                    gestureError = null
                                    haptics.tap()
                                    scope.launch { repo.setGestureMapping(validated) }
                                } catch (e: IllegalArgumentException) {
                                    gestureError = "两个手势不能映射同一动作" // 兜底红字，不落盘
                                }
                            }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = label, style = MaterialTheme.typography.bodyLarge, color = Daylight.ink)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = actionLabel(action),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Daylight.primary,
                    )
                }
            }
            gestureError?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = Daylight.danger,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 10.dp),
                )
            }
        }

        // ② 每轮数量：板块行随图片板块模式增减（统一 = 照片/视频两行；分开 = 四行）；
        // 滑条粗调（拖动走本地态、松手一次性落盘）+ 点数值精输（数字键盘弹窗）；改动当前轮立即生效
        SectionLabel("每轮数量（1~300 · 改动立即生效）")
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            val boards = if (imageBoardsMerged) listOf(Board.PHOTO, Board.VIDEO) else Board.entries.toList()
            boards.forEachIndexed { index, board ->
                if (index > 0) RowDivider()
                RoundSizeRow(
                    board = board,
                    size = roundSizes[board] ?: SettingsRepository.defaultRoundSize(board),
                    onCommit = { v -> scope.launch { repo.setRoundSize(board, v) } },
                )
            }
        }

        // ②.5 图片板块模式（PRD §3.1）：统一归「照片」（缺省）/ 分开三板块
        SectionLabel("图片板块")
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            listOf(
                "统一：全部图片归「照片」一个板块（推荐）" to true,
                "分开：照片 / 截图 / 已保存图片" to false,
            ).forEachIndexed { index, (label, merged) ->
                if (index > 0) RowDivider()
                CheckRow(
                    label = label,
                    selected = imageBoardsMerged == merged,
                    onClick = {
                        haptics.tap()
                        scope.launch { repo.setImageBoardsMerged(merged) }
                    },
                )
            }
        }

        // ③ 排序：单选 随机 / 大文件优先 / 最新优先 / 最早优先
        SectionLabel("排序")
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            listOf(
                "随机" to SortSpec.Random,
                "大文件优先" to SortSpec.SqlOrder(SortClause.FILE_SIZE_DESC),
                "最新优先" to SortSpec.SqlOrder(SortClause.DATE_TAKEN_DESC),
                "最早优先" to SortSpec.SqlOrder(SortClause.DATE_TAKEN_ASC),
            ).forEachIndexed { index, (label, spec) ->
                if (index > 0) RowDivider()
                CheckRow(
                    label = label,
                    selected = sortStrategy == spec,
                    onClick = {
                        haptics.tap()
                        scope.launch { repo.setSortStrategy(spec) }
                    },
                )
            }
        }

        // ④ 允许永久删除（PRD §4.6：默认已解锁；真实开关，落盘）
        SectionLabel("永久删除")
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "允许永久删除",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Daylight.ink,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = permanentUnlocked,
                    onCheckedChange = { on ->
                        haptics.tap()
                        scope.launch { repo.setPermanentDeleteUnlocked(on) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Daylight.primary,
                        checkedThumbColor = Daylight.onPrimary,
                        uncheckedTrackColor = Daylight.hairline,
                        uncheckedThumbColor = Daylight.canvas,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 每轮数量行：板块名 + 滑条（拖动走本地态，松手一次性落盘——异步持久化不回弹、不丢末次写）+
 *  数值键（Action Blue 文字型可点信号，点开精输弹窗）。 */
@Composable
private fun RoundSizeRow(board: Board, size: Int, onCommit: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    var editOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = boardLabel(board),
            style = MaterialTheme.typography.bodyLarge,
            color = Daylight.ink,
            modifier = Modifier.width(56.dp),
        )
        Slider(
            value = if (dragging) dragValue else size.toFloat(),
            onValueChange = { v ->
                dragging = true
                dragValue = v
            },
            onValueChangeFinished = {
                dragging = false
                onCommit(dragValue.roundToInt())
            },
            valueRange = 1f..300f,
            colors = daylightSliderColors(),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            text = if (dragging) "${dragValue.roundToInt()}" else "$size",
            style = MaterialTheme.typography.bodyLarge,
            color = Daylight.primary,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(48.dp)
                .clickable { editOpen = true },
        )
    }
    if (editOpen) {
        RoundSizeDialog(
            board = board,
            initial = size,
            onConfirm = { v ->
                editOpen = false
                onCommit(v)
            },
            onDismiss = { editOpen = false },
        )
    }
}

/** 数值精输弹窗：数字键盘、仅数字 3 位、1~300 校验（越界/空 = 确定禁用 + 红提示）。 */
@Composable
private fun RoundSizeDialog(board: Board, initial: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in 1..300
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Daylight.canvas,
        title = {
            Text(
                text = "${boardLabel(board)} · 每轮数量",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Daylight.ink,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(3) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = text.isNotEmpty() && !valid,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Daylight.primary,
                        unfocusedBorderColor = Daylight.hairline,
                        focusedLabelColor = Daylight.primary,
                        cursorColor = Daylight.primary,
                        errorBorderColor = Daylight.danger,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (valid) "范围 1~300 · 改动立即生效" else "请输入 1~300 之间的整数",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) Daylight.inkSub else Daylight.danger,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) {
                Text(
                    text = "确定",
                    color = if (valid) Daylight.primary else Daylight.inkSub,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = Daylight.primary)
            }
        },
    )
}

/** 行分隔线（左端随内容缩进 16dp，右端贴容器）。 */
@Composable
private fun RowDivider() = HorizontalDivider(
    color = Daylight.dividerSoft,
    thickness = 1.dp,
    modifier = Modifier.padding(start = 16.dp),
)

/** 滑条配色（唯一来源）：Action Blue 轨 + 白拇 + 软灰底轨（SliderDefaults.colors 是 @Composable，须函数化）。 */
@Composable
private fun daylightSliderColors() = SliderDefaults.colors(
    thumbColor = Daylight.canvas,
    activeTrackColor = Daylight.primary,
    inactiveTrackColor = Daylight.hairline,
)

/** 单选行（iOS 设置语汇）：左侧标签 + 选中时右侧 Action Blue 对勾。 */
@Composable
private fun CheckRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Daylight.ink,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Daylight.primary,
            )
        }
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
    Board.SAVED -> "已保存"
    Board.VIDEO -> "视频"
}
