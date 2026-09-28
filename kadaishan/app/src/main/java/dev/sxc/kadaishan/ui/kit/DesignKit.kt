package dev.sxc.kadaishan.ui.kit

import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.ImageLoader
import coil.compose.AsyncImage

/**
 * Apple 语汇设计令牌与组件（原 DESIGN.md「Apple-design-analysis」转写；不复制任何 Apple 资产）。
 * 铁律三条：① 禁装饰渐变（纯白/米白平铺）；② 阴影只给影像（product shadow 一款）；
 * ③ 唯一强调色 Action Blue #0066CC 承担一切"可点"信号。
 */
object Daylight {
    // —— colors ——
    val canvas = Color(0xFFFFFFFF)          // 主画布：纯白平铺
    val parchment = Color(0xFFF5F5F7)       // 米白：分组容器 / 影像衬底 / 次级面
    val ink = Color(0xFF1D1D1F)             // 浅面上全部正文
    val inkStrong = Color(0xFF333333)       // 次级按钮文字
    val inkSub = Color(0xFF6E6E73)          // 弱化文字 / 禁用（对纸白 5.1:1，正文辅助合规；旧值 #7A7A7A 已弃用）
    val primary = Color(0xFF0066CC)         // 唯一强调色
    val primaryFocus = Color(0xFF0071E3)    // 按压 / 焦点
    val onPrimary = Color(0xFFFFFFFF)
    val pearl = Color(0xFFFAFAFC)           // 次级键底
    val hairline = Color(0xFFE0E0E0)        // 1px 分隔
    val dividerSoft = Color(0xFFF0F0F0)     // 软环 / 分段轨
    val checkRing = Color(0xFFC7C7CC)       // 圆环勾选未选态描边
    val chipTranslucent = Color(0xA3D2D2D7) // 媒体面半透圆牌 @64%
    val chromeSurface = Color(0xCCFFFFFF)   // 悬浮 chrome：白 @80%（内容全幅、chrome 退后）
    val primaryOnDark = Color(0xFF2997FF)   // 深底上的强调 / 链接
    val surfaceBlack = Color(0xFF000000)    // 深色面（视频板）
    val onDark = Color(0xFFFFFFFF)          // 深色面前景
    val bodyMuted = Color(0xFFCCCCCC)       // 深色面弱化正文
    val danger = Color(0xFFD70015)          // 危险 / 错误语义色（error 唯一来源）

    // —— M3 colorScheme 专用（唯一定义点；AppTheme 只引用不写 hex）——
    val secondary = Color(0xFF5F5F63)
    val primaryContainer = Color(0xFFD9E7F9)
    val onPrimaryContainer = Color(0xFF003062)
    val onSurfaceVariant = inkSub               // colorScheme.onSurfaceVariant：与弱墨同源（5.1:1）
    val errorContainer = Color(0xFFFDECEA)
    val onErrorContainer = Color(0xFF8A0010)

    // —— shapes（四档制：5/8/11/18 + pill；无中间档）——
    val radiusXs = 5.dp
    val radiusSm = 8.dp
    val radiusMd = 11.dp
    val radiusLg = 18.dp
    val pillRadius = 50.dp
    val cardRadius = 18.dp                  // = radiusLg：影像大卡
    val groupRadius = 18.dp                 // = radiusLg：分组卡

    // —— motion（iOS 弹簧系：近临界阻尼、无过冲；无散点动效）——
    /** 空间位移（卡片进出 / 选中片滑动）：带 10% 活性的临界阻尼。 */
    fun <T> spatial(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /** 状态过渡（chrome 显隐 / 跟手回弹）：无过冲的干脆到位。 */
    fun <T> settle(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)
}

/** 系统级"移除动画"开关（Settings.Global.ANIMATOR_DURATION_SCALE == 0）：作者化动效降级为直切。 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        try {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
}

/** 触觉回执（iOS 质感的行为层；minSdk 35 → 常量直用，无版本分支）。 */
class Haptics(private val view: View) {
    /** 滑动判定达成（跳过 / 翻页）。 */
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    /** 正向确认（标记删除 / 确认提交 / 勾选）。 */
    fun confirm() = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)

