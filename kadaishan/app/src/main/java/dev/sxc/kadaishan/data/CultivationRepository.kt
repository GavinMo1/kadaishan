package dev.sxc.kadaishan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.sxc.kadaishan.domain.cultivation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Context.cultivationStore by preferencesDataStore("cultivation")

/** One durable writer. A failed/uncertain write always invalidates the transaction cache. */
class CultivationRepository internal constructor(
    private val store: DataStore<Preferences>,
    internal val rewards: CultivationRewardOutbox,
    private val backup: suspend (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, clock: () -> Long = System::currentTimeMillis) : this(
        context.applicationContext.cultivationStore, CultivationRewardOutbox(context.applicationContext),
        { raw -> CultivationBackup.preserve(File(context.filesDir, "cultivation-v1-backup.json"), raw) }, clock)
    private val key = stringPreferencesKey("state_v1")
    private val mutex = Mutex()
    private var cache: CultivationState? = null
    private val mutableState = MutableStateFlow<CultivationState?>(null)
    val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    internal var requestRewardDrain: () -> Unit = {}
    internal fun rewardError(e: Exception) { mutableError.value = e.message ?: "补给暂未记录，请进入修仙后重试" }

    private suspend fun load(): CultivationState {
        cache?.let { return it }
        val raw = store.data.first()[key]
        val loaded = if (raw == null) CultivationState(lastMillis = clock().coerceAtLeast(0),
            rewardGeneration = rewards.control().generation, seed = java.security.SecureRandom().nextLong()) else CultivationCodec.decode(raw)
        if (raw == null) save(loaded) else { cache = loaded; mutableState.value = loaded }
        return loaded
    }
    private suspend fun save(value: CultivationState) {
        try {
            store.edit { it[key] = CultivationCodec.encode(value) }
            cache = value
            mutableState.value = value
        } catch (e: Exception) { cache = null; throw e }
    }
    /** Resume the persistent reset intent before exposing or settling a game. Caller owns coordination. */
    private suspend fun resumeReset() {
        val control = rewards.control()
        if (!control.resetPending) return
        cache = null
        val raw = store.data.first()[key]
        val installed = raw?.let { runCatching { CultivationCodec.decode(it) }.getOrNull() }
        if (installed?.rewardGeneration != control.generation) {
            save(CultivationState(lastMillis = clock().coerceAtLeast(0), rewardGeneration = control.generation,
                seed = java.security.SecureRandom().nextLong()))
        } else { cache = installed; mutableState.value = installed }
        rewards.finishReset(control.generation)
    }
    private suspend fun settle(): CultivationState {
        var current = load()
        val context = currentCoroutineContext()
        val now = clock().coerceAtLeast(0)
        if (current.version == 1) backup(store.data.first()[key] ?: error("旧存档已不可读，停止升级"))
        do {
            context.ensureActive()
            val next = CultivationEngine.catchUp(current, now, checkActive = { context.ensureActive() })
            if (next != current) save(next)
            current = next
            yield()
        } while (CultivationEngine.pending(current, now) > 0)
        if (current.version == 1) {
            current = current.copy(version = 2, battleCarryMillis = 0,
                report = current.report.copy(battles = current.report.minutes, legacyMinutes = current.report.minutes))
            save(current)
        }
        require(current.rewardGeneration == rewards.control().generation) { "修仙与补给代次不一致，原存档已保留" }
        return current
    }
    private suspend fun <T> run(action: suspend () -> T): T? = withContext(Dispatchers.Default) {
        val result = rewards.coordination.withLock {
            mutex.withLock {
                mutableBusy.value = true
                try { resumeReset(); action().also { mutableError.value = null } }
                catch (e: CancellationException) { cache = null; throw e }
                catch (e: Exception) { cache = null; mutableError.value = e.message ?: "修仙存档暂不可用，请重试"; null }
                finally { mutableBusy.value = false }
            }
        }
        result
    }
    suspend fun refresh() { run { settle() }; requestRewardDrain() }
    /** Returns committed before/after for feedback; calculations never award on animation. */
    suspend fun update(action: (CultivationState) -> CultivationState): Pair<CultivationState, CultivationState>? = run {
        val old = settle()
        val changed = CultivationEngine.transition(old, action(old))
        save(changed)
        old to changed
    }
    suspend fun clearReport() { update { it.copy(report = Earnings()) } }
    suspend fun setEnabled(enabled: Boolean) { run { val s = load(); save(s.copy(enabled = enabled)) } }
    suspend fun setPaused(paused: Boolean) { update { it.copy(paused = paused, lastMillis = maxOf(it.lastMillis, clock().coerceAtLeast(0))) } }
    suspend fun challenge(): Battle? = run {
        val pair = CultivationEngine.challenge(settle()); save(pair.first); pair.second
    }
    suspend fun reincarnate(preview: CultivationState, heirloom: Long) { run {
        val current = settle()
        require(current.copy(lastMillis = preview.lastMillis, carryMillis = preview.carryMillis) == preview) { "历练收益已更新，请检查新预览后再次确认" }
        save(CultivationEngine.transition(current, CultivationEngine.reincarnate(current, heirloom, preview.world)))
    } }
    /** Durable ACK only; false includes uncertain commits, which are resolved by rereading next time. */
    internal suspend fun deliver(event: PendingReward): Boolean = run {
        val current = settle()
        if (event.generation != current.rewardGeneration) return@run true
        if (event.id <= current.consumedRound) return@run true
        val control = rewards.control()
        require(current.consumedRound <= control.nextId && event.id <= control.nextId) { "补给序号不一致，原记录已保留" }
        val changed = CultivationEngine.completedRound(current, event.id, event.hasDecision, event.completedAt)
        save(changed)
        true
    } ?: false
    /** The public reset is the only place allowed to replace a damaged/unsupported game. */
    suspend fun reset() { run {
        rewards.prepareReset()
        cache = null
        resumeReset()
    } }
}

