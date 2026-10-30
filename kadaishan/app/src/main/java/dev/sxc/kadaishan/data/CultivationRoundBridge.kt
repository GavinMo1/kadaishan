package dev.sxc.kadaishan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

private val Context.roundIdStore by preferencesDataStore("cultivation_round_ids")
internal data class PendingReward(val id: Long, val generation: Long, val completedAt: Long, val hasDecision: Boolean)
internal data class RewardControl(val nextId: Long, val generation: Long, val resetPending: Boolean)

/** Tiny outbox in the existing independent file. No media identifiers, timers or background service. */
internal class CultivationRewardOutbox(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.roundIdStore)
    val coordination = Mutex()
    private val nextKey = longPreferencesKey("next_round")
    private val generationKey = longPreferencesKey("generation")
    private val resetKey = booleanPreferencesKey("reset_pending")
    private val pendingKey = stringPreferencesKey("pending_rewards")
    private val receiptsKey = stringSetPreferencesKey("append_receipts")
    private fun control(p: Preferences) = RewardControl(p[nextKey] ?: 0, p[generationKey] ?: 0, p[resetKey] ?: false).also {
        require(it.nextId >= 0 && it.generation >= 0) { "补给记录损坏，原文件已保留" }
    }
    suspend fun control() = control(store.data.first())
    private fun decode(p: Preferences): List<PendingReward> {
        val a = JSONArray(p[pendingKey] ?: "[]")
        val c = control(p)
        return (0 until a.length()).map { i -> a.getJSONObject(i).let {
            PendingReward(it.getLong("id"), it.getLong("generation"), it.getLong("at"), it.getBoolean("decision"))
        } }.also { list ->
            require(list.all { it.id > 0 && it.id <= c.nextId && it.generation >= 0 && it.generation <= c.generation && it.completedAt >= 0 })
            require(list.map { it.id }.distinct().size == list.size)
        }.sortedBy { it.id }
    }
    private fun encode(events: List<PendingReward>) = JSONArray().apply { events.forEach {
        put(JSONObject().put("id", it.id).put("generation", it.generation).put("at", it.completedAt).put("decision", it.hasDecision))
    } }.toString()
    suspend fun reserve(): Pair<Long, Long> = coordination.withLock {
        var result: Pair<Long, Long>? = null
        store.edit { p ->
            val c = control(p)
            check(!c.resetPending) { "修仙重置中，本轮无补给" }
            check(c.nextId < Long.MAX_VALUE) { "补给序号已达上限" }
            result = c.nextId + 1 to c.generation
            p[nextKey] = c.nextId + 1
        }
        result!!
    }
    /** ID assignment and insertion are one atomic edit. A caller retries the same Handle only. */
    suspend fun append(sourceId: Long, generation: Long, at: Long, decision: Boolean) = coordination.withLock {
        var assigned: Long? = null
        try {
            store.edit { p ->
                if (sourceId.toString() in p[receiptsKey].orEmpty()) return@edit
                val c = control(p)
                if (generation != c.generation) return@edit
                check(!c.resetPending) { "修仙重置中，本轮无补给" }
                check(c.nextId < Long.MAX_VALUE) { "补给序号已达上限" }
                val events = decode(p)
                assigned = c.nextId + 1
                p[receiptsKey] = p[receiptsKey].orEmpty() + sourceId.toString()
                p[nextKey] = assigned!!
                p[pendingKey] = encode(events + PendingReward(assigned!!, generation, at, decision))
            }
        } catch (e: Exception) {
            // Resolve 'committed but acknowledgement lost' before allowing another insertion.
            if (assigned != null) {
                val present = withContext(NonCancellable) { sourceId.toString() in store.data.first()[receiptsKey].orEmpty() }
                if (present) return@withLock
            }
            throw e
        }
    }
    suspend fun forgetReceipt(sourceId: Long) = coordination.withLock {
        store.edit { p -> p[receiptsKey] = p[receiptsKey].orEmpty() - sourceId.toString() }
    }
    /** Previous-process Handles cannot be replayed. Keep receipts only for this process's live Handles. */
    suspend fun pruneOldReceipts(beforeId: Long) = coordination.withLock {
        store.edit { p -> p[receiptsKey] = p[receiptsKey].orEmpty().filter { it.toLong() > beforeId }.toSet() }
    }
    suspend fun head(): PendingReward? = coordination.withLock { decode(store.data.first()).firstOrNull() }
    suspend fun acknowledge(id: Long) = coordination.withLock {
        store.edit { p -> p[pendingKey] = encode(decode(p).filterNot { it.id == id }) }
    }
    // Reset owns coordination and game mutex. These functions deliberately do not reenter either.
    suspend fun prepareReset() {
        store.edit { p ->
            val c = control(p)
            check(c.generation < Long.MAX_VALUE) { "修仙代次已达上限" }
            p[generationKey] = c.generation + 1
            p[resetKey] = true
        }
    }
    suspend fun finishReset(generation: Long) {
        store.edit { p ->
            require(control(p).generation == generation)
            // Explicit reset can recover a malformed outbox after user confirmation as well.
            p[receiptsKey] = emptySet()
            p[pendingKey] = "[]"
            p[resetKey] = false
        }
    }
}

/** Persist first, then deliver/ACK in order. The media action never waits for game I/O. */
class CultivationRoundBridge internal constructor(
    private val game: CultivationRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, game: CultivationRepository) : this(game, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private val outbox = game.rewards
    private val draining = AtomicBoolean(false)
    private val drainRequested = AtomicBoolean(false)
    private val unrecorded = java.util.concurrent.ConcurrentLinkedQueue<Handle>()
    class Handle internal constructor(internal val identity: Deferred<Pair<Long, Long>?>) {
        internal val submitLock = Mutex()
        internal var submitted = false
        internal var staged = false
        internal var completionTime: Long? = null
        internal var completionDecision: Boolean? = null
    }
    private val initialized = scope.async {
        val priorId = outbox.control().nextId
        outbox.pruneOldReceipts(priorId)
    }
    init { game.requestRewardDrain = ::drain; drain() }
    fun begin(): Handle = Handle(scope.async {
        try { initialized.await(); outbox.reserve() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { game.rewardError(e); null }
    })
    fun complete(handle: Handle?, hasDecision: Boolean) {
        if (handle == null) return
        synchronized(handle) {
            if (handle.staged) return
            handle.staged = true
            handle.completionTime = clock().coerceAtLeast(0)
            handle.completionDecision = hasDecision
            unrecorded.add(handle)
        }
        drain()
    }
    private suspend fun submitUnrecorded() {
        while (true) {
            val handle = unrecorded.peek() ?: return
            handle.submitLock.withLock {
                if (!handle.submitted) {
                    val identity = handle.identity.await()
                    if (identity != null) {
                        outbox.append(identity.first, identity.second, handle.completionTime!!, handle.completionDecision!!)
                        handle.submitted = true
                        outbox.forgetReceipt(identity.first)
                    } else handle.submitted = true
                }
                unrecorded.remove(handle)
            }
        }
    }
    private fun drain() {
        drainRequested.set(true)
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            var failed = false
            try {
                initialized.await()
                do {
                    drainRequested.set(false)
                    submitUnrecorded()
                    while (true) {
                        val event = outbox.head() ?: break
                        if (!game.deliver(event)) { failed = true; break }
                        outbox.acknowledge(event.id)
                    }
                } while (!failed && drainRequested.get())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed = true; game.rewardError(e) }
            finally {
                draining.set(false)
                if (!failed && drainRequested.get()) drain()
            }
        }
    }
}
