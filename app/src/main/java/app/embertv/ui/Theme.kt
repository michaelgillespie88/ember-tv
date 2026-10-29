package app.embertv.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Ember {
    val Bg = Color(0xFF09090C)
    val Panel = Color(0xFF131318)
    val Card = Color(0xFF1C1C22)
    val CardHi = Color(0xFF2A2A33)
    val Text = Color(0xFFF5F5F7)
    val Dim = Color(0xFFA8A8B3)
    val Faint = Color(0xFF6E6E78)
    val Accent = Color(0xFFFF7A3D)
    val AccentDeep = Color(0xFFE0442A)
    val Live = Color(0xFFFF453A)
    val Focus = Color(0xFFFFFFFF)
    val Scrim = Color(0xCC000000)
}

object Txt {
    val Hero = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = 46.sp)
    val Title = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
    val Headline = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val Label = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
}

@Composable
fun EmberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ember.Accent,
            background = Ember.Bg,
            surface = Ember.Panel,
            onBackground = Ember.Text,
            onSurface = Ember.Text,
        )
    ) {
        CompositionLocalProvider(LocalContentColor provides Ember.Text, content = content)
    }
}