internal object CultivationBackup {
    private fun checksum(raw: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun preserve(file: File, raw: String) {
        if (file.exists()) {
            val saved = JSONObject(file.readText())
            val original = saved.getString("raw")
            require(saved.getString("sha256") == checksum(original) && CultivationCodec.decode(original).version == 1) { "升级备份校验失败，请保留文件后重试" }
            return
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        val envelope = JSONObject().put("schema", 1).put("createdAt", System.currentTimeMillis())
            .put("raw", raw).put("sha256", checksum(raw)).toString()
        java.io.FileOutputStream(temp).use { out -> out.write(envelope.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}

internal object CultivationCodec {
    fun encode(s: CultivationState): String = JSONObject().apply {
        put("battleCarry", s.battleCarryMillis); put("rewardGeneration", s.rewardGeneration)
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
            put("battles", s.report.battles); put("bonusMinutes", s.report.bonusMinutes); put("legacyMinutes", s.report.legacyMinutes)
            put("minutes", s.report.minutes); put("wins", s.report.wins); put("xp", s.report.cultivationTenths)
            put("stones", s.report.stones); put("drops", s.report.drops); put("dissolved", s.report.dissolved); put("best", s.report.bestQuality)
        })
    }.toString()
    fun decode(raw: String): CultivationState {
        val j = JSONObject(raw)
        val version = j.getInt("version")
        require(version in 1..2) { "修仙存档版本不支持，请勿覆盖原存档" }
        fun longs(name: String) = j.getJSONObject(name).let { obj -> obj.keys().asSequence().associate { GearSlot.valueOf(it) to obj.getLong(it) } }
        val gearArray = j.getJSONArray("gear")
        val r = j.getJSONObject("report")
        val s = CultivationState(
            version = version,
            battleCarryMillis = if (version == 2) j.getLong("battleCarry") else 0,
            rewardGeneration = if (version == 2) j.getLong("rewardGeneration") else 0,
            enabled = j.getBoolean("enabled"), paused = j.getBoolean("paused"), level = j.getInt("level"),
            cultivationTenths = j.getLong("xp"), stones = j.getLong("stones"), map = j.getInt("map"), unlockedMap = j.getInt("unlocked"),
            bosses = j.getJSONArray("bosses").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() },
            practice = Practice.valueOf(j.getString("practice")), enhancements = longs("enhancements").mapValues { it.value.toInt() }, equipped = longs("equipped"),
            gear = (0 until gearArray.length()).map { i -> gearArray.getJSONObject(i).let { g -> Gear(g.getLong("id"), GearSlot.valueOf(g.getString("slot")), g.getInt("tier"), Quality.valueOf(g.getString("quality")), g.getLong("strength")) } },
            heirloomId = if (j.isNull("heirloom")) null else j.getLong("heirloom"), reincarnations = j.getLong("r"), world = j.getLong("world"),
            highestLevel = j.getInt("highest"), collection = j.getJSONArray("collection").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
            seed = j.getLong("seed"), sequence = j.getLong("sequence"), dryWins = j.getInt("dry"), dullDrops = j.getInt("dull"),
            lastMillis = j.getLong("last"), carryMillis = j.getLong("carry"), giftMillis = if (j.isNull("gift")) null else j.getLong("gift"), consumedRound = j.getLong("consumed"),
            report = Earnings(r.getLong("minutes"), r.getLong("wins"), r.getLong("xp"), r.getLong("stones"), r.getLong("drops"), r.getLong("dissolved"), r.getInt("best"),
                if (version == 2) r.getLong("battles") else 0,
                if (version == 2) r.getLong("bonusMinutes") else 0,
                if (version == 2) r.getLong("legacyMinutes") else 0),
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
        require(s.rewardGeneration >= 0 && s.battleCarryMillis >= 0)
        if (version == 2) {
            require(s.battleCarryMillis < CultivationEngine.battle(s).cycleMillis)
            require(s.report.battles >= s.report.wins && s.report.bonusMinutes >= 0 && s.report.legacyMinutes >= 0)
            require(s.report.legacyMinutes <= s.report.minutes && s.report.bonusMinutes <= s.report.minutes - s.report.legacyMinutes)
        }
        return s
    }
}
