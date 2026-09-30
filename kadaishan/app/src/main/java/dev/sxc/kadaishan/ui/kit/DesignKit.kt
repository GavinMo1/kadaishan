package dev.sxc.kadaishan.ui.kit

import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

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

    // —— motion（弹簧词汇表：按交互原型分级；全部近临界阻尼、克制过冲，无散点动效）——
    /** 空间位移（新卡缓入 / 选中片滑动）：带 10% 活性的临界阻尼。 */
    fun <T> spatial(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /** 状态过渡（chrome 显隐 / 跟手回弹）：无过冲的干脆到位。 */
    fun <T> settle(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)

    /** 按压卡扣（Click 原型 · 磁铁吸合）：快而微过冲——按钮按压缩放专用。 */
    fun <T> tap(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.62f, stiffness = 2600f)

    /** 甩出退场（Flow 快档）：急促带活性——换卡退场专用；行程由松手速度缩放（速度接续）。 */
    fun <T> fling(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.86f, stiffness = 750f)

    /** 抽屉（Slide 原型 · 气压关门器）：无过冲沉稳到顶——抽屉入场 / 回弹。 */
    fun <T> drawer(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = 550f)

    /** 落定（Bounce 轻档）：轻微过冲的满足感——勾选落定等达成动作。 */
    fun <T> pop(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.62f, stiffness = 700f)
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

/** 按压缩放动画值（tap 卡扣弹簧；reducedMotion 直切）——按钮「按下有物理感」的唯一来源。 */
@Composable
fun rememberPressScale(pressed: Boolean, pressedScale: Float = 0.95f): Float {
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (reduced) snap() else Daylight.tap(),
        label = "pressScale",
    )
    return scale
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
    val pressScale = rememberPressScale(pressed)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
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
    val pressScale = rememberPressScale(pressed)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
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
    val pressScale = rememberPressScale(pressed)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
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

/**
 * 媒体面上的手势方向浮标（图片/视频板共用）：随拖动进度渐显（14f 起步、26f 满显，px），
 * 跟手倾斜与卡片同步。side = -1 左缘（响应左拖）、+1 右缘（右拖）；
 * vertical = true 底缘（下拉撤回，随 follow 渐显、不倾斜）。
 * follow 惰性读取动画状态（graphicsLayer 内）→ 逐帧不重组。
 */
@Composable
fun DragHintBadge(
    text: String,
    bg: Color,
    fg: Color,
    side: Int,
    follow: () -> Float,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    Box(
        modifier = modifier
            .graphicsLayer {
                val v = follow()
                val progress = ((side * v - 14f) / 26f).coerceIn(0f, 1f)
                alpha = progress
                val s = 0.9f + 0.1f * progress
                scaleX = s
                scaleY = s
                if (!vertical) rotationZ = v * 0.12f
            }
            .clip(RoundedCornerShape(Daylight.pillRadius))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
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

/** 圆环勾选（iOS 多选语汇）：未选灰环 → 选中 Action Blue 实底 + 白勾；选中带 pop 轻过冲落定。 */
@Composable
fun CircleCheck(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val reduced = rememberReducedMotion()
    val pop = remember { Animatable(1f) }
    LaunchedEffect(checked) {
        if (checked && !reduced) {
            pop.snapTo(0.72f)
            pop.animateTo(1f, Daylight.pop())
        } else {
            pop.snapTo(1f)
        }
    }
    Box(
        modifier = modifier
            .size(24.dp)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
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
 * 弹簧抽屉（Slide 原型 · 气压关门器）：抽屉类浮层的物理层——
 * 入场 = drawer() 自底到顶 + 遮罩渐显；把手垂直拖拽跟手（位移 ≥140dp 判 dismiss，否则 drawer() 回弹）；
 * 点遮罩 / 系统返回 = 先退场动画再移除组合。自绘替代 M3 ModalBottomSheet（平台曲线与本体系不合）。
 * closable = false 时拒绝关闭（结算面板 CONFIRMING/VERIFYING 相位守卫）：把手可拉但回弹、
 * 遮罩/返回无效——锁的是关闭，不锁反馈。
 */
@Composable
fun SpringSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    closable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val reduced = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val fraction = remember { Animatable(if (reduced) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }
    // 把手拖拽跟手：目标值直写 + drawer 弹簧追踪（与卡片跟手同模式，热路径零分配）
    var dragTarget by remember { mutableStateOf(0f) }
    val dragY by animateFloatAsState(
        targetValue = dragTarget,
        animationSpec = if (reduced) snap() else Daylight.drawer(),
        label = "sheetDrag",
    )
    var sheetHeightPx by remember { mutableStateOf(1f) }

    fun dismissAnimated() {
        if (closing || !closable) return
        closing = true
        scope.launch {
            fraction.animateTo(0f, if (reduced) snap() else Daylight.drawer())
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        fraction.animateTo(1f, if (reduced) snap() else Daylight.drawer())
    }

    Dialog(
        onDismissRequest = { dismissAnimated() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .pointerInput(closing) {
                    detectTapGestures { if (!closing) dismissAnimated() }
                },
        ) {
            // 遮罩：随入场进度渐显（黑 @20%）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.2f * fraction.value }
                    .background(Color.Black),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { if (it.height > 0) sheetHeightPx = it.height.toFloat() }
                    .graphicsLayer {
                        translationY = (1f - fraction.value) * sheetHeightPx + dragY
                    }
                    .clip(RoundedCornerShape(topStart = Daylight.radiusLg, topEnd = Daylight.radiusLg))
                    .background(Daylight.canvas)
                    // 吃掉内容区点按（不透传到遮罩的点击关闭层）；拖拽 dismiss 只挂在把手行
                    .pointerInput(closing) { detectTapGestures { } }
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .pointerInput(closing) {
                            val dismissPx = 140.dp.toPx()
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    if (!closing) dragTarget = (dragTarget + amount).coerceAtLeast(0f)
                                },
                                onDragEnd = {
                                    if (!closing && dragTarget >= dismissPx && closable) dismissAnimated() else dragTarget = 0f
                                },
                                onDragCancel = { if (!closing) dragTarget = 0f },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 5.dp)
                            .clip(RoundedCornerShape(Daylight.pillRadius))
                            .background(Daylight.dividerSoft),
                    )
                }
                content()
            }
        }
    }
}

/**
 * 信息抽屉（图片/视频板共用）：当前媒体头图（150dp Crop、radiusMd、米白衬底）
 * + 米白分组行（label 墨 17 / 值右对齐弱墨 17、48dp 行、dividerSoft 分隔）。
 * 下滑把手 / 点遮罩 / 系统返回关闭；卡片本体不重复承载文件信息。
 */
@Composable
fun InfoSheet(
    onDismiss: () -> Unit,
    rows: List<Pair<String, String>>,
    thumbModel: Any?,
    thumbLoader: ImageLoader,
    thumbContentDescription: String? = null,
) {
    SpringSheet(onDismiss = onDismiss) {
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
