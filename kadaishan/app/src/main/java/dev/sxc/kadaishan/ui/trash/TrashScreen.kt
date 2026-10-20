package dev.sxc.kadaishan.ui.trash

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.sxc.kadaishan.data.MediaDeleter
import dev.sxc.kadaishan.domain.settle.BatchFlow
import dev.sxc.kadaishan.domain.settle.DegradeResult
import dev.sxc.kadaishan.model.DeleteMode
import dev.sxc.kadaishan.ui.kit.AlertAction
import dev.sxc.kadaishan.ui.kit.CircleCheck
import dev.sxc.kadaishan.ui.kit.DangerPillButton
import dev.sxc.kadaishan.ui.kit.Daylight
import dev.sxc.kadaishan.ui.kit.DaylightAlert
import dev.sxc.kadaishan.ui.kit.GroupCard
import dev.sxc.kadaishan.ui.kit.NavTitleBar
import dev.sxc.kadaishan.ui.kit.PearlButton
import dev.sxc.kadaishan.ui.kit.PillButton
import dev.sxc.kadaishan.ui.kit.Spinner
import dev.sxc.kadaishan.ui.kit.TextActionButton
import dev.sxc.kadaishan.ui.kit.daylightBackground
import dev.sxc.kadaishan.ui.kit.daylightRowClickable
import dev.sxc.kadaishan.ui.kit.rememberHaptics
import dev.sxc.kadaishan.model.TrashEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** 找回批次上限（与结算页同一口径：createTrashRequest 分批 ≤50，避免单批过大被系统拒绝）。 */
private const val RESTORE_BATCH_CAP = 50

/**
 * 常驻回收站页：MediaStore 回收站状态的只读视图 + 恢复发起（不落任何文件副本，零联网）。
 * 列表含全部 IS_TRASHED=1 项（含其他 App 移入的）；找回只走 createTrashRequest(uris, false) 分批发起，
 * 清空回收站 = createDeleteRequest(uris) 分批发起（切批前验活过滤，PRD v1.6），
 * 结果一律由 recheck 回查裁决（清空按三态：已永久删除/未执行/结果未知），失效/拒绝项计数提示、不崩。
 */
