package com.artforge.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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

/** ArtForge palette — deep charcoal workspaces with cool accent tones. */
object ForgeColors {
    val Charcoal = Color(0xFF101114)
    val CharcoalDeep = Color(0xFF0A0B0D)
    val Graphite = Color(0xFF1B1D22)
    val GraphiteElevated = Color(0xFF23262D)
    val GraphiteBorder = Color(0xFF2E323B)
    val Accent = Color(0xFF6C8CFF)
    val AccentWarm = Color(0xFFFF8A4C)
    val AccentSoft = Color(0xFF8FA7C4)
    val Success = Color(0xFF4CD9A0)
    val Danger = Color(0xFFFF5C7A)
    val Warning = Color(0xFFFFC752)
    val TextPrimary = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFF9AA3B2)
    val TextMuted = Color(0xFF5F6773)

    val LightSurface = Color(0xFFF4F5F7)
    val LightPanel = Color(0xFFFFFFFF)
    val LightBorder = Color(0xFFDDE1E7)
    val LightText = Color(0xFF15171A)
    val LightTextSecondary = Color(0xFF5A6270)
}

data class ForgeDimensions(
    val panelCorner: Int = 14,
    val touchTarget: Int = 44,
    val toolButton: Int = 52,
    val timelineRowHeight: Int = 44,
    val layerRowHeight: Int = 56,
)

val LocalForgeDimensions = staticCompositionLocalOf { ForgeDimensions() }
val LocalIsDarkTheme = staticCompositionLocalOf { true }

private val ForgeTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    ),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 9.sp),
)

private val DarkScheme = darkColorScheme(
    primary = ForgeColors.Accent,
    onPrimary = Color(0xFF0B0C0F),
    primaryContainer = Color(0xFF2A3560),
    onPrimaryContainer = Color(0xFFDCE4FF),
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
    surfaceContainerHigh = ForgeColors.GraphiteElevated,
    surfaceContainerHighest = Color(0xFF2A2E36),
    outline = ForgeColors.GraphiteBorder,
    outlineVariant = Color(0xFF252932),
    error = ForgeColors.Danger,
    onError = Color(0xFF20060A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3D5BD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF0B1740),
    secondary = Color(0xFFC25A1E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0CE),
    onSecondaryContainer = Color(0xFF3A1503),
    background = ForgeColors.LightSurface,
    onBackground = ForgeColors.LightText,
    surface = ForgeColors.LightPanel,
    onSurface = ForgeColors.LightText,
    surfaceVariant = Color(0xFFE9ECF1),
    onSurfaceVariant = ForgeColors.LightTextSecondary,
    surfaceContainer = Color(0xFFF7F8FA),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFEDF0F5),
    outline = ForgeColors.LightBorder,
    outlineVariant = Color(0xFFE7EAEF),
    error = Color(0xFFB3261E),
    onError = Color.White,
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
    val scheme = if (dark) DarkScheme else LightScheme
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
            content = content,
        )
    }
}

/** Convenience accessor for the app's accent gradient. */
val forgeAccentGradient: List<Color>
    get() = listOf(ForgeColors.Accent, ForgeColors.AccentWarm)

val panelPadding = 12.dp
