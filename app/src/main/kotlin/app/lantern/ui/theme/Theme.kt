package app.lantern.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lantern.R

/**
 * Lantern's look (docs/decisions/ui-design.md): near-black ink, warm ivory text and one red, after the Light Novel
 * app. The app is always dark; only the reader has light themes of its own.
 *
 * The brand red #E52620 fails 4.5:1 as text on the dark ground (4.1:1), and no red passes both as text there and
 * under white text. So the brand red is the container colour (the + button, primary actions, with white on it at
 * 4.5:1), and a lighter red is [primary], for text, icons and selection (6.1:1 on the background).
 */
object Palette {
    val Red = Color(0xFFE52620) // Rush Red
    val RedText = Color(0xFFFF5A4E)
    val Ink = Color(0xFF20201E) // Ink Black: cards and sheets
    val Ground = Color(0xFF121211) // the background, darker than the cards
    val Ivory = Color(0xFFF2EBDD) // Warm Ivory: text
    val IvoryMuted = Color(0xFFB9B2A5) // secondary text, 8.9:1 on the ground
}

private val colors = darkColorScheme(
    primary = Palette.RedText,
    onPrimary = Color(0xFF2B0402),
    primaryContainer = Palette.Red,
    onPrimaryContainer = Color.White,
    inversePrimary = Color(0xFFB3150F),
    secondary = Color(0xFFE7BDB7),
    onSecondary = Color(0xFF442926),
    secondaryContainer = Color(0xFF3D1C19),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFD9C6A3),
    onTertiary = Color(0xFF3A2F17),
    tertiaryContainer = Color(0xFF52462B),
    onTertiaryContainer = Color(0xFFF6E2BD),
    background = Palette.Ground,
    onBackground = Palette.Ivory,
    surface = Palette.Ground,
    onSurface = Palette.Ivory,
    surfaceVariant = Color(0xFF2A2A27),
    onSurfaceVariant = Palette.IvoryMuted,
    surfaceTint = Palette.Ground, // elevation shows as the container steps below, never as a red cast
    surfaceDim = Palette.Ground,
    surfaceBright = Color(0xFF393935),
    surfaceContainerLowest = Color(0xFF0C0C0B),
    surfaceContainerLow = Color(0xFF191917),
    surfaceContainer = Palette.Ink,
    surfaceContainerHigh = Color(0xFF2A2A27),
    surfaceContainerHighest = Color(0xFF353531),
    inverseSurface = Palette.Ivory,
    inverseOnSurface = Palette.Ink,
    outline = Color(0xFF6E695F),
    outlineVariant = Color(0xFF3A3833),
    scrim = Color.Black,
)

/** Schibsted Grotesk, a variable font: headings and titles. Body and labels stay in the system face (Roboto). */
private fun grotesk(weight: FontWeight) =
    Font(R.font.schibsted_grotesk, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

val Grotesk = FontFamily(
    grotesk(FontWeight.Normal),
    grotesk(FontWeight.Medium),
    grotesk(FontWeight.SemiBold),
    grotesk(FontWeight.Bold),
    grotesk(FontWeight.ExtraBold),
)

private val typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
        displayMedium = base.displayMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp),
        displaySmall = base.displaySmall.copy(fontFamily = Grotesk, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold),
        // Section names ("Continue", "Library"): small, wide-spaced capitals in the label role.
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** A filled button in the brand red with white text. Material's default fill is [ColorScheme.primary], the text red. */
@Composable
fun redButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

@Composable
fun LanternTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
