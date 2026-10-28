package dev.sxc.kadaishan.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.sxc.kadaishan.domain.cultivation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.cultivationStore by preferencesDataStore("cultivation")

/** Separate from media state; one writer, each catch-up chunk is durable and resumable. */
class CultivationRepository(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val store = context.applicationContext.cultivationStore
    private val key = stringPreferencesKey("state_v1")
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<CultivationState?>(null)
    val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    private suspend fun load(): CultivationState {
        mutableState.value?.let { return it }
        val raw = store.data.first()[key]
        return (if (raw == null) CultivationState(lastMillis = clock(), seed = java.security.SecureRandom().nextLong()) else CultivationCodec.decode(raw)).also {
            if (raw == null) save(it) else mutableState.value = it
        }
    }
    private suspend fun save(value: CultivationState) {
        store.edit { it[key] = CultivationCodec.encode(value) }
        mutableState.value = value
    }
    private suspend fun settle(): CultivationState {
        var current = load()
        val now = clock().coerceAtLeast(0)
        do {
            val next = CultivationEngine.catchUp(current, now)
            if (next != current) save(next)
            current = next
        } while (CultivationEngine.pending(current, now) > 0)
        return current
    }
    private suspend fun run(action: suspend () -> Unit) = withContext(Dispatchers.Default) {
        mutex.withLock {
            mutableBusy.value = true
            try { action(); mutableError.value = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableError.value = e.message ?: "修仙存档暂不可用，请重试或单独重置" }
            finally { mutableBusy.value = false }
        }
    }
    suspend fun refresh() = run { settle() }
    suspend fun update(action: (CultivationState) -> CultivationState) = run { save(action(settle())) }
    suspend fun clearReport() = update { it.copy(report = Earnings()) }
    suspend fun setEnabled(enabled: Boolean) = run { save(load().copy(enabled = enabled)) }
    suspend fun setPaused(paused: Boolean) = update { it.copy(paused = paused, lastMillis = maxOf(it.lastMillis, clock())) }
    suspend fun challenge(): Battle? {
        var result: Battle? = null
        run { val pair = CultivationEngine.challenge(settle()); save(pair.first); result = pair.second }
        return result
    }
    /** Reject stale previews; refresh is saved before showing the replacement preview. */
    suspend fun reincarnate(preview: CultivationState, heirloom: Long) = run {
        val current = settle()
        require(current.copy(lastMillis = preview.lastMillis, carryMillis = preview.carryMillis) == preview) { "历练收益已更新，请检查新预览后再次确认" }
        save(CultivationEngine.reincarnate(current, heirloom, preview.world))
    }
    /** Called only by a completed round adapter, never on each swipe. */
    suspend fun completedRound(sequence: Long, hasDecision: Boolean) = run {
        save(CultivationEngine.completedRound(settle(), sequence, hasDecision, clock().coerceAtLeast(0)))
    }
    /** UI must ask for confirmation; only this game's file is replaced. */
    suspend fun reset() = run { save(CultivationState(lastMillis = clock(), seed = java.security.SecureRandom().nextLong())) }
}

