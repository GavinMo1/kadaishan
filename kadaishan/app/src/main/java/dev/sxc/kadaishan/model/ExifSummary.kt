package dev.sxc.kadaishan.model

/** EXIF 摘要（契约层，架构 §2 按需读取口径；逐字签名）。
 *  字段缺失一律 null —— 展示层缺失显「—」，禁止用空值作判据（PRD §3.2 / 架构 §2 空值策略）。
 *  gpsLat/gpsLon 为预留位（足迹扩展用，`卡待删-未来扩展.md` §预留点），v1 展示不消费。 */
data class ExifSummary(
    val make: String?,
    val model: String?,
    val iso: Int?,
    val focal: String?,
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
)
