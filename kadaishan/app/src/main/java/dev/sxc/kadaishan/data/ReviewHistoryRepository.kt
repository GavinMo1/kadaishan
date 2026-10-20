package dev.sxc.kadaishan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.sxc.kadaishan.domain.session.ReviewHistory
import dev.sxc.kadaishan.model.MediaKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

private val Context.reviewDataStore by preferencesDataStore(name = "review_history")

/** A successful return acknowledges a committed DataStore update, never merely a queued write. */
class ReviewHistoryRepository(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val diagnostic: (String) -> Unit = {},
) : ReviewHistory {
    private constructor(context: Context) : this(
        context.applicationContext.reviewDataStore,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { ReviewDiagnostics.record(it) },
    )

    private val writes = Mutex()

    override suspend fun keptIds(): Set<MediaKey> = writes.withLock {
        dataStore.data.first()[KEY_KEPT].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    override suspend fun setKept(ids: Set<MediaKey>, kept: Boolean) {
        if (ids.isEmpty()) return
        val values = ids.map { it.toString() }.toSet()
        diagnostic("write begin keep=$kept count=${ids.size} ids=${ids.take(5)}")
        // Application-owned work can finish through Activity destruction; await propagates IO errors.
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            writes.withLock {
                dataStore.edit { prefs ->
                    val previous = prefs[KEY_KEPT].orEmpty()
                    prefs[KEY_KEPT] = if (kept) previous + values else previous - values
                }
            }
        }.also { write ->
            write.invokeOnCompletion { failure ->
                if (failure != null) diagnostic("write failed type=${failure.javaClass.simpleName} ids=${ids.take(5)}")
            }
        }.await()
        diagnostic("committed keep=$kept count=${ids.size} ids=${ids.take(5)}")
    }

    companion object {
        private val KEY_KEPT = stringSetPreferencesKey("kept_media_ids")
        @Volatile private var instance: ReviewHistoryRepository? = null

        /** Activity recreation must share the pending-write queue as well as the DataStore. */
        fun get(context: Context): ReviewHistoryRepository = instance ?: synchronized(this) {
            instance ?: ReviewHistoryRepository(context).also { instance = it }
        }
    }
}
