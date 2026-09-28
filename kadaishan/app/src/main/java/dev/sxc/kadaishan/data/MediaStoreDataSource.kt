package dev.sxc.kadaishan.data

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import dev.sxc.kadaishan.domain.ImageClassifier
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.domain.classify.classifyFields
import dev.sxc.kadaishan.domain.sort.toOrderBy
import dev.sxc.kadaishan.model.Board
import dev.sxc.kadaishan.model.ImageCategory
import dev.sxc.kadaishan.model.MediaItem
import dev.sxc.kadaishan.model.MediaKey
import dev.sxc.kadaishan.model.MediaQuery
import dev.sxc.kadaishan.model.Page
import dev.sxc.kadaishan.model.SortSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═══ MediaStore 单一数据层（架构 §0 数据层收口；§7 分页 = limit+offset，≤3000 条规模）═══

/** 默认 projection（架构 §2）：11 列一次取回；uri 由 collection + _ID 现场构造，不查 URI 列。 */
private val PROJECTION = arrayOf(
    MediaStore.MediaColumns._ID,
    MediaStore.MediaColumns.DISPLAY_NAME,
    MediaStore.MediaColumns.MIME_TYPE,
    MediaStore.MediaColumns.SIZE,
    MediaStore.MediaColumns.WIDTH,
    MediaStore.MediaColumns.HEIGHT,
    MediaStore.MediaColumns.DURATION,
    MediaStore.MediaColumns.DATE_TAKEN,
    MediaStore.MediaColumns.DATE_ADDED,
    MediaStore.MediaColumns.RELATIVE_PATH,
    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
)

/** 轻量投影（DomainApi.ids：_ID + 分类键列，板块内过滤所需最小列集；仍远小于全列）。 */
private val ID_PROJECTION = arrayOf(
    MediaStore.MediaColumns._ID,
    MediaStore.MediaColumns.RELATIVE_PATH,
    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
    MediaStore.MediaColumns.DISPLAY_NAME,
)

/** 图片/视频两个视图：_ID 互斥（media_type 单值），queryByIds 双集合查全不重。 */
private val ALL_COLLECTIONS = arrayOf(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
)

class MediaStoreDataSource(
    private val resolver: ContentResolver,
    private val classifier: ImageClassifier,
) : MediaSource {

    override suspend fun page(query: MediaQuery, after: MediaKey?, limit: Int): Page<MediaItem> =
        withContext(Dispatchers.IO) {
            val collection = collectionFor(query.board)
            val accept = acceptFor(query.board)
            val skip = (after ?: 0L).toInt()
            val args = queryArgs(query.matchTrashed, sortOrder = query.sort.toOrderBy())
            val cursor = resolver.query(collection, PROJECTION, args, null)
                ?: return@withContext Page(emptyList(), null)
            cursor.use { c ->
                // 行 → 惰性 Sequence：仅窗口物化，全量不进内存（架构 §0/§2）
                val reader = MediaItemReader(c, collection)
                val rows = c.rows().map { reader.read() }
                val (window, scanned) = collectWindow(rows, accept, skip, limit)
                Page(window, if (window.size < limit) null else (skip + scanned).toLong())
            }
        }

    override suspend fun queryByIds(ids: List<MediaKey>, matchTrashed: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyList()
            val selection = ids.joinToString(prefix = "_ID IN (", postfix = ")", separator = ",") { "?" }
            val selectionArgs = Array(ids.size) { ids[it].toString() }
            val args = queryArgs(matchTrashed, selection, selectionArgs)
            val result = ArrayList<MediaItem>(ids.size)
            for (collection in ALL_COLLECTIONS) {
                resolver.query(collection, PROJECTION, args, null)?.use { c ->
                    val reader = MediaItemReader(c, collection)
                    while (c.moveToNext()) result.add(reader.read())
                }
            }
            result
        }

    override suspend fun ids(query: MediaQuery): List<MediaKey> =
        withContext(Dispatchers.IO) {
            resolver.query(
                collectionFor(query.board),
                ID_PROJECTION,
                queryArgs(
                    query.matchTrashed,
                    sortOrder = if (query.sort is SortSpec.Random) "_ID ASC" else query.sort.toOrderBy(),
                ),
                null,
            )?.use { c ->
                val idIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                // 板块内过滤（编排者裁决 2026-09-27）：图片三板逐行走 classifyFields；
                // VIDEO 集合天然全视频（media_type=VIDEO），target=null 短路、无谓词
                val target = when {
                    // 统一图片板块（PRD §3.1）：全部图片入池，分类规则降级为筛选器
                    query.mergeImages -> null
                    query.board == Board.PHOTO -> ImageCategory.CAMERA
                    query.board == Board.SCREENSHOT -> ImageCategory.SCREENSHOT
                    query.board == Board.SAVED -> ImageCategory.SAVED
                    else -> null
                }
                val pathIndex = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val bucketIndex = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val nameIndex = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val str: (Int) -> String = { i -> if (i < 0 || c.isNull(i)) "" else c.getString(i) ?: "" }
                val result = ArrayList<MediaKey>()
                while (c.moveToNext()) {
                    val matched = target == null ||
                        classifyFields(str(pathIndex), str(bucketIndex), str(nameIndex)) == target
                    if (matched) result.add(c.getLong(idIndex))
                }
                result
            } ?: emptyList()
        }

    /** 板块谓词：VIDEO → mimeType 前缀；图片三板 → 分类器映射板块（PRD §3.2 规则链）。 */
    private fun acceptFor(board: Board): (MediaItem) -> Boolean = when (board) {
        Board.VIDEO -> { item -> item.mimeType.startsWith("video/") }
        Board.PHOTO -> { item -> classifier.classify(item) == ImageCategory.CAMERA }
        Board.SCREENSHOT -> { item -> classifier.classify(item) == ImageCategory.SCREENSHOT }
        Board.SAVED -> { item -> classifier.classify(item) == ImageCategory.SAVED }
    }
}

