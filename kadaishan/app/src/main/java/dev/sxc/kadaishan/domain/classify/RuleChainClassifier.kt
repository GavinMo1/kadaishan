package dev.sxc.kadaishan.domain.classify

import dev.sxc.kadaishan.domain.ImageClassifier
import dev.sxc.kadaishan.model.ImageCategory
import dev.sxc.kadaishan.model.MediaItem

/** 规则链分类器（PRD §3.2）：读 MediaItem 的 relativePath / bucketName / displayName 三个字符串字段，
 *  委托纯函数 [classifyFields] 完成判定；不读 dateTaken/分辨率/EXIF（禁用判据）。 */
class RuleChainClassifier : ImageClassifier {

    override fun classify(item: MediaItem): ImageCategory =
        classifyFields(item.relativePath, item.bucketName, item.displayName)
}
