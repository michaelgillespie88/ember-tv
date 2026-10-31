package app.embertv.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.embertv.R

/**
 * A background for the whole app: the dark base colour, and the soft glow at the top of each page
 * that shows through the tinted glass. [glowSoft] and [glowStrong] are for the full-screen pages
 * (editors, sign-in) that glow from the middle.
 */
class AppBackground(val id: String, val name: String, val bg: Color, val glow: Color, val glowSoft: Color, val glowStrong: Color, val swatch: Color)

val BACKDROPS = listOf(
    AppBackground("warm", "Ember", Color(0xFF09090C), Color(0xFF1C130E), Color(0xFF1E1410), Color(0xFF2A1408), Color(0xFF6A3A1E)),
    AppBackground("graphite", "Graphite", Color(0xFF0A0A0C), Color(0xFF18181C), Color(0xFF1A1A1E), Color(0xFF24242A), Color(0xFF4A4A52)),
    AppBackground("midnight", "Midnight", Color(0xFF06080E), Color(0xFF0E1628), Color(0xFF10192D), Color(0xFF14223F), Color(0xFF24407A)),
    AppBackground("ocean", "Ocean", Color(0xFF050B0C), Color(0xFF0B1E22), Color(0xFF0D2226), Color(0xFF0F2E33), Color(0xFF1C5A64)),
    AppBackground("forest", "Forest", Color(0xFF060A08), Color(0xFF0F1D14), Color(0xFF112117), Color(0xFF162D1F), Color(0xFF285A38)),
    AppBackground("plum", "Plum", Color(0xFF09070C), Color(0xFF1B1022), Color(0xFF1F1227), Color(0xFF2B1736), Color(0xFF57305F)),
    AppBackground("rose", "Rose", Color(0xFF0B0709), Color(0xFF221016), Color(0xFF26121A), Color(0xFF341722), Color(0xFF6E3044)),
    AppBackground("black", "Pure black", Color(0xFF000000), Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFF111111), Color(0xFF1A1A1A)),
)

object Ember {
    /** The chosen background (Settings → Background). Reading it from a screen redraws it when it changes. */
    var backdrop by androidx.compose.runtime.mutableStateOf(BACKDROPS[0])

    val Bg: Color get() = backdrop.bg
    val Glow: Color get() = backdrop.glow
    val GlowSoft: Color get() = backdrop.glowSoft
    val GlowStrong: Color get() = backdrop.glowStrong
    // Tinted glass, Apple TV style: light lets the warm background glow through.
    val Panel = Color(0x12FFFFFF)
    val Card = Color(0x17FFFFFF)
    val CardHi = Color(0x29FFFFFF)
    val Text = Color(0xFFF5F5F7)
    val Dim = Color(0xFFA8A8B3)
    val Faint = Color(0xFF6E6E78)
    val Accent = Color(0xFFFF7A3D)
    val AccentDeep = Color(0xFFE0442A)
    val Live = Color(0xFFFF453A)
    val Focus = Color(0xFFFFFFFF)
    val Scrim = Color(0xCC000000)
}

/** Inter (SIL Open Font License) is a close match for Apple's system font. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Inter Display is tuned for big headings, like Apple's display font. */
val InterDisplay = FontFamily(
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

/** Text sizes to choose from in Settings, with how much bigger each makes the text. */
val TEXT_SIZES = listOf("Normal" to 1f, "Large" to 1.12f, "Extra large" to 1.25f)

/** The app's text styles, scaled by the chosen text size (Settings → Text size). */
object Txt {
    /** 1 is normal. Reading a style from a screen redraws it when this changes. */
    var scale by androidx.compose.runtime.mutableStateOf(1f)

    private class Styles(s: Float) {
        val largeTitle = TextStyle(fontFamily = InterDisplay, fontSize = (30f * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, lineHeight = (36f * s).sp)
        val hero = TextStyle(fontFamily = InterDisplay, fontSize = (40f * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = (46f * s).sp)
        val title = TextStyle(fontFamily = InterDisplay, fontSize = (26f * s).sp, fontWeight = FontWeight.SemiBold, lineHeight = (32f * s).sp)
        val headline = TextStyle(fontFamily = Inter, fontSize = (18f * s).sp, fontWeight = FontWeight.SemiBold, lineHeight = (23f * s).sp)
        val body = TextStyle(fontFamily = Inter, fontSize = (15f * s).sp, lineHeight = (22f * s).sp)
        val label = TextStyle(fontFamily = Inter, fontSize = (15f * s).sp, fontWeight = FontWeight.Medium)
        val caption = TextStyle(fontFamily = Inter, fontSize = (12f * s).sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
    }

    private var made = Styles(1f)
    private var madeFor = 1f

    private fun styles(): Styles {
        val s = scale
        if (s != madeFor) {
            made = Styles(s)
            madeFor = s
        }
        return made
    }

    val LargeTitle: TextStyle get() = styles().largeTitle
    val Hero: TextStyle get() = styles().hero
    val Title: TextStyle get() = styles().title
    val Headline: TextStyle get() = styles().headline
    val Body: TextStyle get() = styles().body
    val Label: TextStyle get() = styles().label
    val Caption: TextStyle get() = styles().caption
}

@Composable
fun EmberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            // Apple style: white for anything selected or switched on.
            primary = Ember.Text,
            onPrimary = Color.Black,
            background = Ember.Bg,
            surface = Color(0xFF131318),
            onBackground = Ember.Text,
            onSurface = Ember.Text,
        )
    ) {
        CompositionLocalProvider(
            LocalContentColor provides Ember.Text,
            LocalTextStyle provides TextStyle(fontFamily = Inter),
            content = content,
        )
    }
}