    /** 逆向回退（撤回 / 取消标记）。 */
    fun reject() = view.performHapticFeedback(HapticFeedbackConstants.REJECT)

    /** 控件点按（页签 / 开关）。 */
    fun tap() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** 页面底：平铺纯白（禁装饰渐变）。 */
fun Modifier.daylightBackground(): Modifier = background(Daylight.canvas)

/**
 * 最小触控高度 48dp（Material 触控下限 / HIG 44pt 取严）：在不改组件签名的前提下
 * 给既有交互面补足点击面积——内容不足 48dp 时撑到 48dp，内容更高时不限高。
 */
fun Modifier.minTouch(): Modifier = defaultMinSize(minHeight = 48.dp)

/** 卡片描边（零投影 + 1px hairline）——卡片一律用它，不用阴影。 */
val hairlineBorder: BorderStroke = BorderStroke(1.dp, Daylight.hairline)

/**
 * 影像唯一投影（rgba(0,0,0,0.22)）——只允许用在照片/影像上，按钮/卡片/文字一律无阴影。
 */
fun Modifier.productShadow(shape: Shape = RoundedCornerShape(Daylight.radiusLg)): Modifier =
    shadow(
        elevation = 15.dp,
        shape = shape,
        ambientColor = Color(0x38000000),
        spotColor = Color(0x38000000),
    )

/** 媒体面上的半透圆牌（#d2d2d7 @64% + 墨色字）。 */
fun Modifier.onMediaChip(shape: Shape = RoundedCornerShape(Daylight.radiusSm)): Modifier =
    clip(shape).background(Daylight.chipTranslucent, shape)

/**
 * 主键药丸：容器/前景色可参数化（默认 Action Blue + 白字；深底白键 = container 传 onDark）。
 * 规格：pill + 白字 17/400 + 11×22 padding + 按压 scale(0.95)（系统级微交互）。
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = Daylight.primary,
    content: Color = Daylight.onPrimary,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .graphicsLayer {
                val s = if (pressed) 0.95f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(Daylight.pillRadius))
            .background(if (enabled) container else container.copy(alpha = 0.35f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .minTouch()
            .padding(horizontal = 22.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = content,
            fontSize = 17.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

/** 主键药丸的危险变体：底色唯一来源 Daylight.danger（白字对比 5.4:1）。 */
@Composable
fun DangerPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = PillButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled, container = Daylight.danger)

/** 深底次级键（视频板终态等）：白 @14% 半透 + 白字，pill。 */
@Composable
fun OnDarkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .graphicsLayer {
                val s = if (pressed) 0.95f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(Daylight.pillRadius))
            .background(Daylight.onDark.copy(alpha = if (enabled) 0.14f else 0.07f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .minTouch()
            .padding(horizontal = 22.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = Daylight.onDark.copy(alpha = if (enabled) 1f else 0.5f),
            fontSize = 17.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

/**
 * 次级键（浅底）：pearl 底 + ink-muted-80 字 caption 14 + 软环 + 11dp 圆角。
 */
@Composable
fun PearlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .graphicsLayer {
                val s = if (pressed) 0.95f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(Daylight.radiusMd))
            .background(Daylight.pearl)
            .border(1.5.dp, Daylight.dividerSoft, RoundedCornerShape(Daylight.radiusMd))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .minTouch()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = if (enabled) Daylight.inkStrong else Daylight.inkSub.copy(alpha = 0.5f),
            fontSize = 14.sp,
            letterSpacing = (-0.016).em,
        )
    }
}

/** 媒体面上的动作圆片（照片板「信息/撤回」）：半透灰底 + 墨字，radiusSm。 */
@Composable
fun GhostChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .graphicsLayer {
                val s = if (pressed) 0.96f else 1f
                scaleX = s
                scaleY = s
            }
            .onMediaChip(RoundedCornerShape(Daylight.radiusSm))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .minTouch()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = if (enabled) Daylight.ink else Daylight.ink.copy(alpha = 0.4f),
            fontSize = 14.sp,
            letterSpacing = (-0.016).em,
        )
    }
}

