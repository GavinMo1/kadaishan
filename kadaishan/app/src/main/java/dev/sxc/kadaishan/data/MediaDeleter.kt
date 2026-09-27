package dev.sxc.kadaishan.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.IntentSender
import android.os.Bundle
import android.provider.MediaStore
import dev.sxc.kadaishan.model.DeleteMode
import dev.sxc.kadaishan.model.RecheckRow
import dev.sxc.kadaishan.model.SnapItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MediaStore 写入与回查唯一入口；UI 只负责启动它交出的 IntentSender。 */
class MediaDeleter(private val resolver: ContentResolver) {
    fun createBatchIntent(items: List<SnapItem>, mode: DeleteMode): IntentSender {
        require(items.isNotEmpty()) { "删除批次不能为空" }
        val pending: PendingIntent = when (mode) {
            DeleteMode.TRASH -> MediaStore.createTrashRequest(resolver, items.map { it.uri }, true)
            DeleteMode.PERMANENT -> MediaStore.createDeleteRequest(resolver, items.map { it.uri })
        }
        return pending.intentSender
    }

    fun createRestoreIntent(items: List<SnapItem>): IntentSender {
        require(items.isNotEmpty()) { "恢复批次不能为空" }
        return MediaStore.createTrashRequest(resolver, items.map { it.uri }, false).intentSender
    }

    /** 结果页回查：MATCH_INCLUDE + 专用三列；不构造 MediaItem。 */
    suspend fun recheck(ids: List<Long>): List<RecheckRow> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val selection = ids.joinToString(prefix = "_ID IN (", postfix = ")", separator = ",") { "?" }
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, ids.map(Long::toString).toTypedArray())
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        val result = ArrayList<RecheckRow>(ids.size)
        val collections = arrayOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        collections.forEach { collection ->
            resolver.query(collection, projection, args, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val trashIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
                val expiresIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
                while (cursor.moveToNext()) {
                    result += RecheckRow(
                        id = cursor.getLong(idIndex),
                        isTrashed = cursor.getInt(trashIndex) == 1,
                        dateExpiresSec = if (expiresIndex < 0 || cursor.isNull(expiresIndex)) null else cursor.getLong(expiresIndex),
                    )
                }
            }
        }
        result
    }
}
