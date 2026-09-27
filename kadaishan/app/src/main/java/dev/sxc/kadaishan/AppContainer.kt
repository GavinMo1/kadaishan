package dev.sxc.kadaishan

import dev.sxc.kadaishan.data.AndroidExifReader
import dev.sxc.kadaishan.data.ExifReader
import dev.sxc.kadaishan.data.MediaStoreDataSource
import dev.sxc.kadaishan.data.MediaDeleter
import dev.sxc.kadaishan.data.PendingSettlementRepository
import dev.sxc.kadaishan.data.SettingsRepository
import dev.sxc.kadaishan.domain.ImageClassifier
import dev.sxc.kadaishan.domain.MediaSource
import dev.sxc.kadaishan.domain.classify.RuleChainClassifier

/** 应用级组装根（共享契约逐字签名；三个成员的构造一字不改）。
 *  用 applicationContext 构造，避免持有 Activity。 */
class AppContainer(context: android.content.Context) {
    val settings = SettingsRepository(context)
    val classifier: ImageClassifier = RuleChainClassifier()
    val mediaSource: MediaSource = MediaStoreDataSource(context.contentResolver, classifier)
    val mediaDeleter = MediaDeleter(context.contentResolver)
    val pendingSettlement = PendingSettlementRepository(context)
    val exifReader: ExifReader = AndroidExifReader(context)
}
