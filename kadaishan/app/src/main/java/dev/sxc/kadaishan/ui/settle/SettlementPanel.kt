package dev.sxc.kadaishan.ui.settle

import android.app.Activity
import android.content.IntentSender
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import dev.sxc.kadaishan.domain.settle.canContinueAfterSettlement
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.sxc.kadaishan.data.MediaDeleter
import dev.sxc.kadaishan.data.PendingSettlementRepository
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.domain.settle.BatchFlow
import dev.sxc.kadaishan.domain.settle.DegradeResult
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
import dev.sxc.kadaishan.ui.kit.TextActionButton
import dev.sxc.kadaishan.ui.kit.daylightRowClickable
import dev.sxc.kadaishan.ui.kit.rememberHaptics
import dev.sxc.kadaishan.ui.kit.rememberReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.ceil

/** 结算面相位。公开：作为 [SettlementPanel] 的 initialPhase 冷启动恢复入参，复用同一相位类型。 */
enum class PanelStage { LOADING, REVIEW, CONFIRMING, VERIFYING, RESULT }
private enum class SystemOperation { DELETE, RESTORE }

/** 删除/找回发批上限（G0 唯一实测档：create*Request 分批 ≤50，单批过大被系统拒绝）。 */
private const val BATCH_CAP = 50

