package dev.sxc.kadaishan.ui.settle

import android.app.Activity
import android.content.IntentSender
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.sxc.kadaishan.data.MediaDeleter
import dev.sxc.kadaishan.data.PendingSettlementRepository
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.domain.settle.verifySettlement
import dev.sxc.kadaishan.model.DeleteMode
import dev.sxc.kadaishan.model.PERM_DELETE_CAP
import dev.sxc.kadaishan.model.PendingSettlement
import dev.sxc.kadaishan.model.SettlementItemResult
import dev.sxc.kadaishan.model.SettlementItemStatus
import dev.sxc.kadaishan.model.SnapItem
import dev.sxc.kadaishan.ui.kit.CircleCheck
import dev.sxc.kadaishan.ui.kit.DangerPillButton
import dev.sxc.kadaishan.ui.kit.Daylight
import dev.sxc.kadaishan.ui.kit.PearlButton
import dev.sxc.kadaishan.ui.kit.PillButton
import dev.sxc.kadaishan.ui.kit.Spinner
import dev.sxc.kadaishan.ui.kit.SpringSheet
import dev.sxc.kadaishan.ui.kit.minTouch
import dev.sxc.kadaishan.ui.kit.rememberReducedMotion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.ceil

/** 结算面相位。公开：作为 [SettlementPanel] 的 initialPhase 冷启动恢复入参，复用同一相位类型。 */
enum class PanelStage { LOADING, REVIEW, CONFIRMING, VERIFYING, RESULT }
private enum class SystemOperation { DELETE, RESTORE }

/**
 * 卡片轮末的真实结算面。弹窗回调只负责推进/停止批次，最终结果一律由 recheck 裁决。
 * 这也是暂存快照的唯一写入点，未结算的手势标记不会持久化。
 *
 * @param initialPhase 冷启动恢复：非空时跳过 REVIEW 直接进入该相位（传 VERIFYING，
 *   入参 markedIds 即 pending.snapshot，回查既成事实，不重发删除）。
 * @param onExitRound 结果页「返回」与「继续下一轮」共用的退出回调（宿主清标记开新轮）；
 *   缺省时回退现有语义——「继续下一轮」走 [onNextRound]、「返回」走 [onDismiss]。
 */
