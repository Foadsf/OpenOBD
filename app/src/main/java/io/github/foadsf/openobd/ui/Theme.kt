package io.github.foadsf.openobd.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Dark by design: readable at night in a car and kind to OLED screens. */
object Palette {
    val background = Color(0xFF0E1116)
    val surface = Color(0xFF161A21)
    val surfaceHigh = Color(0xFF1E232C)
    val outline = Color(0xFF2A303B)
    val text = Color(0xFFE8ECF2)
    val muted = Color(0xFF8B95A5)
    val sky = Color(0xFF38BDF8)
    val emerald = Color(0xFF34D399)
    val amber = Color(0xFFFBBF24)
    val crimson = Color(0xFFF87171)
}

private val scheme = darkColorScheme(
    primary = Palette.sky,
    onPrimary = Color(0xFF04131C),
    primaryContainer = Color(0xFF0C3346),
    onPrimaryContainer = Color(0xFFBEE9FC),
    secondary = Palette.emerald,
    onSecondary = Color(0xFF03140D),
    secondaryContainer = Color(0xFF1E2A35),
    onSecondaryContainer = Palette.text,
    error = Palette.crimson,
    onError = Color(0xFF2A0707),
    errorContainer = Color(0xFF3B1414),
    onErrorContainer = Color(0xFFFFDADA),
    background = Palette.background,
    onBackground = Palette.text,
    surface = Palette.background,
    onSurface = Palette.text,
    surfaceVariant = Palette.surfaceHigh,
    onSurfaceVariant = Palette.muted,
    surfaceContainerLowest = Palette.background,
    surfaceContainerLow = Palette.surface,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surfaceHigh,
    surfaceContainerHighest = Palette.surfaceHigh,
    outline = Palette.outline,
    inverseSurface = Color(0xFF2A303B),
    inverseOnSurface = Palette.text,
    inversePrimary = Palette.sky,
    outlineVariant = Palette.outline,
)

/** Tabular figures keep changing numbers from jittering sideways. */
val Numbers = TextStyle(fontFeatureSettings = "tnum")

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun OpenObdTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val type = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
