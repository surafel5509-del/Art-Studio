package com.artforge.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artforge.mobile.data.settings.ThemeMode

/**
 * ArtForge palette.
 *
 * A neutral, slightly blue-shifted graphite scale (so the artwork is the most saturated
 * thing on screen) with one confident accent. Surfaces step up in three planes —
 * canvas backdrop, panel, raised card — which is what makes the workspace read as a
 * professional tool rather than a stack of boxes.
 */
object ForgeColors {
    // Backdrop
    val Charcoal = Color(0xFF0E1013)
    val CharcoalDeep = Color(0xFF08090B)
    val Graphite = Color(0xFF15171C)
    val GraphiteElevated = Color(0xFF1C1F26)
    val GraphiteRaised = Color(0xFF23272F)
    val GraphiteBorder = Color(0xFF2B3038)
    val Hairline = Color(0xFF20242B)

    // Brand
    val Accent = Color(0xFF7B8CFF)
    val AccentPressed = Color(0xFF6274F5)
    val AccentWarm = Color(0xFFFF9160)
    val AccentSoft = Color(0xFF9CB0D6)
    val Success = Color(0xFF4CD9A0)
    val Danger = Color(0xFFFF5C7A)
    val Warning = Color(0xFFFFC752)

    // Text
    val TextPrimary = Color(0xFFF4F6FA)
    val TextSecondary = Color(0xFFA2ABBA)
    val TextMuted = Color(0xFF6B7482)

    // Light theme
    val LightSurface = Color(0xFFF2F4F8)
    val LightPanel = Color(0xFFFFFFFF)
    val LightBorder = Color(0xFFE1E5EC)
    val LightText = Color(0xFF111317)
    val LightTextSecondary = Color(0xFF5A6270)
}

data class ForgeDimensions(
    val panelCorner: Int = 16,
    val touchTarget: Int = 44,
    val toolButton: Int = 52,
    val timelineRowHeight: Int = 44,
    val layerRowHeight: Int = 56,
)

val LocalForgeDimensions = staticCompositionLocalOf { ForgeDimensions() }
val LocalIsDarkTheme = staticCompositionLocalOf { true }

private val Sans = FontFamily.SansSerif

private val ForgeTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        letterSpacing = (-0.8).sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    ),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 11.5.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 9.5.sp,
        letterSpacing = 0.6.sp,
    ),
)

private val DarkScheme = darkColorScheme(
    primary = ForgeColors.Accent,
    onPrimary = Color(0xFF0A0B0F),
    primaryContainer = Color(0xFF2B3563),
    onPrimaryContainer = Color(0xFFE2E8FF),
    secondary = ForgeColors.AccentWarm,
    onSecondary = Color(0xFF23120A),
    secondaryContainer = Color(0xFF4A2A18),
    onSecondaryContainer = Color(0xFFFFE0CE),
    tertiary = ForgeColors.AccentSoft,
    background = ForgeColors.Charcoal,
    onBackground = ForgeColors.TextPrimary,
    surface = ForgeColors.Graphite,
    onSurface = ForgeColors.TextPrimary,
    surfaceVariant = ForgeColors.GraphiteElevated,
    onSurfaceVariant = ForgeColors.TextSecondary,
    surfaceContainer = ForgeColors.Graphite,
    surfaceContainerLow = ForgeColors.Charcoal,
    surfaceContainerHigh = ForgeColors.GraphiteElevated,
    surfaceContainerHighest = ForgeColors.GraphiteRaised,
    outline = ForgeColors.GraphiteBorder,
    outlineVariant = ForgeColors.Hairline,
    error = ForgeColors.Danger,
    onError = Color(0xFF20060A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4457E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE2FF),
    onPrimaryContainer = Color(0xFF0B1740),
    secondary = Color(0xFFC2591E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0CE),
    onSecondaryContainer = Color(0xFF3A1503),
    background = ForgeColors.LightSurface,
    onBackground = ForgeColors.LightText,
    surface = ForgeColors.LightPanel,
    onSurface = ForgeColors.LightText,
    surfaceVariant = Color(0xFFEBEEF3),
    onSurfaceVariant = ForgeColors.LightTextSecondary,
    surfaceContainer = Color(0xFFF7F8FB),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFEDF0F5),
    outline = ForgeColors.LightBorder,
    outlineVariant = Color(0xFFE9ECF1),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/** Softer, more product-like corner language than the Material defaults. */
private val ForgeShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun ArtForgeTheme(
    themeMode: ThemeMode = ThemeMode.Dark,
    highContrast: Boolean = false,
    largeTargets: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val base = if (dark) DarkScheme else LightScheme
    val scheme = if (highContrast) {
        base.copy(
            onBackground = if (dark) Color.White else Color.Black,
            onSurface = if (dark) Color.White else Color.Black,
            onSurfaceVariant = if (dark) Color(0xFFCFD6E2) else Color(0xFF31383F),
            outline = if (dark) Color(0xFF55606E) else Color(0xFF9AA2AE),
        )
    } else {
        base
    }
    val dimensions = ForgeDimensions(
        touchTarget = if (largeTargets) 56 else 44,
        toolButton = if (largeTargets) 60 else 52,
        layerRowHeight = if (largeTargets) 64 else 56,
        timelineRowHeight = if (largeTargets) 52 else 44,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalForgeDimensions provides dimensions,
        LocalIsDarkTheme provides dark,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ForgeTypography,
            shapes = ForgeShapes,
            content = content,
        )
    }
}

/** Convenience accessor for the app's accent gradient. */
val forgeAccentGradient: List<Color>
    get() = listOf(ForgeColors.Accent, Color(0xFF9B7BFF))

val panelPadding = 14.dp
