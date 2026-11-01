package fit.bandwagon.core.ui

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand palette: an AMOLED black canvas with a bright, playful accent set. Green drives CTAs and
// active states; orange, yellow, red and blue are the supporting and category colours. Keep this
// file and AGENTS.md's palette in sync.

private val Black = Color(0xFF000000)
private val Green = Color(0xFF4ADE80)
private val Orange = Color(0xFFFFB020)
private val Yellow = Color(0xFFFACC15)
private val Red = Color(0xFFFF5252)
private val Blue = Color(0xFF38BDF8)
private val Violet = Color(0xFFA78BFA)
private val Pink = Color(0xFFF472B6)
private val Cyan = Color(0xFF22D3EE)
private val Teal = Color(0xFF2DD4BF)

private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF001A0E),
    primaryContainer = Color(0xFF0B2E1A),
    onPrimaryContainer = Color(0xFF8CF7B4),
    secondary = Orange,
    onSecondary = Color(0xFF1A0F00),
    secondaryContainer = Color(0xFF33240A),
    onSecondaryContainer = Color(0xFFFFD27A),
    tertiary = Blue,
    onTertiary = Color(0xFF00131F),
    tertiaryContainer = Color(0xFF082B3D),
    onTertiaryContainer = Color(0xFF9ADDFF),
    error = Red,
    onError = Color(0xFF1A0000),
    errorContainer = Color(0xFF3A0A0A),
    onErrorContainer = Color(0xFFFF9E9E),
    background = Black,
    onBackground = Color(0xFFFFFFFF),
    surface = Black,
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF3A3A3C),
    outlineVariant = Color(0xFF262628),
    // Cards sit one subtle step above pure black so they read on the canvas.
    surfaceContainerLowest = Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF161616),
)

/**
 * Roles Material 3 has no slot for: success, plus chart and category tokens for the Health tab
 * (AGENTS.md "Chart colours"). Screens pick these by meaning, never raw hex values.
 */
@Immutable
data class ExtendedColors(
    /** Goal hit, pact kept, milestone reached. */
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    /** Yellow highlight, e.g. the middle of the red → yellow → green progress gradient. */
    val highlight: Color,
    val sleepDeep: Color,
    val sleepRem: Color,
    val sleepLight: Color,
    val sleepAwake: Color,
    val heart: Color,
    val vitals: Color,
    val body: Color,
    val nutrition: Color,
    val mindful: Color,
    /** Personal normal-range bands behind lines. */
    val bandFill: Color,
    /** Empty track behind rings and bars. */
    val track: Color,
    /** Alias avatar colours, keyed by the colour token stored with the alias. */
    val avatar: Map<String, Color>,
)

private val DarkExtended = ExtendedColors(
    success = Green,
    onSuccess = Color(0xFF001A0E),
    successContainer = Color(0xFF0B2E1A),
    onSuccessContainer = Color(0xFF8CF7B4),
    highlight = Yellow,
    sleepDeep = Violet,
    sleepRem = Blue,
    sleepLight = Color(0xFFC4B5FD),
    sleepAwake = Orange,
    heart = Red,
    vitals = Cyan,
    body = Pink,
    nutrition = Yellow,
    mindful = Teal,
    bandFill = Color.White.copy(alpha = 0.08f),
    track = Color(0xFF1C1C1E),
    avatar = mapOf(
        "coral" to Red,
        "mango" to Orange,
        "teal" to Teal,
        "green" to Green,
        "plum" to Color(0xFFC084FC),
        "sky" to Blue,
        "yellow" to Yellow,
    ),
)

private val LocalExtendedColors = staticCompositionLocalOf { DarkExtended }

object BandwagonTheme {
    val extendedColors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}

/**
 * Always the brand palette on an AMOLED black canvas: dynamic (wallpaper) colour and light mode are
 * deliberately not used, so the app looks the same on every device and the bright accents pop.
 */
@Composable
fun BandwagonTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtendedColors provides DarkExtended) {
        MaterialTheme(colorScheme = DarkColors, content = content)
    }
}

/** Selected chips are an active state, so bright green rather than Material's default secondary tint. */
@Composable
fun brandFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)
