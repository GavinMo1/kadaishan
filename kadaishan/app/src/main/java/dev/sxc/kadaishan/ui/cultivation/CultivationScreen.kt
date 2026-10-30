package dev.sxc.kadaishan.ui.cultivation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.sxc.kadaishan.data.CultivationRepository
import dev.sxc.kadaishan.domain.cultivation.*
import dev.sxc.kadaishan.ui.kit.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The game owns its own page; no game drawing or input is added to photo cards. */
@Composable
fun CultivationScreen(repo: CultivationRepository, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by repo.state.collectAsState()
    val error by repo.error.collectAsState()
    val busy by repo.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(repo, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { repo.refresh(); delay(10_000) }
        }
    }
    var tab by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var bossReplay by remember { mutableStateOf<Battle?>(null) }
    var equipPreview by remember { mutableStateOf<Gear?>(null) }
    var rebirthPreview by remember { mutableStateOf<CultivationState?>(null) }
    var selectedHeirloom by remember { mutableStateOf<Long?>(null) }
    var resetConfirmation by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().daylightBackground().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        NavTitleBar(title = "掌中修仙", onBack = onBack)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            Text(it, color = Daylight.danger, modifier = Modifier.padding(20.dp))
            Row(Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { scope.launch { repo.refresh() } }, enabled = !busy) { Text("重试") }
                TextButton(onClick = { resetConfirmation = true }, enabled = !busy) { Text("单独重置修仙") }
            }
        }
        val s = state
        if (s == null) {
            Text("正在读取修仙存档…", modifier = Modifier.padding(24.dp), color = Daylight.inkSub)
        } else {
            val stats = CultivationEngine.stats(s)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(CultivationEngine.realm(s), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = Daylight.ink)
                    Text("第 ${s.world} 世 · ${s.practice.label}", color = Daylight.inkSub, style = MaterialTheme.typography.bodyMedium)
                }
                Text("攻击 ${stats.first}\n生命 ${stats.second}", color = Daylight.ink, style = MaterialTheme.typography.bodyLarge)
            }
            BattleReplay(s, CultivationEngine.battle(s, captureTrace = true))
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("${CultivationEngine.mapNames[s.map - 1]} · ${if (s.paused) "已暂停" else "自动历练中"}", style = MaterialTheme.typography.titleMedium, color = Daylight.ink)
                val canWin = CultivationEngine.battle(s).won
                Text(if (s.paused) "恢复后继续，暂停期间不积累收益" else if (canWin) "${battleSummary(CultivationEngine.battle(s))}\n预计修为 ${CultivationEngine.hourlyCultivationTenths(s) / 10}/小时 · 灵石 ${CultivationEngine.hourlyStones(s)}/小时" else "暂时打不过普通怪，仍积累修为；可回到旧图刷装", color = Daylight.inkSub, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                val next = CultivationEngine.nextCost(s.level)
                Text("修为 ${s.cultivationTenths / 10}${if (s.level < 18) " / $next" else " · 本世圆满"}    灵石 ${s.stones}", color = Daylight.ink)
                if (s.level < 18) LinearProgressIndicator(progress = { (s.cultivationTenths.toDouble() / 10 / next).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { scope.launch { repo.update { CultivationEngine.breakthrough(it, true) } } }, enabled = !busy && s.level < 18 && s.cultivationTenths / 10 >= next && (s.level != 9 || 3 in s.bosses)) { Text("突破境界") }
                    OutlinedButton(onClick = { scope.launch { repo.setPaused(!s.paused) } }, enabled = !busy) { Text(if (s.paused) "恢复历练" else "暂停历练") }
                }
                if (s.level == 9 && 3 !in s.bosses) Text("筑基需先击败迷雾泽地守关妖兽", color = Daylight.inkSub)
            }
            if (s.report.minutes > 0) {
                SectionLabel("历练收获 · 已全部到账")
                GroupCard(Modifier.padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("有效历练 ${duration(s.report.minutes)}，胜利 ${s.report.wins} 场", style = MaterialTheme.typography.titleMedium, color = Daylight.ink)
                        Text("新历练 ${duration(s.report.minutes - s.report.legacyMinutes - s.report.bonusMinutes)} · 补给 ${duration(s.report.bonusMinutes)}", color = Daylight.inkSub)
                        if (s.report.legacyMinutes > 0) Text("升级前历练 ${duration(s.report.legacyMinutes)}（含可能的补给）", color = Daylight.inkSub)
                        Text("尝试 ${s.report.battles} 场 · 失败 ${s.report.battles - s.report.wins} 场", color = Daylight.inkSub)
                        if (s.report.minutes == Long.MAX_VALUE) Text("报告计数已达上限，后续收益仍会结算", color = Daylight.inkSub)
                        Text("修为 +${s.report.cultivationTenths / 10} · 灵石 +${s.report.stones}", color = Daylight.inkSub)
                        Text("掉落 ${s.report.drops} 件 · 自动化解 ${s.report.dissolved} 件", color = Daylight.inkSub)
                        if (s.report.bestQuality >= 0) Text("最高 ${Quality.entries[s.report.bestQuality].label}装 · 留存装备见装备页", color = qualityColor(Quality.entries[s.report.bestQuality]))
                        TextButton(onClick = { scope.launch { repo.clearReport() } }, enabled = !busy) { Text("收起报告") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("历练", "装备", "历世").forEachIndexed { index, label -> FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) }) }
            }
            when (tab) {
                0 -> {
                    SectionLabel("地图与守关")
                    GroupCard(Modifier.padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            CultivationEngine.mapNames.forEachIndexed { index, name ->
                                val t = index + 1
                                val available = t <= s.unlockedMap && s.level >= 1 + index * 3
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name, color = Daylight.ink, style = MaterialTheme.typography.bodyLarge)
                                        Text(if (t in s.bosses) "守关已通关" else "进入需第 ${1 + index * 3} 层 · 守关需第 ${3 * t} 层", color = Daylight.inkSub, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { scope.launch { repo.update { CultivationEngine.selectMap(it, t) } } }, enabled = !busy && available && s.map != t) { Text(if (s.map == t) "当前" else if (available) "前往" else "未开放") }
                                }
                            }
                            Button(onClick = { scope.launch {
                                val result = repo.challenge()
                                bossReplay = result

                            } }, enabled = !busy && s.level >= s.map * 3) { Text(if (s.map in s.bosses) "守关试招" else "挑战守关妖兽") }
                        }
                    }
                    SectionLabel("功法 · 免费切换")
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Practice.entries.forEach { p -> FilterChip(selected = s.practice == p, onClick = { scope.launch { repo.update { it.copy(practice = p) }?.let { feedback = comparison(it.first, it.second) } } }, enabled = !busy, label = { Text(p.label) }) }
                    }
                    Text("御剑：第三回合双倍伤害\n长生：每次攻击前回复 12% 最大生命", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Daylight.inkSub)
                }
                1 -> {
                    Text("每位置保留三件候选，超出自动化为灵石；穿戴与传承件受保护。换装继承该装备位的强化。", modifier = Modifier.padding(horizontal = 20.dp), color = Daylight.inkSub, style = MaterialTheme.typography.bodyMedium)
                    GearSlot.entries.forEach { slot ->
                        val enhancement = s.enhancements[slot] ?: 0
                        SectionLabel("${slot.label} · 强化 +$enhancement")
                        GroupCard(Modifier.padding(horizontal = 16.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                val items = s.gear.filter { it.slot == slot }.sortedByDescending { it.strength }
                                if (items.isEmpty()) Text("还未获得，继续打怪就有机会掉落", color = Daylight.inkSub)
                                items.forEach { g ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${g.name} · ${g.quality.label}装${if (g.id == s.heirloomId) " · 传承" else ""}", color = qualityColor(g.quality), style = MaterialTheme.typography.titleSmall)
                                            Text(gearStats(g), color = Daylight.inkSub, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        TextButton(onClick = { equipPreview = g }, enabled = !busy && s.equipped[slot] != g.id) { Text(if (s.equipped[slot] == g.id) "已穿戴" else "换上") }
                                    }
                                }
                                val cost = 20L * (enhancement + 1) * (enhancement + 1)
                                TextButton(onClick = { scope.launch { repo.update { CultivationEngine.upgrade(it, slot) }?.let { feedback = comparison(it.first, it.second) } } }, enabled = !busy && enhancement < 10 && s.stones >= cost) { Text(if (enhancement == 10) "已强化至 +10" else "强化装备位 · $cost 灵石") }
                            }
                        }
                    }
                }
                2 -> {
                    SectionLabel("轮回与传承")
                    GroupCard(Modifier.padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("已转世 ${s.reincarnations} 次", style = MaterialTheme.typography.titleLarge, color = Daylight.ink)
                            Text("根骨 +${CultivationEngine.multiply(s.reincarnations, 5)}% · 悟性 +${CultivationEngine.multiply(s.reincarnations, 10)}%", color = Daylight.primary)
                            Text("历世最高：${if (s.highestLevel <= 9) "炼气${s.highestLevel}层" else "筑基${s.highestLevel - 9}层"} · 已发现 ${s.collection.size} 种装备", color = Daylight.inkSub)
                            Spacer(Modifier.height(12.dp))
                            Text("本世筑基九层并通关雷云峰后可转世。继承一件装备与 10% 灵石，永久增加 5% 基础属性与 10% 修为收益；境界、修为、地图、强化及其余装备重置。", color = Daylight.inkSub)
                            Button(onClick = { scope.launch { repo.refresh(); rebirthPreview = repo.state.value; selectedHeirloom = repo.state.value?.gear?.firstOrNull()?.id } }, enabled = !busy && s.level == 18 && 6 in s.bosses) { Text("查看转世清单") }
                        }
                    }
                    SectionLabel("图鉴 · 历世保留")
                    val entries = s.collection.sorted()
                    if (entries.isEmpty()) Text("打怪掉落后点亮，无须额外领取", modifier = Modifier.padding(horizontal = 20.dp), color = Daylight.inkSub)
                    entries.forEach { entry ->
                        val parts = entry.split(":")
                        val g = Gear(0, GearSlot.valueOf(parts[1]), parts[0].toInt(), Quality.valueOf(parts[2]), 1)
                        Text("${g.name} · ${g.quality.label}装", modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = qualityColor(g.quality))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("离线收益无时长上限、不衰减。每次回来，全额结算所选地图的所有历练时间。", modifier = Modifier.padding(horizontal = 20.dp), color = Daylight.inkSub, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { resetConfirmation = true }, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp)) { Text("单独重置修仙存档", color = Daylight.inkSub) }
            Spacer(Modifier.height(24.dp))
        }
    }
    feedback?.let { text -> AlertDialog(onDismissRequest = { feedback = null }, title = { Text("修炼变化") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { feedback = null }) { Text("知道了") } }) }
    bossReplay?.let { result ->
        AlertDialog(onDismissRequest = { bossReplay = null }, title = { Text("已结算守关战报") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                state?.let { BattleReplay(it, result, boss = true) }
                Text(if (result.won) "击败守关妖兽。首胜奖励和解锁已保存，重复试招没有额外奖励。" else battleFailure(result))
            } }, confirmButton = { TextButton(onClick = { bossReplay = null }) { Text("知道了") } })
    }
    equipPreview?.let { gear ->
        val live = state
        val exists = live?.gear?.any { it.id == gear.id } == true
        val current = live?.gear?.firstOrNull { live.equipped[gear.slot] == it.id }
        val after = if (live != null && exists) CultivationEngine.equip(live, gear.id) else null
        val beforeStats = live?.let { CultivationEngine.stats(it) }
        val afterStats = after?.let { CultivationEngine.stats(it) }
        val dissolved = if (live != null && after != null) live.gear.filter { g -> after.gear.none { it.id == g.id } } else emptyList()
        AlertDialog(onDismissRequest = { equipPreview = null }, title = { Text("换上${gear.name}？") }, text = {
            Column {
                Text("当前：${current?.let { "${it.name} · ${gearStats(it)}" } ?: "空装备位"}")
                Text("换上：${gear.name} · ${gearStats(gear)}", color = qualityColor(gear.quality))
                if (beforeStats != null && afterStats != null) Text("角色攻击 ${beforeStats.first} → ${afterStats.first}\n角色生命 ${beforeStats.second} → ${afterStats.second}")
                if (live != null && after != null) Text(comparison(live, after), color = Daylight.inkSub)
                Spacer(Modifier.height(12.dp))
                Text("装备位强化保留，传承件不会被化解。")
                if (dissolved.isNotEmpty()) Text("超出三件候选，将化解：${dissolved.joinToString { "${it.name}（${it.quality.label}装）" }}", color = Daylight.danger)
                if (!exists) Text("这件候选已被新的掉落替换，请返回装备列表", color = Daylight.danger)
            }
        }, confirmButton = { TextButton(onClick = { scope.launch {
            val expected = live ?: return@launch
            val committed = repo.update {
                require(it.level == expected.level && it.map == expected.map && it.practice == expected.practice &&
                    it.equipped == expected.equipped && it.gear == expected.gear && it.enhancements == expected.enhancements &&
                    it.world == expected.world && it.heirloomId == expected.heirloomId) { "装备或属性已更新，请重新检查比较后确认" }
                CultivationEngine.equip(it, gear.id)
            }
            if (committed != null) { feedback = comparison(committed.first, committed.second); equipPreview = null }
        } }, enabled = !busy && exists) { Text("确认换装") } }, dismissButton = { TextButton(onClick = { equipPreview = null }) { Text("取消") } })
    }
    rebirthPreview?.let { preview ->
        AlertDialog(onDismissRequest = { if (!busy) rebirthPreview = null }, title = { Text("转入第 ${preview.world + 1} 世") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("旧世历练已结清。保留 ${preview.stones / 10} 灵石；根骨再加 5%，悟性再加 10%。境界、${preview.cultivationTenths / 10} 修为、地图、所有强化及未选装备将重置。选择一件传承：")
                preview.gear.forEach { g -> Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedHeirloom == g.id, onClick = { selectedHeirloom = g.id })
                    Text("${g.name} · ${g.quality.label}装 · ${gearStats(g)}", color = qualityColor(g.quality))
                } }
                error?.let { Text(it, color = Daylight.danger) }
            }
        }, confirmButton = { TextButton(onClick = { scope.launch {
            val chosen = selectedHeirloom ?: return@launch
            repo.reincarnate(preview, chosen)
            if (repo.state.value?.world != preview.world) rebirthPreview = null else rebirthPreview = repo.state.value
        } }, enabled = !busy && selectedHeirloom != null) { Text("确认转世") } }, dismissButton = { TextButton(onClick = { rebirthPreview = null }, enabled = !busy) { Text("留在这一世") } })
    }
    if (resetConfirmation) AlertDialog(onDismissRequest = { resetConfirmation = false }, title = { Text("重置修仙存档？") }, text = { Text("将永久清除修仙进度、装备、转世记录及待投补给。旧轮次不能为新存档领取补给。照片、回收站与清理设置不受影响。") }, confirmButton = { TextButton(onClick = { scope.launch { repo.reset() }; resetConfirmation = false }) { Text("清除修仙进度", color = Daylight.danger) } }, dismissButton = { TextButton(onClick = { resetConfirmation = false }) { Text("取消") } })
}