@Composable
fun TrashScreen(
    deleter: MediaDeleter,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var entries by remember { mutableStateOf<List<TrashEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 批次状态（找回/清空共用骨架：批次推进 = BatchFlow 单一纯状态机，结果由回查裁决）。
    // M1：launchedIds/outgoingIds/working 等流水线关键态 rememberSaveable（重建不重复发批）；
    // flow 刻意 remember 不落盘——重建后由守卫直接 finishFlow（回查既成事实），禁止重发系统弹窗。
    val longListSaver = listSaver<List<Long>, Long>(save = { it }, restore = { it })
    var flow by remember { mutableStateOf<BatchFlow?>(null) }
    var outgoing by remember { mutableStateOf<IntentSender?>(null) }
    var outgoingIds by rememberSaveable(stateSaver = longListSaver) { mutableStateOf(emptyList()) }
    var launchedIds by rememberSaveable(stateSaver = longListSaver) { mutableStateOf(emptyList()) }
    var working by rememberSaveable { mutableStateOf(false) }
    var permFlow by rememberSaveable { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var messageAlert by rememberSaveable { mutableStateOf(false) }
    // 首次加载默认全选（与结算页 chosen 默认一致）；刷新时保留当前勾选并剔除已消失项
    var initialized by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val rows = try {
                deleter.listTrashed()
            } catch (e: Exception) {
                loadError = "读取回收站失败：${e.message ?: e.javaClass.simpleName}"
                emptyList()
            }
            entries = rows
            val alive = rows.map { it.item.id }.toSet()
            chosen = if (initialized) chosen.intersect(alive) else alive
            initialized = true
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun finishFlow() {
        scope.launch {
            // flow 可能已随重建丢失（守卫路径）：失败计数按 0 兜底，结论仍以回查为唯一判据
            val failed = flow?.failedCount ?: 0
            if (permFlow) {
                // 三态（PRD v1.6 / 架构 §7 口径）：行缺失 = 已永久删除；行仍在 = 未执行；回查失败 = 结果未知
                val rows = try {
                    deleter.recheck(launchedIds)
                } catch (_: Exception) {
                    null
                }
                val (deleted, unexecuted, unknown) = if (rows == null) {
                    Triple(0, failed, launchedIds.size)
                } else {
                    val present = rows.map { it.id }.toSet()
                    Triple(
                        launchedIds.count { it !in present },
                        failed + launchedIds.count { it in present },
                        0,
                    )
                }
                // M9：0 成功不得谎报「清空完成」（PRD §5.5 禁笼统成功）
                val doneWord = if (deleted > 0) "清空完成" else "已中止"
                message = "$doneWord：已永久删除 $deleted · 未执行 $unexecuted · 结果未知 $unknown"
                messageAlert = deleted == 0 && unexecuted + unknown > 0
            } else {
                val restored = try {
                    deleter.recheck(launchedIds).count { !it.isTrashed }
                } catch (_: Exception) {
                    0
                }
                val requested = launchedIds.size
                // 找回结果独立态：直给已找回/未恢复总数（未恢复 = 含未执行/失效，口径同 PRD §4.7）
                message = "已找回 $restored 项 · 未恢复 ${requested - restored} 项"
                messageAlert = restored < requested
            }
            working = false
            flow = null
            launchedIds = emptyList()
            outgoingIds = emptyList()
            permFlow = false
            refresh()
        }
    }

    // M1 守卫：重建后 working 残留但 flow 已丢 → 直接回查收尾（回查既成事实，不重复发批）
    LaunchedEffect(working, flow) {
        if (working && flow == null) {
            if (launchedIds.isNotEmpty()) finishFlow() else {
                working = false
                permFlow = false
            }
        }
    }

    fun launchCurrentBatch() {
        scope.launch {
            val f = flow
            val batch = f?.current() ?: run {
                finishFlow()
                return@launch
            }
            // M13：找回/清空统一在发批前验活过滤（G0 实测 create*Request 构建期遇失效 URI 整批拒绝）
            val alive = try {
                deleter.recheck(batch.map { it.id }).map { it.id }.toSet()
            } catch (_: Exception) {
                batch.map { it.id }.toSet()
            }
            val effective = batch.filter { it.id in alive }
            f.countFailed(batch.size - effective.size)
            if (effective.isEmpty()) {
                f.advance()
                launchCurrentBatch()
                return@launch
            }
            try {
                outgoingIds = effective.map { it.id }
                outgoing = if (permFlow) {
                    deleter.createBatchIntent(effective, DeleteMode.PERMANENT)
                } else {
                    deleter.createRestoreIntent(effective)
                }
            } catch (_: Exception) {
                // M13：构建期整批拒绝（失效 URI / TLT）→ 降档切批重发；拆无可拆才计失败，不崩
                when (val result = f.degradeCurrent()) {
                    is DegradeResult.Retrying -> launchCurrentBatch()
                    is DegradeResult.Skipped -> {
                        f.countFailed(result.batch.size)
                        launchCurrentBatch()
                    }
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        outgoing = null
        if (result.resultCode == Activity.RESULT_OK) {
            launchedIds = launchedIds + outgoingIds
        } else {
            // 拒绝：本批及后续批不再发起（拒绝后重试只会连续弹窗），全部计入提示
            flow?.abortRest(outgoingIds.size)
            finishFlow()
            return@rememberLauncherForActivityResult
        }
        outgoingIds = emptyList()
        flow?.advance()
        launchCurrentBatch()
    }
    LaunchedEffect(outgoing) {
        outgoing?.let {
            try {
                launcher.launch(IntentSenderRequest.Builder(it).build())
            } catch (_: Exception) {
                // M13：launch 抛 TransactionTooLarge 等 → 降档重发，不崩
                outgoing = null
                outgoingIds = emptyList()
                when (val result = flow?.degradeCurrent()) {
                    is DegradeResult.Retrying -> launchCurrentBatch()
                    is DegradeResult.Skipped -> {
                        flow?.countFailed(result.batch.size)
                        launchCurrentBatch()
                    }
                    null -> finishFlow() // 无流程（重建边缘）→ 回查既成事实
                }
            }
        }
    }

    val selected = entries.filter { it.item.id in chosen }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .daylightBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        NavTitleBar(title = "回收站", onBack = onBack)

        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spinner()
                Text(
                    "正在读取回收站…",
                    Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Daylight.inkStrong,
                )
            }
        } else {
            // 错误态：居中 + 重试出口（不留无路可走的红字贴边）
            loadError?.let { err ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Daylight.danger,
                    )
                    Spacer(Modifier.height(12.dp))
                    PearlButton(text = "重试", onClick = { refresh() })
                }
            }
            if (entries.isEmpty()) {
                if (loadError == null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "回收站为空",
                            style = MaterialTheme.typography.titleLarge,
                            color = Daylight.ink,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "删除的项目会显示在这里，保留期以每项标注为准。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Daylight.inkSub,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "共 ${entries.size} 项 · 已选 ${chosen.size} 项",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Daylight.inkSub,
                    )
                    TextActionButton(
                        text = "全选",
                        enabled = !working,
                        onClick = { chosen = entries.map { it.item.id }.toSet() },
                    )
                    TextActionButton(
                        text = "全不选",
                        enabled = !working && chosen.isNotEmpty(),
                        onClick = { chosen = emptySet() },
                    )
                }
                Spacer(Modifier.height(4.dp))
                GroupCard(modifier = Modifier.weight(1f)) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(entries, key = { _, entry -> entry.item.id }) { index, entry ->
                            val item = entry.item
                            val checked = item.id in chosen
                            if (index > 0) {
                                HorizontalDivider(
                                    color = Daylight.dividerSoft,
                                    thickness = 1.dp,
                                    // iOS 列表分隔线对齐文本起点：14(行距)+24(勾圈)+12+52(缩略图)+12 = 114
                                    modifier = Modifier.padding(start = 114.dp),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 60.dp)
                                    .daylightRowClickable(enabled = !working) {
                                        haptics.tap()
                                        chosen = if (checked) chosen - item.id else chosen + item.id
                                    }
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircleCheck(checked = checked, enabled = !working)
                                Spacer(Modifier.width(12.dp))
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = item.displayName.ifBlank { "未命名媒体" },
                                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(Daylight.radiusSm)),
                                )
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(
                                        item.displayName.ifBlank { "未命名媒体" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Daylight.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val expires = entry.dateExpiresSec?.let { formatDate(it) }
                                    Text(
                                        text = "${formatSize(item.sizeBytes)} · " + if (expires == null) "到期时间未知" else "保留至 $expires",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Daylight.inkSub,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            message?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 8.dp),
                    color = if (messageAlert) Daylight.danger else Daylight.inkStrong,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (working) {
                Text(
                    "等待系统确认… 第 ${(flow?.index ?: 0) + 1}/${flow?.totalBatches ?: 0} 批",
                    modifier = Modifier.padding(top = 8.dp),
                    color = Daylight.inkSub,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = if (selected.isEmpty()) "找回所选 0 项" else "找回所选 ${selected.size} 项",
                onClick = {
                    message = null
                    loadError = null
                    flow = BatchFlow(selected.map { it.item }, RESTORE_BATCH_CAP)
                    launchedIds = emptyList()
                    working = true
                    launchCurrentBatch()
                },
                enabled = !working && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            DangerPillButton(
                text = "清空回收站（${entries.size} 项）",
                onClick = { clearConfirm = true },
                enabled = !working && entries.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    // 清空回收站二次确认（iOS 居中 alert）：红色警示 + N 项/容量/不可恢复口径，与勾选态无关
    if (clearConfirm) {
        DaylightAlert(
            onDismiss = { clearConfirm = false },
            title = "清空回收站",
            message = "永久删除 ${entries.size} 项 · ${formatSize(entries.sumOf { it.item.sizeBytes })} · 不可恢复" +
                "（含其他应用移入回收站的项）",
            actions = listOf(
                AlertAction(text = "取消", onClick = { clearConfirm = false }),
                AlertAction(
                    text = "永久删除",
                    color = Daylight.danger,
                    weight = FontWeight.SemiBold,
                    onClick = {
                        clearConfirm = false
                        message = null
                        loadError = null
                        permFlow = true
                        flow = BatchFlow(entries.map { it.item }, RESTORE_BATCH_CAP)
                        launchedIds = emptyList()
                        outgoingIds = emptyList()
                        working = true
                        launchCurrentBatch()
                    },
                ),
            ),
        )
    }
}

/** dateExpiresSec（epoch 秒）→ yyyy-MM-dd（与结算页 ResultContent 同口径）。 */
private fun formatDate(epochSec: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochSec * 1000))

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "大小未知"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