@Composable
fun SettlementPanel(
    markedIds: List<Long>,
    totalInRound: Int,
    source: MediaSource,
    deleter: MediaDeleter,
    pendingStore: PendingSettlementRepository,
    onNextRound: () -> Unit,
    onDismiss: () -> Unit,
    initialPhase: PanelStage? = null,
    onExitRound: (() -> Unit)? = null,
    /** 设置「允许永久删除」快照：false = 结算页不出现永久删除开关（锁定语义在此落位）。 */
    permanentAllowed: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    // 面板批次态走 rememberSaveable：旋转/重建不丢相位与批进度、不重复发批；
    // enum 一律以 name 字符串入库（沿用 MainActivity 的 Bundle 可存性口径）。
    // outgoing 刻意用 remember 不落盘：重建后不重新 launch，系统弹窗结果由 ResultRegistry 补送。
    var stage by rememberSaveable(markedIds, stateSaver = enumSaver<PanelStage>()) { mutableStateOf(initialPhase ?: PanelStage.LOADING) }
    var available by remember(markedIds) { mutableStateOf<List<SnapItem>>(emptyList()) }
    var chosen by rememberSaveable(markedIds, stateSaver = LongSetSaver) { mutableStateOf(markedIds.toSet()) }
    var snapshot by rememberSaveable { mutableStateOf<List<SnapItem>>(emptyList()) }
    var batches by rememberSaveable { mutableStateOf<List<List<SnapItem>>>(emptyList()) }
    var batchIndex by rememberSaveable { mutableStateOf(0) }
    var outgoing by remember { mutableStateOf<IntentSender?>(null) }
    var results by rememberSaveable { mutableStateOf<List<SettlementItemResult>>(emptyList()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var operation by rememberSaveable(stateSaver = enumSaver<SystemOperation>()) { mutableStateOf(SystemOperation.DELETE) }
    // 「立即永久删除」开关：不持久化，每次进入结算页复位为关。
    var permDelete by rememberSaveable(markedIds) { mutableStateOf(false) }
    // 本轮真实删除模式：确认时写入，快照/分批/暂存/发批/回查全链跟随。
    var deleteMode by rememberSaveable(markedIds, stateSaver = enumSaver<DeleteMode>()) { mutableStateOf(DeleteMode.TRASH) }

    /** 回查一回：withTimeout(2000) 重试 1 次（共 2 次）；仍超时 → 结果页「核对失败，去系统相册确认」。 */
    val runRecheck: suspend () -> List<SettlementItemResult> = {
        val ids = snapshot.map { it.id }
        val rows = try {
            try {
                withTimeout(2_000L) { deleter.recheck(ids) }
            } catch (_: TimeoutCancellationException) {
                withTimeout(2_000L) { deleter.recheck(ids) }
            }
        } catch (_: TimeoutCancellationException) {
            error = "核对失败，去系统相册确认"
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        rows?.let { verifySettlement(snapshot, deleteMode, it, true) }
            ?: snapshot.map { SettlementItemResult(it, SettlementItemStatus.UNKNOWN) }
    }

    fun verify() {
        stage = PanelStage.VERIFYING
    }

    fun launchCurrentBatch() {
        val batch = batches.getOrNull(batchIndex) ?: run { verify(); return }
        try {
            outgoing = when (operation) {
                SystemOperation.DELETE -> deleter.createBatchIntent(batch, deleteMode)
                SystemOperation.RESTORE -> deleter.createRestoreIntent(batch)
            }
        } catch (e: Exception) {
            error = "系统无法创建删除请求：${e.message ?: e.javaClass.simpleName}"
            verify()
        }
    }

    fun finishRestore() {
        scope.launch {
            val restored = try {
                val rows = deleter.recheck(snapshot.map { it.id }).associateBy { it.id }
                snapshot.count { rows[it.id]?.isTrashed == false }
            } catch (_: Exception) {
                0
            }
            val msg = if (restored == snapshot.size) "已回查确认找回 $restored 项。"
            else "找回请求已完成；请回到原目录确认 $restored/${snapshot.size} 项。"
            error = if (error == null) msg else "$msg；$error"
            stage = PanelStage.RESULT
        }
    }

    /** 批次发送失败：该批降档拆子批重发（每档自动重试 1 次；子批尺寸严格递减、下限 5，必然终止）；
     *  拆无可拆 → 止损文案，不崩溃。 */
    fun handleLaunchFailure() {
        val failed = batches.getOrNull(batchIndex)
        val subs = if (failed != null) deleter.fallbackSubBatches(failed) else emptyList()
        if (subs.isNotEmpty()) {
            batches = batches.toMutableList().apply {
                removeAt(batchIndex)
                addAll(batchIndex, subs)
            }
            launchCurrentBatch()
        } else {
            error = "请减少勾选数量或分轮处理"
            if (operation == SystemOperation.RESTORE) finishRestore() else verify()
        }
    }

    // 冷启动恢复用 MATCH_INCLUDE（快照项可能已在回收站）；正常流程排除已入回收站项。
    LaunchedEffect(markedIds) {
        val items = source.queryByIds(
            markedIds,
            if (initialPhase != null) MediaStore.MATCH_INCLUDE else MediaStore.MATCH_EXCLUDE,
        ).map { SnapItem(it.id, it.uri.toString(), it.displayName, it.sizeBytes) }
        available = items
        when {
            stage == PanelStage.LOADING -> {
                chosen = items.map { it.id }.toSet()
                stage = PanelStage.REVIEW
            }
            // 冷启动恢复：入参即 pending.snapshot，回查既成事实；快照已空（全部执行完）直接出结果页。
            initialPhase != null && snapshot.isEmpty() -> {
                snapshot = items
                if (items.isEmpty()) stage = PanelStage.RESULT
            }
        }
    }

    // 回查驱动：确认链与冷启动恢复都汇到这里，重建后仍能推进，不无限转圈。
    LaunchedEffect(stage, snapshot) {
        if (stage == PanelStage.VERIFYING && snapshot.isNotEmpty()) {
            results = runRecheck()
            stage = PanelStage.RESULT
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        outgoing = null
        // Deny 后不能继续请求下一批；但不把 resultCode 当成成功判定，仍必须回查。
        if (result.resultCode != Activity.RESULT_OK) {
            if (operation == SystemOperation.RESTORE) finishRestore() else verify()
        } else {
            batchIndex++
            if (batchIndex < batches.size) launchCurrentBatch() else {
                if (operation == SystemOperation.RESTORE) finishRestore() else verify()
            }
        }
    }
    LaunchedEffect(outgoing) {
        val sender = outgoing ?: return@LaunchedEffect
        try {
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (_: Exception) {
            outgoing = null
            handleLaunchFailure()
        }
    }

    // 弹簧抽屉物理层（Slide 原型）：CONFIRMING/VERIFYING 相位锁关闭（把手可拉回弹、遮罩/返回无效）
    SpringSheet(
        onDismiss = onDismiss,
        closable = stage != PanelStage.CONFIRMING && stage != PanelStage.VERIFYING,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            when (stage) {
                PanelStage.LOADING -> LoadingContent("正在核对本轮标记…")
                PanelStage.REVIEW -> if (available.isEmpty()) {
                    // M4 空态（0 标记轮）：不摆死掉的确认钮，直接给「开始下一轮」出口（经 onExitRound 清标记开新轮）
                    Text(
                        "本轮无删除项",
                        style = MaterialTheme.typography.titleLarge,
                        color = Daylight.ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "本轮没有标记任何删除项，可直接开始下一轮。",
                        color = Daylight.inkSub,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(24.dp))
                    PillButton(
                        text = "开始下一轮",
                        onClick = { (onExitRound ?: onDismiss)() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    if (permDelete) {
                        Text(
                            "永久删除不可恢复",
                            color = Daylight.danger,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Daylight.radiusSm))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        "确认本轮删除",
                        style = MaterialTheme.typography.titleLarge,
                        color = Daylight.ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "本轮共 $totalInRound 项 · 你标记了 ${markedIds.size} 项 · 勾选了 ${chosen.size} 项",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Daylight.ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "默认移入回收站，可从本 App「回收站」页找回，保留期以每项标注为准。注意：vivo 系统相册「最近删除」不显示这些项目，属正常。",
                        color = Daylight.inkSub,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        TextButton(onClick = { chosen = emptySet() }) {
                            Text("一键全部不选", color = Daylight.primary)
                        }
                    }
                    // 永久模式已勾项的前 PERM_DELETE_CAP 项（列表序）：只用于灰显定位，不改写勾选集合（禁止静默剔除）。
                    val withinCapIds = if (permDelete) {
                        val ids = LinkedHashSet<Long>()
                        for (item in available) {
                            if (item.id in chosen) {
                                ids += item.id
                                if (ids.size >= PERM_DELETE_CAP) break
                            }
                        }
                        ids
                    } else {
                        emptySet()
                    }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        itemsIndexed(available, key = { _, item -> item.id }) { index, item ->
                            val checked = item.id in chosen
                            val overCap = permDelete &&
                                if (checked) item.id !in withinCapIds else chosen.size >= PERM_DELETE_CAP
                            if (index > 0) {
                                HorizontalDivider(color = Daylight.dividerSoft, thickness = 1.dp)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 56.dp)
                                    .alpha(if (overCap) 0.38f else 1f)
                                    .clickable(enabled = !overCap) {
                                        chosen = if (checked) chosen - item.id else chosen + item.id
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircleCheck(checked = checked)
                                Spacer(Modifier.width(12.dp))
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(Daylight.radiusSm))
                                        .background(Daylight.parchment),
                                )
                                Text(
                                    text = item.displayName.ifBlank { "未命名媒体" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Daylight.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .weight(1f),
                                )
                                Text(
                                    text = formatSize(item.sizeBytes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Daylight.inkSub,
                                )
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (permanentAllowed) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("立即永久删除", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            // 只切模式，不剔除勾选：超限项走灰显 + 提示（PRD §4.3）。
                            Switch(
                                checked = permDelete,
                                onCheckedChange = { permDelete = it },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = Daylight.primary,
                                    checkedThumbColor = Daylight.onPrimary,
                                    uncheckedTrackColor = Daylight.hairline,
                                    uncheckedThumbColor = Daylight.canvas,
                                ),
                            )
                        }
                    }
                    if (permDelete && chosen.size > PERM_DELETE_CAP) {
                        Text("永久删除单轮上限 $PERM_DELETE_CAP 项，请分轮或改用回收站", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (chosen.isNotEmpty()) {
                        Text(
                            "将弹出 ${ceil(chosen.size / 50.0).toInt()} 次系统确认，每批 ≤50 项",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val confirmEnabled = chosen.isNotEmpty() && (!permDelete || chosen.size <= PERM_DELETE_CAP)
                    val confirmAction: () -> Unit = {
                        deleteMode = if (permDelete && permanentAllowed) DeleteMode.PERMANENT else DeleteMode.TRASH
                        snapshot = available.filter { it.id in chosen }
                        batches = snapshot.chunked(50)
                        batchIndex = 0
                        operation = SystemOperation.DELETE
                        error = null
                        stage = PanelStage.CONFIRMING
                        scope.launch {
                            pendingStore.save(PendingSettlement(snapshot, deleteMode, 0, ceil(snapshot.size / 50.0).toInt()))
                            launchCurrentBatch()
                        }
                    }
                    if (chosen.isEmpty()) {
                        // 全不选后确认钮禁用会成死局：给「跳过本轮」出口（PRD §4.4 全不选=可继续）
                        Text(
                            "未勾选任何项，将跳过本轮",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Daylight.inkSub,
                        )
                        Spacer(Modifier.height(8.dp))
                        PillButton(
                            text = "开始下一轮",
                            onClick = { (onExitRound ?: onDismiss)() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (permDelete) {
                        DangerPillButton(
                            text = "永久删除 ${chosen.size} 项 · 不可恢复",
                            onClick = confirmAction,
                            enabled = confirmEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        PillButton(
                            text = "移入回收站 ${chosen.size} 项 · ${formatSize(available.filter { it.id in chosen }.sumOf { it.sizeBytes })}",
                            onClick = confirmAction,
                            enabled = confirmEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                PanelStage.CONFIRMING -> LoadingContent(
                    "${if (operation == SystemOperation.RESTORE) "正在请求找回" else "等待系统确认"}… 第 ${batchIndex + 1}/${batches.size} 批",
                )
                PanelStage.VERIFYING -> LoadingContent("正在回查实际结果…")
                PanelStage.RESULT -> ResultContent(
                    results = results,
                    mode = deleteMode,
                    error = error,
                    onRetry = {
                        val retry = results.filter { it.status == SettlementItemStatus.NOT_EXECUTED }.map { it.item }
                        if (retry.isNotEmpty()) {
                            snapshot = retry
                            batches = retry.chunked(50)
                            batchIndex = 0
                            operation = SystemOperation.DELETE
                            error = null
                            stage = PanelStage.CONFIRMING
                            scope.launch {
                                // 重试沿用当次结果的 mode（deleteMode 仍是产生 results 的那一轮的模式）
                                pendingStore.save(PendingSettlement(retry, deleteMode, 0, ceil(retry.size / 50.0).toInt()))
                                launchCurrentBatch()
                            }
                        }
                    },
                    onRestore = {
                        val trashed = results.filter { it.status == SettlementItemStatus.TRASHED }.map { it.item }
                        if (trashed.isNotEmpty()) {
                            snapshot = trashed
                            batches = trashed.chunked(50)
                            batchIndex = 0
                            operation = SystemOperation.RESTORE
                            error = null
                            stage = PanelStage.CONFIRMING
                            launchCurrentBatch()
                        }
                    },
                    // 「返回」与「继续下一轮」同效：清暂存标记后经 onExitRound 交宿主清标记开新轮；
                    // 缺省回退现有语义（onNextRound / onDismiss）。
                    onNext = {
                        scope.launch { pendingStore.clear() }
                        (onExitRound ?: onNextRound)()
                    },
                    onClose = {
                        scope.launch { pendingStore.clear() }
                        (onExitRound ?: onDismiss)()
                    },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable private fun LoadingContent(text: String) = Row(
    Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
) {
    Spinner()
    Text(
        text,
        Modifier.padding(start = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = Daylight.inkStrong,
    )
}

@Composable private fun ResultContent(
    results: List<SettlementItemResult>, mode: DeleteMode, error: String?, onRetry: () -> Unit, onRestore: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
) {
    val trashedResults = results.filter { it.status == SettlementItemStatus.TRASHED }
    val trashed = trashedResults.size
    val deletedResults = results.filter { it.status == SettlementItemStatus.DELETED }
    val deleted = deletedResults.size
    val pending = results.count { it.status == SettlementItemStatus.NOT_EXECUTED }
    val unknown = results.count { it.status == SettlementItemStatus.UNKNOWN }
    Text(
        "处理结果",
        style = MaterialTheme.typography.titleLarge,
        color = Daylight.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    // 三态计数：数字 600 + ink；未执行/结果未知 >0 时该数字转 danger。
    val summary = buildAnnotatedString {
        fun count(value: Int, danger: Boolean): Int {
            pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = if (danger) Daylight.danger else Daylight.ink))
            append(value.toString())
            pop()
            return value
        }
        append("已入回收站 ")
        count(trashed, false)
        append(" · 已永久删除 ")
        count(deleted, false)
        append(" · 未执行 ")
        count(pending, pending > 0)
        append(" · 结果未知 ")
        count(unknown, unknown > 0)
    }
    Text(
        summary,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    if (mode == DeleteMode.PERMANENT && deleted > 0) {
        CountUpBytes(bytes = deletedResults.sumOf { it.item.sizeBytes })
    }
    if (trashed > 0) {
        val latestExpires = trashedResults.mapNotNull { it.dateExpiresSec }.maxOrNull()
        if (latestExpires == null) {
            Text("之后由系统清除", color = Daylight.inkSub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            Text(
                "回收站项合计 ${formatSize(trashedResults.sumOf { it.item.sizeBytes })} 保留至 ${formatDate(latestExpires)}，之后由系统清除",
                color = Daylight.inkSub,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Text(
        "回收站项目在 vivo 系统相册「最近删除」中不可见（双体系）；找回请使用本 App 入口。",
        color = Daylight.inkSub,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let {
        Text(
            it,
            color = Daylight.danger,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // 差异项逐个全列（PRD §4.3「逐个列出」），不截断；行 = 名称 + 右侧状态值（iOS 值右对齐语汇）。
    val details = trashedResults + results.filter { it.status != SettlementItemStatus.TRASHED && it.status != SettlementItemStatus.DELETED }
    if (details.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(details, key = { it.item.id }) { r ->
                val name = r.item.displayName.ifBlank { "未命名媒体" }
                val expires = (if (r.status == SettlementItemStatus.TRASHED) r.dateExpiresSec else null)?.let { formatDate(it) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Daylight.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusLabel(r.status) + (if (expires == null) "" else " · 保留至 $expires"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r.status == SettlementItemStatus.NOT_EXECUTED) Daylight.danger else Daylight.inkSub,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    if (pending > 0) PearlButton(onClick = onRetry, text = "重试未执行项", modifier = Modifier.fillMaxWidth())
    if (trashed > 0) PearlButton(onClick = onRestore, text = "找回本次删除项", modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    PillButton(onClick = onNext, text = "继续下一轮", modifier = Modifier.fillMaxWidth())
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = onClose) { Text("返回", color = Daylight.primary) }
    }
}

/** enum → name 字符串入库（沿用 MainActivity「以 name 字符串保存」的 Bundle 可存性口径）。 */
private inline fun <reified T : Enum<T>> enumSaver(): Saver<T, Any> = Saver(
    save = { it.name },
    restore = { name -> enumValues<T>().firstOrNull { it.name == name } },
)

/** Set<Long> → 逗号串：不依赖集合类的 Serializable 白名单细节。 */
private val LongSetSaver: Saver<Set<Long>, String> = Saver(
    save = { it.joinToString(",") },
    restore = { raw ->
        if (raw.isEmpty()) emptySet()
        else raw.split(",").mapTo(LinkedHashSet<Long>()) { it.toLong() }
    },
)

/** 峰值时刻：释放空间数值滚动落定（settle 弹簧 0→1；reducedMotion 直达终值）。 */
@Composable
private fun CountUpBytes(bytes: Long) {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(bytes) {
        if (!reduced && bytes > 0) {
            progress.snapTo(0f)
            progress.animateTo(1f, Daylight.settle())
        } else {
            progress.snapTo(1f)
        }
    }
    Text(
        "已释放 ${formatSize((bytes * progress.value).toLong())}",
        color = Daylight.inkSub,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun statusLabel(status: SettlementItemStatus): String = when (status) {
    SettlementItemStatus.TRASHED -> "已入回收站"
    SettlementItemStatus.DELETED -> "已永久删除"
    SettlementItemStatus.NOT_EXECUTED -> "未执行"
    SettlementItemStatus.UNKNOWN -> "结果未知，可手动回查"
}

/** dateExpiresSec（epoch 秒）→ yyyy-MM-dd，保留期由回查 DATE_EXPIRES 动态展示。 */
private fun formatDate(epochSec: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochSec * 1000))

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "大小未知"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