private fun duration(minutes: Long): String = if (minutes >= 1440) "${minutes / 1440} 天 ${minutes % 1440 / 60} 小时" else if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分钟" else "$minutes 分钟"
private fun gearStats(g: Gear): String = when (g.slot) {
    GearSlot.WEAPON -> "攻击 +${g.strength}"
    GearSlot.ROBE -> "生命 +${g.strength * 8}"
    GearSlot.CHARM -> "攻击 +${g.strength} / 生命 +${g.strength * 4}"
}
private fun qualityColor(q: Quality): Color = when (q) {
    Quality.WHITE -> Daylight.inkSub; Quality.BLUE -> Color(0xFF28629A)
    Quality.PURPLE -> Color(0xFF795396); Quality.ORANGE -> Color(0xFF935915)
}

@Composable
internal fun CultivationScene(s: CultivationState, modifier: Modifier, p: Float, enemyAlpha: Float, playerHit: Boolean) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val ink = if (playerHit) Daylight.danger else Color(0xFF426559); val mist = Color(0xFFDFE8DF)
        fun mountain(points: List<Offset>, color: Color) { drawPath(Path().apply { moveTo(points.first().x, points.first().y); points.drop(1).forEach { lineTo(it.x, it.y) }; close() }, color) }
        mountain(listOf(Offset(0f,h),Offset(w*.15f,h*.28f),Offset(w*.39f,h*.75f),Offset(w*.6f,h*.15f),Offset(w,h*.8f),Offset(w,h)), mist)
        mountain(listOf(Offset(0f,h),Offset(w*.25f,h*.66f),Offset(w*.49f,h*.85f),Offset(w*.8f,h*.45f),Offset(w,h),Offset(w,h)), Color(0xFFCDDCCF))
        drawLine(ink.copy(alpha=.25f), Offset(w*.12f,h*.88f),Offset(w*.91f,h*.88f),2.dp.toPx())
        val x=w*.28f + if (playerHit) -4.dp.toPx() else 0f; val y=h*.68f
        // A small robed cultivator, drawn locally. No downloaded media or dependencies.
        drawCircle(ink, 10.dp.toPx(), Offset(x,y-26.dp.toPx()))
        mountain(listOf(Offset(x,y-14.dp.toPx()),Offset(x-17.dp.toPx(),y+24.dp.toPx()),Offset(x+18.dp.toPx(),y+24.dp.toPx())),ink)
        drawLine(Color(0xFFF4F6EF),Offset(x-5.dp.toPx(),y-8.dp.toPx()),Offset(x+10.dp.toPx(),y+18.dp.toPx()),2.dp.toPx())
        val mx=w*.76f
        drawOval(Color(0xFF7B816B).copy(alpha = enemyAlpha),topLeft=Offset(mx-24.dp.toPx(),y-7.dp.toPx()),size=androidx.compose.ui.geometry.Size(48.dp.toPx(),32.dp.toPx()))
        drawCircle(Color(0xFF5C6555).copy(alpha = enemyAlpha),16.dp.toPx(),Offset(mx+12.dp.toPx(),y-12.dp.toPx()))
        drawLine(ink.copy(alpha = enemyAlpha),Offset(mx+5.dp.toPx(),y-24.dp.toPx()),Offset(mx+2.dp.toPx(),y-38.dp.toPx()),4.dp.toPx())
        drawCircle(Color.White.copy(alpha = enemyAlpha),2.dp.toPx(),Offset(mx+18.dp.toPx(),y-15.dp.toPx()))
        val sx=w*(.38f+.25f*p); val sy=y-20.dp.toPx()-kotlin.math.sin(p*Math.PI).toFloat()*20.dp.toPx()
        drawLine(Color(0xFF9EA88C),Offset(sx-22.dp.toPx(),sy),Offset(sx+12.dp.toPx(),sy),3.dp.toPx())
        drawLine(Color(0xFF647E6A),Offset(sx-18.dp.toPx(),sy-5.dp.toPx()),Offset(sx-18.dp.toPx(),sy+5.dp.toPx()),2.dp.toPx())
        drawCircle(ink.copy(alpha=.15f),30.dp.toPx(),Offset(x,y),style=Stroke(1.dp.toPx()))
    }
}
