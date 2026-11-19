package app.clipt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Clipverse tokens — mirrors the Chrome extension's CSS variables exactly. */
@Immutable
data class CliptColors(
    val canvas: Color,
    val card: Color,
    val line: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val accentSoft: Color,
    val ok: Color,
    val chip: Color,
)

private val Light = CliptColors(
    canvas = Color(0xFFF7F7F9), card = Color(0xFFFFFFFF), line = Color(0xFFE8E8ED),
    ink = Color(0xFF111114), ink2 = Color(0xFF5C5C66), ink3 = Color(0xFF9A9AA3),
    accent = Color(0xFFEB0028), accentSoft = Color(0x14EB0028), ok = Color(0xFF12A150), chip = Color(0xFFEFEFF3),
)

// True-black-ish canvas: OLED pixels near-off, which also saves battery.
private val Dark = CliptColors(
    canvas = Color(0xFF0E0E10), card = Color(0xFF1A1A1D), line = Color(0xFF2A2A2E),
    ink = Color(0xFFF4F4F6), ink2 = Color(0xFFA8A8B2), ink3 = Color(0xFF6E6E78),
    accent = Color(0xFFFF2E4C), accentSoft = Color(0x1FFF2E4C), ok = Color(0xFF2FD27A), chip = Color(0xFF242428),
)

// AMOLED: true black canvas so OLED pixels switch off; cards barely lifted off it.
private val Amoled = Dark.copy(
    canvas = Color(0xFF000000), card = Color(0xFF0B0B0C), line = Color(0xFF1C1C1F), chip = Color(0xFF151517),
    ink2 = Color(0xFFA3A3AD), ink3 = Color(0xFF66666F),
)

/** Resolves the saved theme mode against the system setting. */
@Composable
fun isDarkMode(mode: String): Boolean = when (mode) {
    "light" -> false
    "dark", "amoled" -> true
    else -> isSystemInDarkTheme()
}

val LocalCliptColors = staticCompositionLocalOf { Light }

object Clipt {
    val colors: CliptColors @Composable get() = LocalCliptColors.current
}

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun CliptTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = isDarkMode(mode)
    val c = when {
        !dark -> Light
        mode == "amoled" -> Amoled
        else -> Dark
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = Color.White, background = c.canvas, onBackground = c.ink,
            surface = c.card, onSurface = c.ink, surfaceVariant = c.chip, onSurfaceVariant = c.ink2,
            outline = c.line, outlineVariant = c.line, surfaceContainerLow = c.card, surfaceContainer = c.card,
            surfaceContainerHigh = c.card, surfaceContainerHighest = c.chip, secondaryContainer = c.chip,
            onSecondaryContainer = c.ink,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = Color.White, background = c.canvas, onBackground = c.ink,
            surface = c.card, onSurface = c.ink, surfaceVariant = c.chip, onSurfaceVariant = c.ink2,
            outline = c.line, outlineVariant = c.line, surfaceContainerLow = c.card, surfaceContainer = c.card,
            surfaceContainerHigh = c.card, surfaceContainerHighest = c.chip, secondaryContainer = c.chip,
            onSecondaryContainer = c.ink,
        )
    }
    CompositionLocalProvider(LocalCliptColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
