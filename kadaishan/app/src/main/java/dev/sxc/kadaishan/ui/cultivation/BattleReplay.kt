package dev.sxc.kadaishan.ui.cultivation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.sxc.kadaishan.domain.cultivation.*
import dev.sxc.kadaishan.ui.kit.Daylight
import dev.sxc.kadaishan.ui.kit.rememberReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

internal fun seconds(ms: Long): String = String.format(Locale.getDefault(), "%.2f", ms / 1000.0)
internal fun battleFailure(b: Battle) = when (b.end) {
    BattleEnd.KILL -> "${b.turns} 回合击败，剩余生命 ${b.playerHp}/${b.maxHp}"
    BattleEnd.DEAD -> "第 ${b.turns} 回合倒下，妖兽剩 ${b.enemyHp}/${b.enemyMaxHp} 生命；提高生命或减少击杀回合"
    BattleEnd.TIMEOUT -> "五回合后妖兽仍剩 ${b.enemyHp}/${b.enemyMaxHp} 生命，需要提高输出"
}
internal fun battleSummary(b: Battle): String = if (b.won)
    "${b.turns} 回合 · 战后 ${b.playerHp}/${b.maxHp} 生命\n预计 ${seconds(b.cycleMillis)} 秒一场 · 约 ${b.winsPerHour} 胜/小时"
else battleFailure(b)

internal fun comparison(before: CultivationState, after: CultivationState): String {
    val a = CultivationEngine.battle(before)
    val b = CultivationEngine.battle(after)
    val effect = when {
        !a.won && b.won -> "现在能击败本图普通妖兽"
        a.won && !b.won -> "注意：换后将无法击败本图普通妖兽"
        a.cycleMillis == b.cycleMillis && a.won == b.won -> "本图效率暂未变化，可比较守关生存"
        b.cycleMillis < a.cycleMillis -> "击杀或休整更快，刷装效率提高"
        else -> "本图历练变慢，请确认取舍"
    }
    return "$effect\n回合 ${a.turns} → ${b.turns} · 战后生命 ${a.playerHp}/${a.maxHp} → ${b.playerHp}/${b.maxHp}" +
        "\n周期 ${seconds(a.cycleMillis)} → ${seconds(b.cycleMillis)} 秒" +
        "\n预计灵石/小时 ${CultivationEngine.hourlyStones(before)} → ${CultivationEngine.hourlyStones(after)}"
}

/** A trace is a configuration demonstration, never a time source or an economic callback. */
@Composable
internal fun BattleReplay(s: CultivationState, battle: Battle, boss: Boolean = false) {
    val reduced = rememberReducedMotion()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var step by remember(battle) { mutableIntStateOf(-1) }
    var showTurns by remember { mutableStateOf(false) }
    LaunchedEffect(battle, reduced, s.paused, lifecycle) {
        step = -1
        if (!reduced && !s.paused) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                step = -1
                delay(600)
                repeat(battle.trace.size * 3) { index -> step = index; delay(320) }
                step = battle.trace.size * 3
                delay(1800)
            }
        }
    }
    val finished = reduced || s.paused || step >= battle.trace.size * 3
    val record = if (step >= 0 && !finished) battle.trace.getOrNull(step / 3) else null
    val phase = step % 3
    val player = if (finished) battle.playerHp else when (phase) {
        0, 1 -> record?.afterHealing ?: battle.maxHp
        else -> record?.playerAfter ?: battle.maxHp
    }
    val enemy = if (finished) battle.enemyHp else if (phase == 0) record?.enemyBefore ?: battle.enemyMaxHp
        else record?.enemyAfter ?: battle.enemyMaxHp
    val playerProgress by animateFloatAsState((player.toDouble() / battle.maxHp).toFloat(), tween(if (reduced) 0 else 180), label = "player-health")
    val enemyProgress by animateFloatAsState((enemy.toDouble() / battle.enemyMaxHp).toFloat(), tween(if (reduced) 0 else 180), label = "monster-health")
    val flight by animateFloatAsState(if (record != null && phase == 1) 1f else 0f, tween(if (reduced) 0 else 200), label = "sword-hit")
    val enemyAlpha by animateFloatAsState(if (enemy == 0L) .12f else 1f, tween(if (reduced) 0 else 200), label = "monster-defeat")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(if (boss) "已结算守关回放 · 重播不发奖励" else "当前配置试演 · 不额外发奖励", style = MaterialTheme.typography.bodySmall, color = Daylight.inkSub)
        Text("修士生命 $player / ${battle.maxHp}", color = Daylight.ink)
        LinearProgressIndicator(progress = { playerProgress }, modifier = Modifier.fillMaxWidth(), color = Daylight.primary)
        Spacer(Modifier.height(8.dp))
        Text("${CultivationEngine.monsterNames[battle.mapTier - 1]}${if (boss) "·守关" else ""} $enemy / ${battle.enemyMaxHp}", color = Daylight.ink)
        LinearProgressIndicator(progress = { enemyProgress }, modifier = Modifier.fillMaxWidth(), color = Daylight.danger)
        CultivationScene(s, Modifier.fillMaxWidth().height(150.dp), flight, enemyAlpha, record?.retaliation != 0L && phase == 2 && record != null)
        Text(when {
            finished -> battleFailure(battle)
            record == null -> "双方满血 · 修士先手"
            phase == 0 -> "第 ${record.turn} 回合${if (record.healing > 0) " · 长生回复 +${record.healing}" else " · 准备出手"}"
            phase == 1 -> "${if (record.burst) "御剑爆发" else "修士攻击"} −${record.damage}"
            record.enemyAfter == 0L -> "妖兽已击败，不再反击"
            else -> "妖兽反击 −${record.retaliation}"
        }, color = Daylight.ink, style = MaterialTheme.typography.bodyMedium)
        if (!boss && battle.won) Text("寻路 12 秒 + 战斗 ${battle.turns * 6} 秒 + 休整 ${seconds(battle.recoveryMillis)} 秒", color = Daylight.inkSub, style = MaterialTheme.typography.bodySmall)
        Text("收益按整分钟结算；观看或重播不会加速。", color = Daylight.inkSub, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { showTurns = !showTurns }) { Text(if (showTurns) "收起回合记录" else "查看回合记录") }
        if (showTurns) battle.trace.forEach { t ->
            Text("第${t.turn}回合：回复 ${t.healing}，${if (t.burst) "御剑" else "攻击"} ${t.damage}，受伤 ${t.retaliation}；剩余 ${t.playerAfter}/${battle.maxHp}，妖兽 ${t.enemyAfter}/${battle.enemyMaxHp}",
                style = MaterialTheme.typography.bodySmall, color = Daylight.inkSub, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}