/**
 * 卡片轮末的真实结算面。全屏模态页（底部上滑入场；复核可返回，结果退出统一开新轮）；
 * CONFIRMING/VERIFYING 相位锁关闭（返回键无效）。
 * 弹窗回调只负责推进/停止批次，最终结果一律由 recheck 裁决。
 * 这也是暂存快照的唯一写入点，未结算的手势标记不会持久化。
 *
 * @param initialPhase 冷启动恢复：非空时跳过 REVIEW 直接进入该相位（传 VERIFYING，
 *   initialSnapshot/initialMode 承载 pending 原始事实，不重发删除）。
 * @param onExitRound 异常结果页统一退出回调，宿主清标记开新轮。
 * @param onCompleted 全部回查成功后的轻反馈与自动继续；冷启动恢复可不提供。
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
    onCompleted: ((message: String, canRestore: Boolean) -> Unit)? = null,
    initialSnapshot: List<SnapItem> = emptyList(),
    initialMode: DeleteMode = DeleteMode.TRASH,
    onKeep: suspend (Set<Long>) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    // 面板批次态走 rememberSaveable：旋转/重建不丢相位与批进度、不重复发批；
    // enum 一律以 name 字符串入库（沿用 MainActivity 的 Bundle 可存性口径）。
    // outgoing 刻意用 remember 不落盘：重建后不重新 launch，系统弹窗结果由 ResultRegistry 补送。
    var stage by rememberSaveable(markedIds, stateSaver = enumSaver<PanelStage>()) { mutableStateOf(initialPhase ?: PanelStage.LOADING) }
    var available by remember(markedIds) { mutableStateOf<List<SnapItem>>(emptyList()) }
    var chosen by rememberSaveable(markedIds, stateSaver = LongSetSaver) { mutableStateOf(markedIds.toSet()) }
    var snapshot by rememberSaveable { mutableStateOf(initialSnapshot) }
    // 批次推进 = BatchFlow 单一来源（纯状态机，与回收站页共享）；remember 刻意不落盘：
    // 旋转/重建丢 flow 时由守卫转 VERIFYING 回查既成事实，绝不重发系统弹窗（与 M1 守卫同哲学）。
    var flow by remember { mutableStateOf<BatchFlow?>(null) }
    var outgoing by remember { mutableStateOf<IntentSender?>(null) }
    var results by rememberSaveable { mutableStateOf<List<SettlementItemResult>>(emptyList()) }
    var previewId by rememberSaveable(markedIds) { mutableStateOf<Long?>(null) }
    var savingKeep by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var operation by rememberSaveable(stateSaver = enumSaver<SystemOperation>()) { mutableStateOf(SystemOperation.DELETE) }
    // 「立即永久删除」开关：不持久化，每次进入结算页复位为关。
    var permDelete by rememberSaveable(markedIds) { mutableStateOf(false) }
    // 本轮真实删除模式：确认时写入，快照/分批/暂存/发批/回查全链跟随。
    var deleteMode by rememberSaveable(markedIds, stateSaver = enumSaver<DeleteMode>()) { mutableStateOf(initialMode) }

    /** 回查一回：withTimeout(2000) 重试 1 次（共 2 次）；仍超时 → 结果页「暂时无法核对，请重新核对或稍后在本 App 回收站查看」。 */
    val runRecheck: suspend () -> List<SettlementItemResult> = {
        val ids = snapshot.map { it.id }
        val rows = try {
            try {
                withTimeout(2_000L) { deleter.recheck(ids) }
            } catch (_: TimeoutCancellationException) {
                withTimeout(2_000L) { deleter.recheck(ids) }
            }
        } catch (_: TimeoutCancellationException) {
            error = "暂时无法核对，请重新核对或稍后在本 App 回收站查看"
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

    fun launchCurrentBatch() {
        val f = flow
        val batch = f?.current() ?: run { verify(); return }
        scope.launch {
            // A stale URI rejects the entire system request at construction time on the target device.
            val liveIds = try {
                source.queryByIds(
                    batch.map { it.id },
                    if (operation == SystemOperation.RESTORE) MediaStore.MATCH_INCLUDE else MediaStore.MATCH_EXCLUDE,
                ).map { it.id }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "无法读取这些项目，请检查相册权限后重试"
                if (operation == SystemOperation.RESTORE) finishRestore() else verify()
                return@launch
            }
            val live = batch.filter { it.id in liveIds }
            if (live.isEmpty()) {
                f.advance()
                if (!f.finished) launchCurrentBatch()
                else if (operation == SystemOperation.RESTORE) finishRestore() else verify()
                return@launch
            }
            try {
                outgoing = when (operation) {
                    SystemOperation.DELETE -> deleter.createBatchIntent(live, deleteMode)
                    SystemOperation.RESTORE -> deleter.createRestoreIntent(live)
                }
            } catch (_: SecurityException) {
                error = "相册权限已变更，请重新授权后重试"
                if (operation == SystemOperation.RESTORE) finishRestore() else verify()
            } catch (_: Exception) {
                when (val result = f.degradeCurrent()) {
                    is DegradeResult.Retrying -> launchCurrentBatch()
                    is DegradeResult.Skipped -> {
                        if (result.batch.isNotEmpty()) error = "请减少勾选数量或分轮处理"
                        if (operation == SystemOperation.RESTORE) finishRestore() else verify()
                    }
                }
            }
        }
    }

    /** 批次发送失败（launch 抛异常，如 TransactionTooLarge）：该批降档拆子批重发
     *  （每档自动重试 1 次；子批尺寸严格递减、下限 5，必然终止）；
     *  拆无可拆 → 止损文案，不崩溃；被跳过批不预计失败——最终由回查裁决（NOT_EXECUTED）。 */
    fun handleLaunchFailure() {
        when (val result = flow?.degradeCurrent()) {
            is DegradeResult.Retrying -> launchCurrentBatch()
            is DegradeResult.Skipped -> {
                if (result.batch.isNotEmpty()) error = "请减少勾选数量或分轮处理"
                if (operation == SystemOperation.RESTORE) finishRestore() else verify()
            }
            null -> verify() // 无流程（重建后守卫路径之外的兜底）→ 回查既成事实
        }
    }

    // 冷启动恢复用 MATCH_INCLUDE（快照项可能已在回收站）；正常流程排除已入回收站项。
    LaunchedEffect(markedIds) {
        if (initialPhase != null && initialSnapshot.isNotEmpty()) return@LaunchedEffect
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

    // 重建守卫：CONFIRMING 相位但 flow 已丢（旋转/重建）→ 转 VERIFYING 回查既成事实，绝不重发弹窗
    // （顺带修复历史边缘：此前旋转后批次虽在 rememberSaveable，但无任何路径重新发批，会卡死 CONFIRMING）。
    LaunchedEffect(stage, flow) {
        if (stage == PanelStage.CONFIRMING && flow == null && snapshot.isNotEmpty()) verify()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        outgoing = null
        if (stage != PanelStage.CONFIRMING) return@rememberLauncherForActivityResult
        // Deny 后不能继续请求下一批；但不把 resultCode 当成成功判定，仍必须回查。
        if (result.resultCode != Activity.RESULT_OK) {
            if (operation == SystemOperation.RESTORE) finishRestore() else verify()
        } else {
            flow?.advance()
            if (flow?.finished == false) launchCurrentBatch() else {
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

    // Full success is a brief receipt in the card stream. Exceptions retain a review surface.
    LaunchedEffect(stage, results) {
        if (stage == PanelStage.RESULT && operation == SystemOperation.DELETE &&
            onCompleted != null && canContinueAfterSettlement(results, snapshot.size, deleteMode, error)) {
            pendingStore.clear()
            val message = if (deleteMode == DeleteMode.TRASH) "已移入回收站 ${results.size} 项"
                else "已永久删除 ${results.size} 项，释放 ${formatSize(results.sumOf { it.item.sizeBytes })}"
            onCompleted(message, deleteMode == DeleteMode.TRASH)
        }
    }

    fun leaveResult() {
        scope.launch {
            pendingStore.clear()
            (onExitRound ?: onNextRound)()
        }
    }

    val reduced = rememberReducedMotion()
    val enterFraction = remember { Animatable(if (reduced) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }
    val closable = !savingKeep && stage != PanelStage.CONFIRMING && stage != PanelStage.VERIFYING
    var showDeleteOptions by rememberSaveable { mutableStateOf(false) }

    fun dismissAnimated() {
        if (closing || !closable) return
        closing = true
        scope.launch {
            enterFraction.animateTo(0f, if (reduced) snap() else Daylight.drawer())
            if (stage == PanelStage.RESULT) {
                pendingStore.clear()
                (onExitRound ?: onNextRound)()
            } else onDismiss()
        }
    }

    LaunchedEffect(Unit) { enterFraction.animateTo(1f, if (reduced) snap() else Daylight.drawer()) }
    BackHandler {
        if (previewId != null) previewId = null else if (closable) dismissAnimated()
    }

    val confirmAction: () -> Unit = {
        if (!savingKeep && stage == PanelStage.REVIEW) {
            savingKeep = true
            val kept = available.filterNot { it.id in chosen }.map { it.id }.toSet()
            val selected = available.filter { it.id in chosen }
            val mode = if (permDelete && permanentAllowed) DeleteMode.PERMANENT else DeleteMode.TRASH
            scope.launch {
                try {
                    onKeep(kept)
                    deleteMode = mode
                    snapshot = selected
                    flow = BatchFlow(snapshot, BATCH_CAP)
                    operation = SystemOperation.DELETE
                    error = null
                    stage = PanelStage.CONFIRMING
                    pendingStore.save(PendingSettlement(snapshot, deleteMode, 0, ceil(snapshot.size / BATCH_CAP.toDouble()).toInt()))
                    haptics.confirm()
                    launchCurrentBatch()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "记录未能保存，请重试"
                    stage = PanelStage.REVIEW
                } finally { savingKeep = false }
            }
        }
    }

    // Capture the Activity's insets before entering the Dialog's separate window.
    // Some device Dialog windows report zero navigation insets despite drawing behind navigation.
    val hostSafeArea = WindowInsets.safeDrawing.union(WindowInsets.mandatorySystemGestures).asPaddingValues()
    val direction = LocalLayoutDirection.current
    Dialog(
        onDismissRequest = { if (previewId != null) previewId = null else dismissAnimated() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = closable,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogSafeArea = WindowInsets.safeDrawing.union(WindowInsets.mandatorySystemGestures).asPaddingValues()
        val safePadding = PaddingValues(
            start = maxOf(hostSafeArea.calculateStartPadding(direction), dialogSafeArea.calculateStartPadding(direction)),
            top = maxOf(hostSafeArea.calculateTopPadding(), dialogSafeArea.calculateTopPadding()),
            end = maxOf(hostSafeArea.calculateEndPadding(direction), dialogSafeArea.calculateEndPadding(direction)),
            bottom = maxOf(hostSafeArea.calculateBottomPadding(), dialogSafeArea.calculateBottomPadding(), 24.dp),
        )
        val context = LocalContext.current
        val imageLoader = remember(context) {
            ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build()
        }
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { translationY = (1f - enterFraction.value) * size.height }
                    .background(Daylight.canvas)
                    .then(if (previewId != null) Modifier.clearAndSetSemantics {} else Modifier)
                    .padding(safePadding),
            ) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (closable) TextActionButton(
                        text = if (stage == PanelStage.RESULT) "继续整理" else "‹ 返回",
                        onClick = { dismissAnimated() },
                    ) else Spacer(Modifier.width(16.dp))
                    Spacer(Modifier.weight(1f))
                    if (stage == PanelStage.REVIEW && available.isNotEmpty() && !savingKeep) TextActionButton(
                        text = if (chosen.isEmpty()) "全选" else "全不选",
                        onClick = {
                            haptics.tap()
                            chosen = if (chosen.isEmpty()) available.map { it.id }.toSet() else emptySet()
                        },
                    )
                }
                when (stage) {
                    PanelStage.LOADING -> LoadingContent("正在准备待删除项…")
                    PanelStage.REVIEW -> {
                        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
                            Text(
                                if (available.isEmpty()) "没有待删除项" else "确认待删除的项目",
                                style = MaterialTheme.typography.headlineSmall, color = Daylight.ink,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "本轮浏览 $totalInRound 项，标记 ${available.size} 项",
                                style = MaterialTheme.typography.bodyMedium, color = Daylight.inkSub,
                            )
                            Spacer(Modifier.height(20.dp))
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(96.dp),
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 16.dp),
                            ) {
                                gridItems(available, key = { it.id }) { item ->
                                    val checked = item.id in chosen
                                    Box(
                                        Modifier.aspectRatio(1f).semantics { contentDescription = "查看 ${item.displayName}" }
                                            .clip(RoundedCornerShape(Daylight.radiusMd))
                                            .background(Daylight.parchment)
                                            .daylightRowClickable(enabled = !savingKeep) {
                                                haptics.tap()
                                                previewId = item.id
                                            },
                                    ) {
                                        AsyncImage(
                                            model = item.uri, imageLoader = imageLoader,
                                            contentDescription = "${item.displayName}，${if (checked) "已选择删除" else "保留"}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize().alpha(if (checked) 1f else 0.45f),
                                        )
                                        Box(
                                            Modifier.align(Alignment.TopEnd).size(48.dp)
                                                .semantics { contentDescription = "${item.displayName}，待删除选择" }
                                                .toggleable(value = checked, enabled = !savingKeep, role = Role.Checkbox) {
                                                    haptics.tap()
                                                    chosen = if (it) chosen + item.id else chosen - item.id
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Box(Modifier.clip(RoundedCornerShape(Daylight.pillRadius))
                                                .background(Daylight.canvas).padding(2.dp)) {
                                                CircleCheck(checked = checked)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        ) {
                            // Only secondary options scroll; the primary action stays outside the viewport.
                            Column(
                                Modifier.fillMaxWidth().heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                error?.let { Text(it, color = Daylight.danger, style = MaterialTheme.typography.bodyMedium) }
                                if (permanentAllowed && chosen.isNotEmpty()) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (permDelete) "永久删除" else "移入回收站，可找回",
                                            Modifier.weight(1f), color = if (permDelete) Daylight.danger else Daylight.inkSub,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        TextActionButton(
                                            text = if (showDeleteOptions) "收起" else "更改",
                                            onClick = { showDeleteOptions = !showDeleteOptions },
                                        )
                                    }
                                    if (showDeleteOptions) Row(
                                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("立即永久删除", Modifier.weight(1f), color = Daylight.ink)
                                        Switch(
                                            enabled = !savingKeep, checked = permDelete, onCheckedChange = { haptics.tap(); permDelete = it },
                                            thumbContent = {},
                                            colors = SwitchDefaults.colors(
                                                checkedTrackColor = Daylight.danger, checkedThumbColor = Daylight.onPrimary,
                                                uncheckedTrackColor = Daylight.hairline, uncheckedThumbColor = Daylight.canvas,
                                            ),
                                        )
                                    }
                                }
                                if (permDelete) {
                                    Text(
                                        if (chosen.size > PERM_DELETE_CAP) "永久删除最多 $PERM_DELETE_CAP 项，请减少勾选或改用回收站"
                                        else "永久删除不可恢复",
                                        color = Daylight.danger, style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            when {
                                chosen.isEmpty() -> PillButton(
                                    text = if (savingKeep) "正在保存…" else "保留全部，继续整理",
                                    enabled = !savingKeep,
                                    onClick = {
                                        if (!savingKeep) {
                                            savingKeep = true
                                            scope.launch {
                                                try {
                                                    onKeep(available.map { it.id }.toSet())
                                                    (onExitRound ?: onNextRound)()
                                                } catch (e: kotlinx.coroutines.CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    error = "保留记录未能保存，请重试"
                                                } finally { savingKeep = false }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                permDelete -> DangerPillButton(
                                    text = "永久删除 ${chosen.size} 项",
                                    onClick = confirmAction, enabled = !savingKeep && chosen.size <= PERM_DELETE_CAP,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                else -> PillButton(
                                    text = "移入回收站 ${chosen.size} 项 · ${formatSize(available.filter { it.id in chosen }.sumOf { it.sizeBytes })}",
                                    onClick = confirmAction, enabled = !savingKeep, modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    PanelStage.CONFIRMING -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val f = flow
                        LoadingContent(
                            if (operation == SystemOperation.RESTORE) "正在请求找回…"
                            else if (f != null && f.totalBatches > 1) "请确认第 ${f.index + 1}/${f.totalBatches} 批删除"
                            else "请在系统弹窗中确认",
                        )
                    }
                    PanelStage.VERIFYING -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LoadingContent("正在核对删除结果…")
                    }
                    PanelStage.RESULT -> ResultContent(
                        results = results, mode = deleteMode, error = error,
                        modifier = Modifier.weight(1f),
                        onRetry = {
                            val retry = results.filter { it.status == SettlementItemStatus.NOT_EXECUTED }.map { it.item }
                            if (retry.isNotEmpty()) {
                                flow = BatchFlow(retry, BATCH_CAP)
                                operation = SystemOperation.DELETE
                                error = null
                                stage = PanelStage.CONFIRMING
                                scope.launch {
                                    pendingStore.save(PendingSettlement(snapshot, deleteMode, 0, ceil(retry.size / BATCH_CAP.toDouble()).toInt()))
                                    launchCurrentBatch()
                                }
                            }
                        },
                        onRecheck = { error = null; verify() },
                        onRestore = {
                            val trashed = results.filter { it.status == SettlementItemStatus.TRASHED }.map { it.item }
                            if (trashed.isNotEmpty()) {
                                snapshot = trashed
                                flow = BatchFlow(trashed, BATCH_CAP)
                                operation = SystemOperation.RESTORE
                                error = null
                                stage = PanelStage.CONFIRMING
                                launchCurrentBatch()
                            }
                        },
                        onNext = { leaveResult() },
                    )
                }
            }
            previewId?.takeIf { stage == PanelStage.REVIEW }?.let { id ->
                androidx.compose.runtime.key(id) {
                    ReviewMediaPreview(
                        id = id, source = source, imageLoader = imageLoader,
                        selected = id in chosen,
                        onToggle = {
                            haptics.tap()
                            chosen = if (id in chosen) chosen - id else chosen + id
                        },
                        onClose = { previewId = null },
                        modifier = Modifier.fillMaxSize().background(Daylight.canvas).padding(safePadding),
                    )
                }
            }
        }
    }
}

@Composable private fun LoadingContent(text: String) = Row(
    Modifier.fillMaxWidth().padding(24.dp),
    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
) {
    Spinner()
    Text(text, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge, color = Daylight.inkStrong)
}

@Composable private fun ResultContent(
    results: List<SettlementItemResult>, mode: DeleteMode, error: String?,
    onRetry: () -> Unit, onRestore: () -> Unit, onRecheck: () -> Unit, onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = results.count { it.status == SettlementItemStatus.NOT_EXECUTED }
    val unknown = results.count { it.status == SettlementItemStatus.UNKNOWN }
    val trashed = results.count { it.status == SettlementItemStatus.TRASHED }
    val deleted = results.count { it.status == SettlementItemStatus.DELETED }
    val differences = results.filter {
        it.status == SettlementItemStatus.NOT_EXECUTED || it.status == SettlementItemStatus.UNKNOWN
    }
    Column(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    if (differences.isNotEmpty()) "还有 ${differences.size} 项需要处理" else "本轮整理完成",
                    style = MaterialTheme.typography.headlineSmall, color = Daylight.ink,
                )
            }
            item {
                Text(
                    listOfNotNull(
                        if (trashed > 0) "已移入回收站 $trashed 项" else null,
                        if (deleted > 0) "已永久删除 $deleted 项" else null,
                        if (pending > 0) "未执行 $pending 项" else null,
                        if (unknown > 0) "待核对 $unknown 项" else null,
                    ).joinToString("，"),
                    style = MaterialTheme.typography.bodyLarge, color = Daylight.inkSub,
                )
            }
            if (mode == DeleteMode.PERMANENT && deleted > 0) item {
                CountUpBytes(results.filter { it.status == SettlementItemStatus.DELETED }.sumOf { it.item.sizeBytes })
            }
            if (trashed > 0) item {
                Text("已移入回收站的项目可在本 App 找回。", style = MaterialTheme.typography.bodyMedium, color = Daylight.inkSub)
            }
            error?.let { item { Text(it, color = Daylight.danger, style = MaterialTheme.typography.bodyMedium) } }
            items(differences, key = { it.item.id }) { result ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Daylight.radiusMd))
                        .background(Daylight.parchment).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = result.item.uri, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(Daylight.radiusSm)),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            result.item.displayName.ifBlank { "未命名媒体" },
                            color = Daylight.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(statusLabel(result.status), color = Daylight.danger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            if (pending > 0) {
                PearlButton(onClick = onRetry, text = "重试 $pending 项", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            if (unknown > 0) {
                PearlButton(onClick = onRecheck, text = "重新核对", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            PillButton(onClick = onNext, text = "继续整理", modifier = Modifier.fillMaxWidth())
            if (trashed > 0) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextActionButton(text = "找回本次删除项", onClick = onRestore)
            }
            Spacer(Modifier.height(16.dp))
        }
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
    // 锁定终值单位只滚数值：滚动期间不穿越 KB→MB→GB 边界，单位后缀不跳变（峰值时刻不抖）
    Text(
        "已释放 ${formatSizeInUnitOf(bytes, (bytes * progress.value).toLong())}",
        color = Daylight.inkSub,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 以 target 的量级单位格式化 value（CountUpBytes 滚动锁单位专用；进度 0 时亦落目标单位）。 */
private fun formatSizeInUnitOf(target: Long, value: Long): String = when {
    target < 1024L * 1024L -> "%.1f KB".format(value / 1024.0)
    target < 1024L * 1024L * 1024L -> "%.1f MB".format(value / (1024.0 * 1024.0))
    else -> "%.2f GB".format(value / (1024.0 * 1024.0 * 1024.0))
}

private fun statusLabel(status: SettlementItemStatus): String = when (status) {
    SettlementItemStatus.TRASHED -> "已入回收站"
    SettlementItemStatus.DELETED -> "已永久删除"
    SettlementItemStatus.NOT_EXECUTED -> "未执行"
    SettlementItemStatus.UNKNOWN -> "结果未知，可手动回查"
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "大小未知"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