internal object CultivationCodec {
    fun encode(s: CultivationState): String = JSONObject().apply {
        put("version", s.version); put("enabled", s.enabled); put("paused", s.paused)
        put("level", s.level); put("xp", s.cultivationTenths); put("stones", s.stones)
        put("map", s.map); put("unlocked", s.unlockedMap); put("bosses", JSONArray(s.bosses.toList()))
        put("practice", s.practice.name)
        put("enhancements", JSONObject().apply { s.enhancements.forEach { (k,v) -> put(k.name, v) } })
        put("equipped", JSONObject().apply { s.equipped.forEach { (k,v) -> put(k.name, v) } })
        put("gear", JSONArray().apply { s.gear.forEach { g -> put(JSONObject().apply {
            put("id", g.id); put("slot", g.slot.name); put("tier", g.tier); put("quality", g.quality.name); put("strength", g.strength)
        }) } })
        put("heirloom", s.heirloomId ?: JSONObject.NULL); put("r", s.reincarnations); put("world", s.world)
        put("highest", s.highestLevel); put("collection", JSONArray(s.collection.toList()))
        put("seed", s.seed); put("sequence", s.sequence); put("dry", s.dryWins); put("dull", s.dullDrops)
        put("last", s.lastMillis); put("carry", s.carryMillis); put("gift", s.giftMillis ?: JSONObject.NULL); put("consumed", s.consumedRound)
        put("report", JSONObject().apply {
            put("minutes", s.report.minutes); put("wins", s.report.wins); put("xp", s.report.cultivationTenths)
            put("stones", s.report.stones); put("drops", s.report.drops); put("dissolved", s.report.dissolved); put("best", s.report.bestQuality)
        })
    }.toString()
    fun decode(raw: String): CultivationState {
        val j = JSONObject(raw)
        require(j.getInt("version") == 1) { "修仙存档版本不支持，请勿覆盖原存档" }
        fun longs(name: String) = j.getJSONObject(name).let { obj -> obj.keys().asSequence().associate { GearSlot.valueOf(it) to obj.getLong(it) } }
        val gearArray = j.getJSONArray("gear")
        val r = j.getJSONObject("report")
        val s = CultivationState(
            enabled = j.getBoolean("enabled"), paused = j.getBoolean("paused"), level = j.getInt("level"),
            cultivationTenths = j.getLong("xp"), stones = j.getLong("stones"), map = j.getInt("map"), unlockedMap = j.getInt("unlocked"),
            bosses = j.getJSONArray("bosses").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() },
            practice = Practice.valueOf(j.getString("practice")), enhancements = longs("enhancements").mapValues { it.value.toInt() }, equipped = longs("equipped"),
            gear = (0 until gearArray.length()).map { i -> gearArray.getJSONObject(i).let { g -> Gear(g.getLong("id"), GearSlot.valueOf(g.getString("slot")), g.getInt("tier"), Quality.valueOf(g.getString("quality")), g.getLong("strength")) } },
            heirloomId = if (j.isNull("heirloom")) null else j.getLong("heirloom"), reincarnations = j.getLong("r"), world = j.getLong("world"),
            highestLevel = j.getInt("highest"), collection = j.getJSONArray("collection").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
            seed = j.getLong("seed"), sequence = j.getLong("sequence"), dryWins = j.getInt("dry"), dullDrops = j.getInt("dull"),
            lastMillis = j.getLong("last"), carryMillis = j.getLong("carry"), giftMillis = if (j.isNull("gift")) null else j.getLong("gift"), consumedRound = j.getLong("consumed"),
            report = Earnings(r.getLong("minutes"), r.getLong("wins"), r.getLong("xp"), r.getLong("stones"), r.getLong("drops"), r.getLong("dissolved"), r.getInt("best")),
        )
        require(s.level in 1..18 && s.map in 1..6 && s.unlockedMap in 1..6 && s.map <= s.unlockedMap)
        require(s.cultivationTenths >= 0 && s.stones >= 0 && s.lastMillis >= 0 && s.carryMillis in 0..59_999)
        require(s.reincarnations >= 0 && s.world > 0 && s.sequence >= 0 && s.consumedRound >= 0)
        require(s.dryWins in 0..19 && s.dullDrops in 0..49 && s.enhancements.values.all { it in 0..10 })
        require(s.gear.size <= 13 && s.gear.map { it.id }.distinct().size == s.gear.size)
        require(s.gear.all { it.tier in 1..6 && it.strength > 0 })
        require(s.equipped.all { (slot, id) -> s.gear.any { it.slot == slot && it.id == id } })
                require(s.heirloomId == null || s.gear.any { it.id == s.heirloomId })
        require(s.bosses.all { it in 1..6 } && s.highestLevel in 1..18)
        require(s.giftMillis == null || s.giftMillis >= 0)
        require(s.report.minutes >= 0 && s.report.wins >= 0 && s.report.cultivationTenths >= 0 && s.report.stones >= 0 && s.report.drops >= 0 && s.report.dissolved >= 0 && s.report.bestQuality in -1..3)
        require(s.collection.all { entry ->
            val parts = entry.split(":")
            parts.size == 3 && parts[0].toIntOrNull() in 1..6 && GearSlot.entries.any { it.name == parts[1] } && Quality.entries.any { it.name == parts[2] }
        })
        return s
    }
}
