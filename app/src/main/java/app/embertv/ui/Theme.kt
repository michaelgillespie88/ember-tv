package app.embertv.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.embertv.R

object Ember {
    val Bg = Color(0xFF09090C)
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

object Txt {
    val LargeTitle = TextStyle(fontFamily = InterDisplay, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, lineHeight = 36.sp)
    val Hero = TextStyle(fontFamily = InterDisplay, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = 46.sp)
    val Title = TextStyle(fontFamily = InterDisplay, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
    val Headline = TextStyle(fontFamily = Inter, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
    val Body = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 22.sp)
    val Label = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val Caption = TextStyle(fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
}

@Composable
fun EmberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ember.Accent,
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