/** 窗口收集（真实调用方 = page 的 cursor 行序列；架构 §7 limit+offset）。
 *  跳过前 skip 行（按行计数，含未命中行），随后扫描收集命中直至 limit 行或穷尽；
 *  第二元素 = 本窗跳过 skip 后实际迭代的行数（含未命中行），供 nextKey = skip + scanned 推进。 */
internal fun <T> collectWindow(
    rows: Sequence<T>,
    accept: (T) -> Boolean,
    skip: Int,
    limit: Int,
): Pair<List<T>, Int> {
    val window = ArrayList<T>(limit.coerceAtLeast(0))
    val iterator = rows.iterator()
    var toSkip = skip
    while (toSkip > 0 && iterator.hasNext()) {
        iterator.next()
        toSkip--
    }
    var scanned = 0
    while (window.size < limit && iterator.hasNext()) {
        val row = iterator.next()
        scanned++
        if (accept(row)) window.add(row)
    }
    return window to scanned
}

/** 板 → 集合（共享契约：VIDEO → Video，其余三板 → Images）。 */
private fun collectionFor(board: Board): Uri =
    if (board == Board.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

/** 查询参数（API 26+ Bundle 口径——唯一能同时携带 MATCH_TRASHED 与 SQL 的通道）：
 *  MATCH_TRASHED 谓词 + selection/sortOrder 走 ContentResolver.QUERY_ARG_SQL_* 标准键
 *  （框架5参 query 内部即如此包装，MediaProvider 按同键读取）。 */
private fun queryArgs(
    matchTrashed: Int,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
): Bundle = Bundle().apply {
    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, matchTrashed)
    if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
    if (selectionArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
    if (sortOrder != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
}

/** cursor 逐行惰性序列（Cursor 非 Iterable；yield 后行保持在当前位，消费方读完再前进）。 */
private fun Cursor.rows(): Sequence<Cursor> = sequence {
    while (moveToNext()) yield(this@rows)
}

/** 行 → MediaItem（架构 §2 空值策略：缺列/NULL → width/height/size 0、duration/dateTaken null、字符串 ""；
 *  禁作主判据）。列索引每 cursor 解析一次，行循环零重复查找。 */
private class MediaItemReader(private val cursor: Cursor, private val collection: Uri) {
    private val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
    private val displayNameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
    private val mimeTypeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
    private val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
    private val widthIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
    private val heightIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
    private val durationIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
    private val dateTakenIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
    private val dateAddedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
    private val relativePathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
    private val bucketNameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

    fun read(): MediaItem {
        val id = longOr(idIdx, 0L)
        return MediaItem(
            id = id,
            uri = ContentUris.withAppendedId(collection, id),
            displayName = stringOr(displayNameIdx),
            mimeType = stringOr(mimeTypeIdx),
            sizeBytes = longOr(sizeIdx, 0L),
            width = intOr(widthIdx, 0),
            height = intOr(heightIdx, 0),
            durationMs = longOrNull(durationIdx),
            dateTakenMs = longOrNull(dateTakenIdx),
            dateAddedSec = longOr(dateAddedIdx, 0L),
            relativePath = stringOr(relativePathIdx),
            bucketName = stringOr(bucketNameIdx),
        )
    }

    private fun longOr(index: Int, default: Long): Long =
        if (index < 0 || cursor.isNull(index)) default else cursor.getLong(index)

    private fun longOrNull(index: Int): Long? =
        if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)

    private fun intOr(index: Int, default: Int): Int =
        if (index < 0 || cursor.isNull(index)) default else cursor.getInt(index)

    private fun stringOr(index: Int): String =
        if (index < 0 || cursor.isNull(index)) "" else cursor.getString(index) ?: ""
}
