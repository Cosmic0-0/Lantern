package app.lantern.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.lantern.R

/**
 * How the reader looks (docs/decisions/ui-design.md, step 5): its own theme, font and text size, chosen in the Display
 * sheet. The rest of the app stays dark. Every text colour here is at least 4.5:1 on its ground.
 */
enum class ReaderTheme(val id: String, val label: String, val ground: Color, val ink: Color, val light: Boolean) {
    Dark("dark", "Dark", Palette.Ground, Palette.Ivory, light = false),
    Black("black", "Black", Color.Black, Color(0xFFE8E2D6), light = false),
    Sepia("sepia", "Sepia", Color(0xFFF4ECD8), Color(0xFF3B2F1E), light = true),
    White("white", "White", Color.White, Color(0xFF1C1B1A), light = true);

    /** The app's colour roles for this theme, so the bars and sheets of the reader match its page. */
    @Composable
    fun scheme(): ColorScheme {
        val app = MaterialTheme.colorScheme
        return when (this) {
            Dark -> app
            Black -> app.copy(
                background = ground, surface = ground, surfaceDim = ground,
                surfaceContainerLowest = ground, surfaceContainerLow = Color(0xFF0B0B0A), surfaceContainer = Color(0xFF141413),
                onBackground = ink, onSurface = ink, onSurfaceVariant = Color(0xFFA59E92),
            )
            Sepia -> lightReader(
                ground = ground, ink = ink, muted = Color(0xFF6B5B45),
                container = Color(0xFFEAE0C8), high = Color(0xFFE2D6BA), highest = Color(0xFFD9CCAE),
                outline = Color(0xFF9C8B70), outlineVariant = Color(0xFFD3C5A8), tint = Color(0xFFF1D0C8),
            )
            White -> lightReader(
                ground = ground, ink = ink, muted = Color(0xFF5C5852),
                container = Color(0xFFF3F1EE), high = Color(0xFFEBE8E4), highest = Color(0xFFE3DFDA),
                outline = Color(0xFF8E8A84), outlineVariant = Color(0xFFD9D5CF), tint = Color(0xFFFCE3DF),
            )
        }
    }

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Dark
    }
}

private fun lightReader(
    ground: Color, ink: Color, muted: Color, container: Color, high: Color, highest: Color,
    outline: Color, outlineVariant: Color, tint: Color,
) = lightColorScheme(
    primary = Color(0xFFB3150F), // the red as text on a light ground: 5.9:1 on sepia, 6.9:1 on white
    onPrimary = Color.White,
    primaryContainer = Palette.Red,
    onPrimaryContainer = Color.White,
    secondaryContainer = tint,
    onSecondaryContainer = Color(0xFF410E0A),
    background = ground, onBackground = ink,
    surface = ground, onSurface = ink, onSurfaceVariant = muted,
    surfaceTint = ground,
    surfaceContainerLowest = ground, surfaceContainerLow = container, surfaceContainer = container,
    surfaceContainerHigh = high, surfaceContainerHighest = highest,
    outline = outline, outlineVariant = outlineVariant,
)

private fun variable(res: Int, weight: FontWeight) =
    Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** The reader's fonts: a serif made for reading on screens, the phone's own sans, and one made to be easy to tell apart. */
enum class ReaderFont(val id: String, val label: String, val family: FontFamily) {
    Literata("literata", "Literata", FontFamily(variable(R.font.literata, FontWeight.Normal), variable(R.font.literata, FontWeight.Bold))),
    Roboto("roboto", "Roboto", FontFamily.Default),
    Atkinson(
        "atkinson", "Atkinson",
        FontFamily(variable(R.font.atkinson_hyperlegible, FontWeight.Normal), variable(R.font.atkinson_hyperlegible, FontWeight.Bold)),
    );

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Roboto
    }
}

/** The chapter text's style: [size] in sp, with a line height of 1.5 times it. */
fun readerTextStyle(font: ReaderFont, size: Float) = TextStyle(fontFamily = font.family, fontSize = size.sp, lineHeight = (size * 1.5f).sp)

const val READER_SIZE_MIN = 14f
const val READER_SIZE_MAX = 28f
const val READER_SIZE_DEFAULT = 18f
