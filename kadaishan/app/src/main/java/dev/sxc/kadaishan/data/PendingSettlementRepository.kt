package dev.sxc.kadaishan.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.sxc.kadaishan.model.DeleteMode
import dev.sxc.kadaishan.model.PendingSettlement
import dev.sxc.kadaishan.model.SnapItem
import kotlinx.coroutines.flow.first

private val Context.settlementDataStore: DataStore<Preferences> by preferencesDataStore(name = "settlement")

/** CONFIRMING 起才落盘；结果页离开才清除，避免冷启动后误发第二次删除。 */
class PendingSettlementRepository(context: Context) {
    private val dataStore = context.settlementDataStore

    suspend fun save(value: PendingSettlement) {
        dataStore.edit { it[KEY_PENDING] = encode(value) }
    }

    suspend fun load(): PendingSettlement? = decode(dataStore.data.first()[KEY_PENDING])

    suspend fun clear() {
        dataStore.edit { it.remove(KEY_PENDING) }
    }

    private fun encode(value: PendingSettlement): String = buildString {
        append(value.mode.name).append('|').append(value.batchesDone).append('|').append(value.totalBatches)
        value.snapshot.forEach { item ->
            append('\n').append(item.id).append('\t')
            append(field(item.uri)).append('\t')
            append(field(item.displayName)).append('\t').append(item.sizeBytes)
        }
    }

    private fun decode(raw: String?): PendingSettlement? = try {
        val lines = raw?.lines().orEmpty()
        val head = lines.firstOrNull()?.split('|') ?: return null
        require(head.size == 3)
        val items = lines.drop(1).map { line ->
            val parts = line.split('\t')
            require(parts.size == 4)
            SnapItem(parts[0].toLong(), unfield(parts[1]), unfield(parts[2]), parts[3].toLong())
        }
        PendingSettlement(items, DeleteMode.valueOf(head[0]), head[1].toInt(), head[2].toInt())
    } catch (_: Exception) {
        null
    }

    private fun field(value: String): String = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
    private fun unfield(value: String): String = String(Base64.decode(value, Base64.NO_WRAP))

    private companion object {
        val KEY_PENDING = stringPreferencesKey("settlement_pending")
    }
}
