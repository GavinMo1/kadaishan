package dev.sxc.kadaishan.ui.cultivation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sxc.kadaishan.data.CultivationRepository
import dev.sxc.kadaishan.domain.cultivation.*
import dev.sxc.kadaishan.ui.kit.*
import kotlinx.coroutines.launch

internal fun xpText(tenths: Long): String = "${tenths / 10}.${tenths % 10}"

@Composable
internal fun RefinementPanel(s: CultivationState, repo: CultivationRepository, busy: Boolean, onFeedback: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var budget by remember { mutableStateOf<Long?>(null) }
    var quote by remember { mutableStateOf<CultivationProgression.Refinement?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    SectionLabel("灵石炼化")
    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(if (s.level >= 18) "本世修为已圆满，可培养功法或转世" else
                "${4 + s.level} 灵石换 1 修为 · 不自动消耗", color = Daylight.ink)
            Text("按当前境界定价，无每日次数。补足后仍需主动突破；最后不足 1 点的盈余完整保留。", color = Daylight.inkSub,
                style = MaterialTheme.typography.bodySmall)
            listOf(1000L to "最多花 1000 灵石", 10000L to "最多花 10000 灵石", Long.MAX_VALUE to "补足下层").forEach { (limit, label) ->
                val preview = CultivationProgression.refinement(s, limit)
                TextButton(onClick = { budget = limit; quote = preview; message = null }, enabled = !busy && preview.cost > 0) { Text(label) }
            }
            if (s.level < 18 && s.cultivationTenths >= CultivationEngine.nextCost(s.level) * 10)
                Text("修为已足，先突破或挑战守关妖兽", color = Daylight.inkSub)
        }
    }
    quote?.let { q ->
        AlertDialog(onDismissRequest = { if (!busy) quote = null }, title = { Text("确认炼化灵石") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("消耗 ${q.cost} 灵石，获得 ${xpText(q.xpTenths)} 修为。\n余额 ${q.expected.stones} → ${q.expected.stones - q.cost}")
                val resulting = CultivationEngine.add(q.expected.xp, q.xpTenths)
                Text("修为 ${xpText(q.expected.xp)} → ${xpText(resulting)}")
                val excess = resulting - CultivationEngine.nextCost(q.expected.level) * 10
                if (excess > 0) Text("突破后保留 ${xpText(excess)} 修为，不会丢弃。")
                message?.let { Text(it, color = Daylight.danger) }
            }
        }, confirmButton = {
            TextButton(onClick = { scope.launch {
                val result = repo.update { CultivationProgression.refine(it, q) }
                if (result != null) {
                    quote = null; message = null
                    onFeedback("炼化完成：消耗 ${q.cost} 灵石，获得 ${xpText(q.xpTenths)} 修为。\n尚未自动突破，可返回查看当前境界。")
                }
                else {
                    repo.state.value?.takeIf { it.version >= 3 }?.let { quote = CultivationProgression.refinement(it, budget ?: 0) }
                    message = "${repo.error.value ?: "暂未保存"}。预览已更新，请检查后重试。"
                }
            } }, enabled = !busy && q.cost > 0) { Text("确认花费 ${q.cost}") }
        }, dismissButton = { TextButton(onClick = { quote = null }, enabled = !busy) { Text("取消") } })
    }
}

@Composable
internal fun ManualPanel(s: CultivationState, repo: CultivationRepository, busy: Boolean, onFeedback: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var quote by remember { mutableStateOf<CultivationProgression.Training?>(null) }
    var all by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    SectionLabel("功法 · 一个主修位")
    Text("通用心得 ${s.insight} · 已解锁 ${s.manualLevels.size}/6", modifier = Modifier.padding(horizontal = 20.dp),
        color = Daylight.ink, style = MaterialTheme.typography.titleMedium)
    Text("普通战胜利 1% 掉落，最多 200 胜必掉。优先获得未解锁功法；集齐后重复掉落转 1 点心得。离线照常掉落，等级与心得转世全保留。",
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Daylight.inkSub,
        style = MaterialTheme.typography.bodySmall)
    Practice.entries.forEach { p ->
        val n = CultivationProgression.rank(s, p)
        SectionLabel("${p.label} · ${if (n == 0) "未获得" else "Lv$n${if (s.practice == p) " · 主修中" else ""}"}")
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(CultivationProgression.description(p, n), color = Daylight.ink)
                if (n in 1..19) {
                    Text("下级：${CultivationProgression.description(p, n + 1)}", color = Daylight.inkSub)
                    Text("需 ${500L * (n + 1) * (n + 1)} 灵石 + $n 心得", color = Daylight.inkSub)
                }
                if (n > 0) {
                    TextButton(onClick = { scope.launch {
                        repo.update { CultivationProgression.select(it, p) }?.let { onFeedback(comparison(it.first, it.second)) }
                    } }, enabled = !busy && s.practice != p) { Text(if (s.practice == p) "正在主修" else "免费切换主修") }
                    if (n < 20) for (batch in listOf(false, true)) {
                        val preview = CultivationProgression.training(s, p, batch)
                        TextButton(onClick = { all = batch; quote = preview; message = null }, enabled = !busy && preview.cost > 0) {
                            Text(if (batch) "培养至可负担等级" else "培养一级")
                        }
                    } else Text("已修至 Lv20", color = Daylight.inkSub)
                } else Text("继续挂机打怪，有机会获得此功法", color = Daylight.inkSub)
            }
        }
    }
    quote?.let { q ->
        val before = s.copy(manualLevels = q.expected.levels, practice = q.expected.practice)
        val after = before.copy(manualLevels = before.manualLevels + (q.practice to q.target))
        AlertDialog(onDismissRequest = { if (!busy) quote = null }, title = { Text("培养${q.practice.label}") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Lv${q.expected.levels[q.practice]} → Lv${q.target}\n消耗 ${q.cost} 灵石 + ${q.insight} 心得")
                Text("灵石 ${q.expected.stones} → ${q.expected.stones - q.cost}\n心得 ${q.expected.insight} → ${q.expected.insight - q.insight}")
                Text(CultivationProgression.description(q.practice, q.target))
                Text(if (q.expected.practice == q.practice) comparison(before, after) else "当前未主修，培养后切换主修才生效。", color = Daylight.inkSub)
                Text("功法等级永久继承，确定成功。")
                message?.let { Text(it, color = Daylight.danger) }
            }
        }, confirmButton = { TextButton(onClick = { scope.launch {
            val result = repo.update { CultivationProgression.train(it, q) }
            if (result != null) {
                quote = null
                onFeedback("${q.practice.label}已提升至 Lv${q.target}\n" + comparison(result.first, result.second))
            } else {
                repo.state.value?.takeIf { it.version >= 3 }?.let { quote = CultivationProgression.training(it, q.practice, all) }
                message = "${repo.error.value ?: "暂未保存"}。预览已更新，请检查后重试。"
            }
        } }, enabled = !busy && q.cost > 0) { Text("确认培养") } }, dismissButton = {
            TextButton(onClick = { quote = null }, enabled = !busy) { Text("取消") }
        })
    }
}
