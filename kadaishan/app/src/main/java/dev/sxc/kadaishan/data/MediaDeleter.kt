package dev.sxc.kadaishan.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import dev.sxc.kadaishan.model.DeleteMode
import dev.sxc.kadaishan.model.RecheckRow
import dev.sxc.kadaishan.model.SnapItem
import dev.sxc.kadaishan.model.TrashEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 回收站视图查询的双集合（Images + Video，_ID 互斥不重）。 */
private val TRASH_COLLECTIONS = arrayOf(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
)

/** MediaStore 写入与回查唯一入口；UI 只负责启动它交出的 IntentSender。 */
class MediaDeleter(private val resolver: ContentResolver) {
    fun createBatchIntent(items: List<SnapItem>, mode: DeleteMode): IntentSender {
        require(items.isNotEmpty()) { "删除批次不能为空" }
        val pending: PendingIntent = when (mode) {
            DeleteMode.TRASH -> MediaStore.createTrashRequest(resolver, items.map { Uri.parse(it.uri) }, true)
            DeleteMode.PERMANENT -> MediaStore.createDeleteRequest(resolver, items.map { Uri.parse(it.uri) })
        }
        return pending.intentSender
    }

    fun createRestoreIntent(items: List<SnapItem>): IntentSender {
        require(items.isNotEmpty()) { "恢复批次不能为空" }
        return MediaStore.createTrashRequest(resolver, items.map { Uri.parse(it.uri) }, false).intentSender
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
        TRASH_COLLECTIONS.forEach { collection ->
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

    /** 回收站全量清单（含其他 App 移入的项）：Images + Video 双集合，
     *  MATCH_INCLUDE + is_trashed=1，投影 _id/_display_name/_size/date_expires；
     *  uri 由 collection + _ID 现场构造（与 MediaStoreDataSource 同口径）。
     *  按 dateExpiresSec 升序（最紧急先），无到期时间的项排最后；0 行 = 空态。 */
    suspend fun listTrashed(): List<TrashEntry> = withContext(Dispatchers.IO) {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "is_trashed=1")
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        val result = ArrayList<TrashEntry>()
        TRASH_COLLECTIONS.forEach { collection ->
            resolver.query(collection, projection, args, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val expiresIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
                val str: (Int) -> String = { i -> if (i < 0 || cursor.isNull(i)) "" else cursor.getString(i) ?: "" }
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    result += TrashEntry(
                        item = SnapItem(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            displayName = str(nameIndex),
                            sizeBytes = if (sizeIndex < 0 || cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex),
                        ),
                        dateExpiresSec = if (expiresIndex < 0 || cursor.isNull(expiresIndex)) null else cursor.getLong(expiresIndex),
                    )
                }
            }
        }
        result.sortedBy { it.dateExpiresSec ?: Long.MAX_VALUE }
    }
}

/** 失败批降档逻辑已收编至 domain.settle.BatchFlow（纯 JVM 可测）；本类只保留 MediaStore 写入与回查。 */
