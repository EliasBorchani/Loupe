package dev.loupe.desktop.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Loupe's palette.
 *
 * Neutrals carry a slight green-cyan bias toward the accent, so the greys read as chosen rather
 * than inherited. **Severity colours are deliberately not the accent**: in a file where seven
 * lines in ten are Debug, colouring every level is the same as colouring none, so only Warn and
 * Error get a hue and the accent owns interaction.
 */
@Immutable
class LoupeColors(
    val ground: Color,
    val surface: Color,
    val sunken: Color,
    val border: Color,
    val borderStrong: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val warn: Color,
    val warnSoft: Color,
    val error: Color,
    val errorSoft: Color,
) {
    /** Row text colour for a severity ordinal on the profile's scale. */
    fun inkForLevel(ordinal: Int, levelCount: Int): Color = when {
        levelCount == 0 -> ink
        ordinal == levelCount - 1 -> error
        ordinal == levelCount - 2 -> warn
        ordinal <= 1 -> inkSecondary
        else -> ink
    }

    /** Row background for a severity ordinal, transparent for everything that is not a problem. */
    fun surfaceForLevel(ordinal: Int, levelCount: Int): Color = when {
        levelCount == 0 -> Color.Transparent
        ordinal == levelCount - 1 -> errorSoft
        ordinal == levelCount - 2 -> warnSoft
        else -> Color.Transparent
    }

    /**
     * Timeline bar for a severity ordinal.
     *
     * The third of these, and it used to live in the timeline file taking its colours as arguments —
     * so a change to the severity scale had to be made here and remembered there. The branches are
     * the same shape on purpose: the top of the declared `order` is the error colour, the one below
     * it the warning colour, and a profile chooses its colours by choosing its scale.
     */
    fun barForLevel(ordinal: Int, levelCount: Int): Color = when {
        levelCount == 0 -> accent.copy(alpha = 0.55f)
        ordinal == levelCount - 1 -> error
        ordinal == levelCount - 2 -> warn
        ordinal <= 1 -> accent.copy(alpha = 0.28f)
        else -> accent.copy(alpha = 0.55f)
    }
}

private val LIGHT = LoupeColors(
    ground = Color(0xFFF1F4F3),
    surface = Color(0xFFFFFFFF),
    sunken = Color(0xFFE7ECEA),
    border = Color(0xFFD5DEDB),
    borderStrong = Color(0xFFB8C6C1),
    ink = Color(0xFF0F1A18),
    inkSecondary = Color(0xFF4C5F5A),
    inkTertiary = Color(0xFF7B8C87),
    accent = Color(0xFF0E7C6B),
    accentInk = Color(0xFF0B5D50),
    accentSoft = Color(0xFFDBEFEA),
    onAccent = Color(0xFFFFFFFF),
    warn = Color(0xFF9C6412),
    warnSoft = Color(0xFFF6ECD9),
    error = Color(0xFFAE3527),
    errorSoft = Color(0xFFF8E3DF),
)

private val DARK = LoupeColors(
    ground = Color(0xFF0D1413),
    surface = Color(0xFF141D1A),
    sunken = Color(0xFF101814),
    border = Color(0xFF243330),
    borderStrong = Color(0xFF344741),
    ink = Color(0xFFE2EAE7),
    inkSecondary = Color(0xFF98A8A3),
    inkTertiary = Color(0xFF6C7C77),
    accent = Color(0xFF37BAA1),
    accentInk = Color(0xFF7FD8C6),
    accentSoft = Color(0xFF113029),
    onAccent = Color(0xFF06201B),
    warn = Color(0xFFD7A254),
    warnSoft = Color(0xFF2C2415),
    error = Color(0xFFE27A6D),
    errorSoft = Color(0xFF34201D),
)

/**
 * Type roles.
 *
 * The mono face is the system's, on purpose: this is a native macOS app showing a text file, and
 * SF Mono is what every other tool the user has open renders that file in.
 */
@Immutable
class LoupeTypography(
    val ui: TextStyle,
    val uiSmall: TextStyle,
    val uiStrong: TextStyle,
    val label: TextStyle,
    val mono: TextStyle,
    val monoSmall: TextStyle,
)

private val MONO = FontFamily.Monospace
private val UI = FontFamily.SansSerif

private val TYPOGRAPHY = LoupeTypography(
    ui = TextStyle(fontFamily = UI, fontSize = 13.sp, lineHeight = 18.sp),
    uiSmall = TextStyle(fontFamily = UI, fontSize = 11.5f.sp, lineHeight = 15.sp),
    uiStrong = TextStyle(fontFamily = UI, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    label = TextStyle(
        fontFamily = UI,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.0.sp,
    ),
    mono = TextStyle(fontFamily = MONO, fontSize = 12.sp, lineHeight = 17.sp),
    monoSmall = TextStyle(fontFamily = MONO, fontSize = 11.sp, lineHeight = 15.sp),
)

/** The one spacing scale. Rows are tight on purpose — density is the point of a log viewer. */
object Spacing {
    val hairline = 1.dp
    val tiny = 3.dp
    val small = 6.dp
    val medium = 10.dp
    val large = 16.dp
    val rowHeight = 19.dp
}

val LocalLoupeColors: ProvidableCompositionLocal<LoupeColors> = staticCompositionLocalOf { LIGHT }
val LocalLoupeTypography: ProvidableCompositionLocal<LoupeTypography> = staticCompositionLocalOf { TYPOGRAPHY }

object LoupeTheme {
    val colors: LoupeColors
        @Composable @ReadOnlyComposable
        get() = LocalLoupeColors.current

    val type: LoupeTypography
        @Composable @ReadOnlyComposable
        get() = LocalLoupeTypography.current
}

@Composable
fun LoupeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLoupeColors provides if (dark) DARK else LIGHT,
        LocalLoupeTypography provides TYPOGRAPHY,
        content = content,
    )
}
