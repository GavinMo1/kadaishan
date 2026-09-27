package dev.sxc.kadaishan.data

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import dev.sxc.kadaishan.model.ExifSummary
import dev.sxc.kadaishan.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

// ═══ EXIF 按需读取（架构 §2：不在查询 projection，逐文件读，IO 调度器；零联网零整图解码）═══

/** 单条目 EXIF 摘要读取（契约签名，架构 §2 按需读取）。 */
interface ExifReader {
    suspend fun read(item: MediaItem): ExifSummary
}

/** 真机实现：content:// 流式读 EXIF（androidx.exifinterface）。
 *  缺失字段 → null（展示层显「—」，禁止用空值作判据）；文件失效 / 解析失败 → 全 null 摘要，不抛给调用方。 */
class AndroidExifReader(private val context: Context) : ExifReader {

    override suspend fun read(item: MediaItem): ExifSummary = withContext(Dispatchers.IO) {
        try {
            val exif = context.contentResolver.openInputStream(item.uri)?.use { input ->
                ExifInterface(input)
            } ?: return@withContext EMPTY

            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
            val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, -1).takeIf { it > 0 }
            val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                .takeIf { it > 0.0 }
                ?.let { String.format(Locale.US, "%.1f mm", it) }
            // GPS 预留位（足迹扩展）：v1 信息卡不展示，但读取成本与本次同流，一并填。
            val latLon = FloatArray(2)
            val hasGps = try {
                exif.getLatLong(latLon)
            } catch (e: Exception) {
                false
            }

            ExifSummary(
                make = make,
                model = model,
                iso = iso,
                focal = focal,
                gpsLat = if (hasGps) latLon[0].toDouble() else null,
                gpsLon = if (hasGps) latLon[1].toDouble() else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 文件已被外部删除 / 非 EXIF 容器 / 解析异常 → 字段全缺，信息卡显「—」，不崩溃（PRD §5.5）
            EMPTY
        }
    }

    private companion object {
        val EMPTY = ExifSummary(null, null, null, null)
    }
}
