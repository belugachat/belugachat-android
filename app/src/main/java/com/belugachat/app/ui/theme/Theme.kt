package com.belugachat.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.belugachat.app.R

private val Dark = darkColorScheme(
    primary = Color(0xFF6B93FF),
    onPrimary = Color(0xFF0B1120),
    primaryContainer = Color(0xFF1E2B4D),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFFA1A7B3),
    onSecondary = Color(0xFF16181D),
    secondaryContainer = Color(0xFF262B38),
    onSecondaryContainer = Color(0xFFF3F4F6),
    tertiary = Color(0xFFFBBF24),
    tertiaryContainer = Color(0xFF3B2E12),
    onTertiaryContainer = Color(0xFFFDE68A),
    background = Color(0xFF16181D),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF16181D),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF21242C),
    onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceContainerLowest = Color(0xFF111318),
    surfaceContainerLow = Color(0xFF1B1E25),
    surfaceContainer = Color(0xFF21242C),
    surfaceContainerHigh = Color(0xFF262B38),
    surfaceContainerHighest = Color(0xFF2E323D),
    outline = Color(0xFF3E4452),
    outlineVariant = Color(0xFF2E323D),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF35191F),
    onErrorContainer = Color(0xFFFECACA),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3B6BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEEF3FF),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF4B5563),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF4F6FA),
    onSecondaryContainer = Color(0xFF111827),
    tertiary = Color(0xFFD97706),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF4F6FA),
    onSurfaceVariant = Color(0xFF6B7280),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF4F6FA),
    surfaceContainerHigh = Color(0xFFEAEFF7),
    surfaceContainerHighest = Color(0xFFE5E9F2),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE5E9F2),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEF2F2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

private fun TextStyle.ui(): TextStyle = copy(fontFamily = Manrope)

private val AppTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.ui(),
        displayMedium = base.displayMedium.ui(),
        displaySmall = base.displaySmall.ui(),
        headlineLarge = base.headlineLarge.ui().copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.ui().copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.ui().copy(
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
        ),
        titleLarge = base.titleLarge.ui().copy(
            fontSize = 17.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
        ),
        titleMedium = base.titleMedium.ui().copy(
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
        ),
        titleSmall = base.titleSmall.ui().copy(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
        ),
        bodyLarge = base.bodyLarge.ui().copy(
            fontSize = 14.5.sp,
            lineHeight = 21.5.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.sp,
        ),
        bodyMedium = base.bodyMedium.ui().copy(
            fontSize = 13.5.sp,
            lineHeight = 19.5.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.sp,
        ),
        bodySmall = base.bodySmall.ui().copy(
            fontSize = 12.sp,
            lineHeight = 16.5.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.sp,
        ),
        labelLarge = base.labelLarge.ui().copy(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
        ),
        labelMedium = base.labelMedium.ui().copy(
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.1.sp,
        ),
        labelSmall = base.labelSmall.ui().copy(
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp,
        ),
    )
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.5.sp)

val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun BelugaChatTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
    }
}
