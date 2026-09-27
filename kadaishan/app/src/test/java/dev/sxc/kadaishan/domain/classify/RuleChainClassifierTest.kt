package dev.sxc.kadaishan.domain.classify

import dev.sxc.kadaishan.model.ImageCategory
import dev.sxc.kadaishan.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** PRD §3.2 图片三分类规则链单测（JUnit4，纯 JVM，运行期不触碰 android.*）。
 *  路径/命名判据走纯函数 classifyFields；禁用判据走 RuleChainClassifier.classify(MediaItem)。 */
class RuleChainClassifierTest {

    // —— 截图：目录候选集 ——

    @Test
    fun `目录 Pictures Screenshots 命中截图`() {
        assertEquals(ImageCategory.SCREENSHOT, classifyFields("Pictures/Screenshots/", "", "a.png"))
    }

    @Test
    fun `目录 Pictures 截屏 命中截图`() {
        assertEquals(ImageCategory.SCREENSHOT, classifyFields("Pictures/截屏/", "", "a.png"))
    }

    @Test
    fun `目录 DCIM Screenshots 命中截图`() {
        assertEquals(ImageCategory.SCREENSHOT, classifyFields("DCIM/Screenshots/", "", "a.png"))
    }

    @Test
    fun `根目录 Screenshot 命中截图`() {
        assertEquals(ImageCategory.SCREENSHOT, classifyFields("Screenshot/", "", "a.png"))
    }

    @Test
    fun `bucketName 是截屏目录名时命中截图`() {
        assertEquals(ImageCategory.SCREENSHOT, classifyFields("Pictures/其他/", "截屏", "a.png"))
    }

    // —— 截图：命名判据与路径规范化 ——

    @Test
    fun `Screenshot 前缀命名命中截图`() {
        assertEquals(ImageCategory.SCREENSHOT, classifyFields("Download/", "Download", "Screenshot_20260926.png"))
    }

    @Test
    fun `路径统一分隔符 大小写不敏感 允许无尾斜杠`() {
        assertEquals(ImageCategory.SCREENSHOT, classifyFields("pictures\\Screenshots", "", "a.png"))
    }

    // —— 相机 ——

    @Test
    fun `目录 DCIM Camera 命中相机`() {
        assertEquals(ImageCategory.CAMERA, classifyFields("DCIM/Camera/", "Camera", "a.jpg"))
    }

    @Test
    fun `IMG 前缀命名且非截图目录命中相机`() {
        assertEquals(ImageCategory.CAMERA, classifyFields("Pictures/", "Pictures", "IMG_1234.jpg"))
    }

    // —— 兜底 SAVED ——

    @Test
    fun `Download 目录兜底 SAVED`() {
        assertEquals(ImageCategory.SAVED, classifyFields("Download/", "Download", "photo.jpg"))
    }

    @Test
    fun `Pictures WeiXin 目录兜底 SAVED`() {
        assertEquals(ImageCategory.SAVED, classifyFields("Pictures/WeiXin/", "WeiXin", "wx_1.jpg"))
    }

    @Test
    fun `Android media 微信目录兜底 SAVED`() {
        assertEquals(ImageCategory.SAVED, classifyFields("Android/media/com.tencent.mm/", "com.tencent.mm", "0001.jpg"))
    }

    @Test
    fun `非标目录兜底 SAVED`() {
        assertEquals(ImageCategory.SAVED, classifyFields("Misc/Custom/", "Custom", "a.png"))
    }

    @Test
    fun `相机文件移入 Download 且无 IMG 前缀不误判为相机`() {
        assertEquals(ImageCategory.SAVED, classifyFields("Download/", "Download", "PXL_20260926_030405123.jpg"))
    }

    // —— 唯一归类 / 顺序优先：截图规则先于相机规则 ——

    @Test
    fun `截图目录内 IMG 文件唯一归类为截图`() {
        assertEquals(ImageCategory.SCREENSHOT, classifyFields("Pictures/Screenshots/", "Screenshots", "IMG_0001.jpg"))
    }

    // —— 禁用判据：同路径同名，仅 dateTakenMs/width 不同 → 分类相同 ——

    @Test
    fun `同路径同名仅 dateTakenMs 与 width 不同则分类相同`() {
        val classifier = RuleChainClassifier()
        val withoutMeta = mediaItem(
            relativePath = "Pictures/截屏/",
            bucketName = "截屏",
            displayName = "PXL_20260926_120000.jpg",
            dateTakenMs = null,
            width = 0,
        )
        val withMeta = mediaItem(
            relativePath = "Pictures/截屏/",
            bucketName = "截屏",
            displayName = "PXL_20260926_120000.jpg",
            dateTakenMs = 1_756_228_800_000L,
            width = 4032,
        )
        assertEquals(ImageCategory.SCREENSHOT, classifier.classify(withoutMeta))
        assertEquals(ImageCategory.SCREENSHOT, classifier.classify(withMeta))
    }

    // —— MediaItem 构造辅助：android.net.Uri 在纯 JVM 单测里没有可用实例（android.jar 全是桩，
    //    构造/静态方法不可用），而分类只读 MediaItem 的三个字符串字段——用 Unsafe 分配实例绕过
    //    构造器，仅回填分类字段与对照字段（dateTakenMs/width），运行期不加载、不调用任何 android.* 代码。——

    private fun mediaItem(
        relativePath: String,
        bucketName: String,
        displayName: String,
        dateTakenMs: Long?,
        width: Int,
    ): MediaItem {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val item = allocate.invoke(theUnsafe, MediaItem::class.java) as MediaItem
        setField(item, "relativePath", relativePath)
        setField(item, "bucketName", bucketName)
        setField(item, "displayName", displayName)
        setField(item, "dateTakenMs", dateTakenMs)
        setField(item, "width", width)
        return item
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
