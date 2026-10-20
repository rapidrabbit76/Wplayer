package dev.wplayer.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.wplayer.app.data.model.ThemeMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak

// Material 3 tonal palettes generated from an accent blue (#0097FF) on neutral greys.
private val LightColors = lightColorScheme(
    primary = Color(0xFF00629F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD0E4FF),
    onPrimaryContainer = Color(0xFF001D34),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDEE3EB),
    onSurfaceVariant = Color(0xFF42474E),
    outline = Color(0xFF72777F),
    outlineVariant = Color(0xFFC2C7CF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE0E2E8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCAFF),
    onPrimary = Color(0xFF003256),
    primaryContainer = Color(0xFF00497A),
    onPrimaryContainer = Color(0xFFD0E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFFD7BEE4),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    outline = Color(0xFF8C9199),
    outlineVariant = Color(0xFF42474E),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
)

@Composable
fun WplayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    /** Colors from the wallpaper (Android 12+) instead of the app's blue. */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    // OLED black: only the large background surfaces; containers keep their tint for contrast.
    val colorScheme = if (darkTheme && pureBlack) {
        base.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black)
    } else {
        base
    }
    MaterialTheme(colorScheme = colorScheme, typography = KoreanTypography) {
        CompositionLocalProvider(LocalRippleConfiguration provides FocusVisibleRipple, content = content)
    }
}

/**
 * The standard ripple, with a focus highlight strong enough to follow from a
 * sofa: a remote or keyboard has no pointer, so the highlight is all there is.
 * Focus only shows in key navigation, so touch use looks the same.
 */
private val FocusVisibleRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = FOCUS_HIGHLIGHT_ALPHA, hoveredAlpha = 0.08f, pressedAlpha = 0.1f),
)

/** Opacity of the content color over a focused element, for highlights drawn outside the ripple. */
const val FOCUS_HIGHLIGHT_ALPHA = 0.28f

/** Whether [mode] means a dark theme right now. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Default Material type scale with phrase-based line breaking, so Korean wraps
 * between words ("없습니다" stays whole) instead of between syllables.
 * The breaker keys off the text locale, so Korean is set explicitly rather
 * than relying on the device language. Honored on Android 13+.
 */
private val KoreanTypography: Typography = Typography().run {
    val lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
    val korean = LocaleList(Locale("ko-KR"))
    fun TextStyle.korean() = copy(lineBreak = lineBreak, localeList = korean)
    copy(
        displayLarge = displayLarge.korean(),
        displayMedium = displayMedium.korean(),
        displaySmall = displaySmall.korean(),
        headlineLarge = headlineLarge.korean(),
        headlineMedium = headlineMedium.korean(),
        headlineSmall = headlineSmall.korean(),
        titleLarge = titleLarge.korean(),
        titleMedium = titleMedium.korean(),
        titleSmall = titleSmall.korean(),
        bodyLarge = bodyLarge.korean(),
        bodyMedium = bodyMedium.korean(),
        bodySmall = bodySmall.korean(),
        labelLarge = labelLarge.korean(),
        labelMedium = labelMedium.korean(),
        labelSmall = labelSmall.korean(),
    )
}
