package dev.sxc.kadaishan.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*

private val Context.roundIdStore by preferencesDataStore("cultivation_round_ids")

/** Tool-side sequence allocation never reads media IDs and never delays a tool operation. */
class CultivationRoundBridge(context: Context, private val game: CultivationRepository) {
    private val store = context.applicationContext.roundIdStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = longPreferencesKey("next_round")
    class Handle internal constructor(internal val sequence: Deferred<Long?>)
    fun begin(): Handle = Handle(scope.async {
        try {
            var allocated: Long? = null
            store.edit { p ->
                val previous = p[key] ?: 0
                if (previous < Long.MAX_VALUE) { allocated = previous + 1; p[key] = previous + 1 }
            }
            allocated
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    })
    fun complete(handle: Handle?, hasDecision: Boolean) {
        if (handle == null) return
        scope.launch { handle.sequence.await()?.let { game.completedRound(it, hasDecision) } }
    }
}
