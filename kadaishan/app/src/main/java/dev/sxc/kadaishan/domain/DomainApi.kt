package dev.sxc.kadaishan.domain

import dev.sxc.kadaishan.model.ImageCategory
import dev.sxc.kadaishan.model.MediaItem
import dev.sxc.kadaishan.model.MediaKey
import dev.sxc.kadaishan.model.MediaQuery
import dev.sxc.kadaishan.model.Page

// ═══ 扩展点契约（卡待删-架构设计.md v2.2 §6；原则：每个接口当期必须有真实调用方）═══

/** 媒体查询唯一入口（架构 §0 数据层收口）。
 *  回查不走此接口：回查只认 RecheckRow 三列（MediaDeleter，刀 3 落地）。 */
interface MediaSource {
    /** 分页读取。
     *  `after` = 已扫描行数游标（limit+offset 口径——架构 §7 明示"keyset 或 limit+offset"均可；≤3000 条规模）；
     *  null = 从头开始。返回 items ≤ limit（过滤后命中数）；
     *  nextKey = 新的已扫描行数，null = 已穷尽。 */
    suspend fun page(query: MediaQuery, after: MediaKey?, limit: Int): Page<MediaItem>

    /** 按 id 取详情（随机路径按窗口取数 / 后续窗口详情、结果页取数）。 */
    suspend fun queryByIds(ids: List<MediaKey>, matchTrashed: Int): List<MediaItem>

    /** 随机排序独立 shuffle 路径（PRD §4.4）：轻量投影（_ID+分类键列），返回板块内命中 id，
     *  按查询排序序（Random 时稳定 _ID 序）；shuffle 由调用方注入 Random 的纯函数完成。 */
    suspend fun ids(query: MediaQuery): List<MediaKey>
}

/** 分类器（调用方：板块查询）——v1 分类仅图片（PRD §3.2 规则链）。
 *  录屏识别（扩展 §1.1）启用时扩视频分类枚举，签名不动。 */
interface ImageClassifier {
    fun classify(item: MediaItem): ImageCategory
}
