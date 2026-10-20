package dev.sxc.kadaishan.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.sxc.kadaishan.ui.kit.Daylight

/**
 * Apple 语汇色板：全部值引用 [Daylight] 单一色源，此处不写裸 hex。
 * 始终浅色——影像优先的画廊基线，不跟随系统深色。
 * surfaceContainer 全系 + inverse* 显式收编：不留 M3 基线紫调灰的泄漏面
 * （Card / AlertDialog / Switch / Snackbar 默认都取这些角色）。
 */
private val AppleScheme = lightColorScheme(
    primary = Daylight.primary,
    onPrimary = Daylight.onPrimary,
    primaryContainer = Daylight.primaryContainer,
    onPrimaryContainer = Daylight.onPrimaryContainer,
    inversePrimary = Daylight.primaryOnDark,
    secondary = Daylight.secondary,
    onSecondary = Daylight.onDark,
    secondaryContainer = Daylight.parchment,
    onSecondaryContainer = Daylight.ink,
    tertiary = Daylight.primary,
    onTertiary = Daylight.onPrimary,
    tertiaryContainer = Daylight.primaryContainer,
    onTertiaryContainer = Daylight.onPrimaryContainer,
    background = Daylight.canvas,
    onBackground = Daylight.ink,
    surface = Daylight.canvas,
    onSurface = Daylight.ink,
    surfaceVariant = Daylight.parchment,
    onSurfaceVariant = Daylight.onSurfaceVariant,
    surfaceTint = Daylight.canvas,              // tonal elevation 不改色（全 App 零高度体系，深度只由阴影/hairline 表达）
    surfaceDim = Daylight.parchment,
    surfaceBright = Daylight.canvas,
    surfaceContainerLowest = Daylight.canvas,
    surfaceContainerLow = Daylight.canvas,
    surfaceContainer = Daylight.parchment,
    surfaceContainerHigh = Daylight.canvas,
    surfaceContainerHighest = Daylight.parchment,
    inverseSurface = Daylight.surfaceBlack,     // Snackbar 深底
    inverseOnSurface = Daylight.onDark,
    outline = Daylight.hairline,
    outlineVariant = Daylight.dividerSoft,
    scrim = Color(0xFF000000),
    error = Daylight.danger,
    onError = Daylight.onDark,
    errorContainer = Daylight.errorContainer,
    onErrorContainer = Daylight.onErrorContainer,
)

/**
 * 全 App 字阶：系统默认 fontFamily（不设 fontFamily 即落系统字体），
 * 字重仅 400/600 两档（M3 默认 500 档一律归位）。
 * 规格：bodyLarge 17sp/行高25sp/(-0.022)em、bodyMedium 14sp/(-0.016)em、
 * title* 600/(-0.011)em、labelSmall 12sp/(-0.01)em；未点名的档位沿用 M3 默认字号/行高（400 档）。
 */
val KadaishanTypography: Typography = run {
    val base = Typography()
    Typography(
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.011).em,
        ),
        titleMedium = base.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.011).em,
        ),
        titleSmall = base.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.011).em,
        ),
        // headlineSmall 归位到 Headline 档（22sp/600/负字距）：AlertDialog 标题槽与主张句的默认来源，
        // 不收编会泄 M3 默认 24sp + 零字距（档位外 + 违反全负字距律）
        headlineSmall = base.headlineSmall.copy(
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.011).em,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontSize = 17.sp,
            lineHeight = 25.sp,
            letterSpacing = (-0.022).em,
            fontWeight = FontWeight.Normal,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontSize = 14.sp,
            letterSpacing = (-0.016).em,
            fontWeight = FontWeight.Normal,
        ),
        // bodySmall / labelMedium 归位最小档（12sp/负字距）：M3 默认正字距（+0.4/+0.5sp）是泄漏面
        bodySmall = base.bodySmall.copy(
            fontSize = 12.sp,
            letterSpacing = (-0.01).em,
            fontWeight = FontWeight.Normal,
        ),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Normal),
        labelMedium = base.labelMedium.copy(
            fontSize = 12.sp,
            letterSpacing = (-0.01).em,
            fontWeight = FontWeight.Normal,
        ),
        labelSmall = base.labelSmall.copy(
            fontSize = 12.sp,
            letterSpacing = (-0.01).em,
            fontWeight = FontWeight.Normal,
        ),
    )
}

/** 圆角四档制（5/8/11/18 + pill），无中间档。 */
private val AppleShapes = Shapes(
    extraSmall = RoundedCornerShape(5),
    small = RoundedCornerShape(8),
    medium = RoundedCornerShape(18),
    large = RoundedCornerShape(18),
    extraLarge = RoundedCornerShape(18),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppleScheme,
        shapes = AppleShapes,
        typography = KadaishanTypography,
        content = content,
    )
}