/**
 * 分段页签（iOS 分段控件语汇）：软灰轨 + 纯白选中片随选择滑动（弹簧）；
 * 无阴影，选中 600 / 未选 400。
 */
@Composable
fun SegTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReducedMotion()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Daylight.radiusSm))
            .background(Daylight.dividerSoft)
            .padding(3.dp),
    ) {
        val tabWidth = maxWidth / labels.size.toFloat()
        val pill by animateDpAsState(
            targetValue = tabWidth * selectedIndex,
            animationSpec = if (reduced) snap() else Daylight.settle(),
        )
        // 选中片：纯白滑片，占满单项宽度、上下留 3dp 轨距
        Box(
            modifier = Modifier
                .offset(x = pill)
                .size(tabWidth, 30.dp)
                .clip(RoundedCornerShape(Daylight.radiusSm))
                .background(Daylight.canvas),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                val on = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .size(width = tabWidth, height = 30.dp)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Text(
                        text = label,
                        fontSize = 14.sp,
                        color = if (on) Daylight.ink else Daylight.inkStrong,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        letterSpacing = (-0.016).em,
                    )
                }
            }
        }
    }
}

/** 推入式导航栏（子页通用）：‹ 返回（蓝）左置，标题 17/600 居中，透明底。 */
@Composable
fun NavTitleBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().height(48.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Text(
                text = "‹ 返回",
                color = Daylight.primary,
                fontSize = 17.sp,
            )
        }
        androidx.compose.material3.Text(
            text = title,
            color = Daylight.ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** 分组容器（iOS 内嵌分组列表）：米白 radiusLg、零投影零描边；行内自管分隔线。 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Daylight.groupRadius))
            .background(Daylight.parchment),
        content = content,
    )
}

/** 分组标题（分组容器上方）：13sp 弱化字、不参与层级强调。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier.padding(start = 20.dp, top = 22.dp, bottom = 7.dp, end = 16.dp),
        color = Daylight.inkSub,
        fontSize = 13.sp,
        letterSpacing = (-0.01).em,
    )
}

/** 圆环勾选（iOS 多选语汇）：未选灰环 → 选中 Action Blue 实底 + 白勾。 */
@Composable
fun CircleCheck(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (checked) Daylight.primary else Color.Transparent)
            .border(
                width = if (checked) 0.dp else 1.5.dp,
                color = if (checked) Color.Transparent else Daylight.checkRing,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Daylight.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 细环加载指示（替代默认 Material 转圈配色）：Action Blue + 软灰轨。 */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = Daylight.primary,
        trackColor = Daylight.dividerSoft,
        strokeWidth = 2.5.dp,
    )
}

/**
 * 信息抽屉（图片/视频板共用）：当前媒体头图（150dp Crop、radiusMd、米白衬底）
 * + 米白分组行（label 墨 17 / 值右对齐弱墨 17、48dp 行、dividerSoft 分隔）。
 * 下滑 / 点遮罩 / 系统返回关闭；卡片本体不重复承载文件信息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(
    onDismiss: () -> Unit,
    rows: List<Pair<String, String>>,
    thumbModel: Any?,
    thumbLoader: ImageLoader,
    thumbContentDescription: String? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Daylight.canvas,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            if (thumbModel != null) {
                AsyncImage(
                    model = thumbModel,
                    contentDescription = thumbContentDescription,
                    imageLoader = thumbLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(Daylight.radiusMd))
                        .background(Daylight.parchment),
                )
                Spacer(modifier = Modifier.height(14.dp))
            }
            GroupCard {
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = Daylight.dividerSoft,
                            thickness = 1.dp,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Daylight.ink,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        androidx.compose.material3.Text(
                            text = value,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Daylight.inkSub,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                    }
                }
            }
        }
    }
}
