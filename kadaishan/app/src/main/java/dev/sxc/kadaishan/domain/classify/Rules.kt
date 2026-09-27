package dev.sxc.kadaishan.domain.classify

import dev.sxc.kadaishan.model.ImageCategory

// ═══ PRD §3.2 图片三分类规则链：1 截图 → 2 相机照片 → 3 兜底 SAVED（每张图唯一归类）═══
// 判据白名单：relativePath / bucketName / displayName。禁用 DATE_TAKEN/日期、分辨率、EXIF。

/** 路径规范化：统一分隔符（\ → /）、去首尾空白、去首尾斜杠；比较前 lowercase（大小写不敏感）。
 *  尾部斜杠差异由 trim('/') 消除——"Pictures/Screenshots" 与 "Pictures/Screenshots/" 等价。 */
private fun normalize(raw: String): String =
    raw.trim()
        .replace('\\', '/')
        .trim('/')
        .lowercase()

/** field 是否命中目录 dir：等于 dir、位于 dir 之下，或（field 不含分隔符时）等于 dir 的末段目录名——
 *  bucketName 只带末段名（如 "截屏"、"Screenshots"），仍须能命中候选集。 */
private fun inDir(field: String, dir: String): Boolean {
    val f = normalize(field)
    val d = normalize(dir)
    return f == d || f.startsWith("$d/") || ('/' !in f && d.endsWith("/$f"))
}

/** 截图判定（PRD §3.2）：relativePath 或 bucketName 命中目录候选集，或命名前缀 "Screenshot_"。 */
internal object ScreenshotRule {

    /** 目录候选集（G0-4 真机校准：Pictures/Screenshots/ 与 Pictures/截屏/ 是双目录，必须都命中）。 */
    private val DIRECTORIES = listOf(
        "Pictures/Screenshots/",
        "Pictures/截屏/",
        "DCIM/Screenshots/",
        "Screenshot/", // 主存储根目录下的 Screenshot/
    )

    private const val NAME_PREFIX = "Screenshot_"

    fun matches(relativePath: String, bucketName: String, displayName: String): Boolean =
        displayName.startsWith(NAME_PREFIX) ||
            DIRECTORIES.any { inDir(relativePath, it) || inDir(bucketName, it) }
}

/** 相机判定（PRD §3.2）：relativePath 规范化后 == "DCIM/Camera/"，辅助：命名前缀 "IMG_"。 */
internal object CameraRule {

    private const val DIRECTORY = "DCIM/Camera/"
    private const val NAME_PREFIX = "IMG_"

    fun matches(relativePath: String, displayName: String): Boolean =
        normalize(relativePath) == normalize(DIRECTORY) || displayName.startsWith(NAME_PREFIX)
}

/** 三分类纯函数（顺序链：截图 → 相机 → 兜底 SAVED），纯 JVM，单测不触碰 android.*。 */
internal fun classifyFields(relativePath: String, bucketName: String, displayName: String): ImageCategory =
    when {
        ScreenshotRule.matches(relativePath, bucketName, displayName) -> ImageCategory.SCREENSHOT
        CameraRule.matches(relativePath, displayName) -> ImageCategory.CAMERA
        else -> ImageCategory.SAVED
    }
