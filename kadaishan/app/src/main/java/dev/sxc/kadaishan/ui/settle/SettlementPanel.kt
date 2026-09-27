package dev.sxc.kadaishan.ui.settle

import android.app.Activity
import android.content.IntentSender
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.sxc.kadaishan.data.MediaDeleter
import dev.sxc.kadaishan.data.PendingSettlementRepository
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.domain.settle.verifySettlement
import dev.sxc.kadaishan.model.DeleteMode
import dev.sxc.kadaishan.model.PendingSettlement
import dev.sxc.kadaishan.model.SettlementItemResult
import dev.sxc.kadaishan.model.SettlementItemStatus
import dev.sxc.kadaishan.model.SnapItem
import kotlinx.coroutines.launch
import kotlin.math.ceil

private enum class PanelStage { LOADING, REVIEW, CONFIRMING, VERIFYING, RESULT }
private enum class SystemOperation { DELETE, RESTORE }

/**
 * 卡片轮末的真实结算面。弹窗回调只负责推进/停止批次，最终结果一律由 recheck 裁决。
 * 这也是暂存快照的唯一写入点，未结算的手势标记不会持久化。
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
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var stage by remember(markedIds) { mutableStateOf(PanelStage.LOADING) }
    var available by remember(markedIds) { mutableStateOf<List<SnapItem>>(emptyList()) }
    var chosen by remember(markedIds) { mutableStateOf<Set<Long>>(markedIds.toSet()) }
    var snapshot by remember { mutableStateOf<List<SnapItem>>(emptyList()) }
    var batches by remember { mutableStateOf<List<List<SnapItem>>>(emptyList()) }
    var batchIndex by remember { mutableStateOf(0) }
    var outgoing by remember { mutableStateOf<IntentSender?>(null) }
    var results by remember { mutableStateOf<List<SettlementItemResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf(SystemOperation.DELETE) }

    LaunchedEffect(markedIds) {
        available = source.queryByIds(markedIds, MediaStore.MATCH_EXCLUDE).map {
            SnapItem(it.id, it.uri, it.displayName, it.sizeBytes)
        }
        chosen = available.map { it.id }.toSet()
        stage = PanelStage.REVIEW
    }

    fun verify() {
        stage = PanelStage.VERIFYING
        scope.launch {
            results = try {
                verifySettlement(snapshot, DeleteMode.TRASH, deleter.recheck(snapshot.map { it.id }), true)
            } catch (_: Exception) {
                snapshot.map { SettlementItemResult(it, SettlementItemStatus.UNKNOWN) }
            }
            stage = PanelStage.RESULT
        }
    }

    fun launchCurrentBatch() {
        val batch = batches.getOrNull(batchIndex) ?: run { verify(); return }
        try {
            outgoing = when (operation) {
                SystemOperation.DELETE -> deleter.createBatchIntent(batch, DeleteMode.TRASH)
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
            error = if (restored == snapshot.size) "已回查确认找回 $restored 项。" else "找回请求已完成；请回到原目录确认 $restored/${snapshot.size} 项。"
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
    LaunchedEffect(outgoing) { outgoing?.let { launcher.launch(IntentSenderRequest.Builder(it).build()) } }

    ModalBottomSheet(
        onDismissRequest = {
            if (stage != PanelStage.CONFIRMING && stage != PanelStage.VERIFYING) onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            when (stage) {
                PanelStage.LOADING -> LoadingContent("正在核对本轮标记…")
                PanelStage.REVIEW -> {
                    Text("确认本轮删除", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("本轮共 $totalInRound 项 · 你标记了 ${markedIds.size} 项 · 勾选了 ${chosen.size} 项")
                    Text("默认移入系统回收站，可在本 App 内找回。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { chosen = emptySet() }) { Text("一键全部不选") }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(available, key = { it.id }) { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = item.id in chosen,
                                    onCheckedChange = { checked ->
                                        chosen = if (checked) chosen + item.id else chosen - item.id
                                    },
                                )
                                AsyncImage(model = item.uri, contentDescription = null, modifier = Modifier.size(48.dp))
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text(item.displayName.ifBlank { "未命名媒体" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatSize(item.sizeBytes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = chosen.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            snapshot = available.filter { it.id in chosen }
                            batches = snapshot.chunked(50)
                            batchIndex = 0
                            operation = SystemOperation.DELETE
                            error = null
                            stage = PanelStage.CONFIRMING
                            scope.launch {
                                pendingStore.save(PendingSettlement(snapshot, DeleteMode.TRASH, 0, ceil(snapshot.size / 50.0).toInt()))
                                launchCurrentBatch()
                            }
                        },
                    ) { Text("移入回收站 ${chosen.size} 项 · ${formatSize(available.filter { it.id in chosen }.sumOf { it.sizeBytes })}") }
                }
                PanelStage.CONFIRMING -> LoadingContent(
                    "${if (operation == SystemOperation.RESTORE) "正在请求找回" else "等待系统确认"}… 第 ${batchIndex + 1}/${batches.size} 批",
                )
                PanelStage.VERIFYING -> LoadingContent("正在回查实际结果…")
                PanelStage.RESULT -> ResultContent(
                    results = results,
                    error = error,
                    onRetry = {
                        val retry = results.filter { it.status == SettlementItemStatus.NOT_EXECUTED }.map { it.item }
                        if (retry.isNotEmpty()) {
                            snapshot = retry
                            batches = retry.chunked(50)
                            batchIndex = 0
                            operation = SystemOperation.DELETE
                            stage = PanelStage.CONFIRMING
                            scope.launch {
                                pendingStore.save(PendingSettlement(retry, DeleteMode.TRASH, 0, ceil(retry.size / 50.0).toInt()))
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
                    onNext = {
                        scope.launch { pendingStore.clear() }
                        onNextRound()
                    },
                    onClose = {
                        scope.launch { pendingStore.clear() }
                        onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable private fun LoadingContent(text: String) = Row(
    Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
) { CircularProgressIndicator(Modifier.size(24.dp)); Text(text, Modifier.padding(start = 12.dp)) }

@Composable private fun ResultContent(
    results: List<SettlementItemResult>, error: String?, onRetry: () -> Unit, onRestore: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
) {
    val trashed = results.count { it.status == SettlementItemStatus.TRASHED }
    val deleted = results.count { it.status == SettlementItemStatus.DELETED }
    val pending = results.count { it.status == SettlementItemStatus.NOT_EXECUTED }
    val unknown = results.count { it.status == SettlementItemStatus.UNKNOWN }
    Text("处理结果", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("已入回收站 $trashed · 已永久删除 $deleted · 未执行 $pending · 结果未知 $unknown")
    Text("系统相册可能不会显示由本 App 移入回收站的项目；找回请使用本 App 入口。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    results.filter { it.status != SettlementItemStatus.TRASHED && it.status != SettlementItemStatus.DELETED }.take(4).forEach {
        Text("• ${it.item.displayName.ifBlank { "未命名媒体" }}：${statusLabel(it.status)}")
    }
    Spacer(Modifier.height(12.dp))
    if (pending > 0) OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试未执行项") }
    if (trashed > 0) OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("找回本次删除项") }
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("继续下一轮") }
    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("返回") }
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
