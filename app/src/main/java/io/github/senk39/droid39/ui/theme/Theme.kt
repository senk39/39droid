package io.github.senk39.droid39.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Kolory w duchu PowerAmp: prawie czarne tło, karty o odcień jaśniejsze, akcent Miku (#39C5BB). */
object C39 {
    val Bg = Color(0xFF0D0F12)
    val Surface = Color(0xFF15181D)
    val Surface2 = Color(0xFF1C2027)
    val Surface3 = Color(0xFF262B33)
    val Line = Color(0xFF2B3038)
    val Text = Color(0xFFEDEFF2)
    val Text2 = Color(0xFFA3A9B3)
    val Text3 = Color(0xFF6C737E)
    val Miku = Color(0xFF39C5BB)
    val MikuDark = Color(0xFF1E6E68)
    val Pink = Color(0xFFE12885)
    val Heart = Color(0xFFFF4F7B)
    val Warn = Color(0xFFE5484D)
    val Gold = Color(0xFFE9B949)
    val Green = Color(0xFF3FB950)
    val Lastfm = Color(0xFFD51007)

    /** Kolory pigułek typów tagów VocaDB (jak w 39player: tło, tekst). */
    val TagColors = mapOf(
        "Genres" to Color(0xFF2F6FB0), "Subjective" to Color(0xFF8E5BB8), "Themes" to Color(0xFF3E8E6A),
        "Animation" to Color(0xFFB0652F), "Copyrights" to Color(0xFFB03A5B), "Sources" to Color(0xFF6B7B2F),
        "Series" to Color(0xFF2F8E8E), "Vocalists" to Color(0xFF39A59C), "Instruments" to Color(0xFF8E7B2F),
        "Composition" to Color(0xFF5B6EB8), "Languages" to Color(0xFF7B5B3E), "Lyrics" to Color(0xFF9B4F8E),
        "Media" to Color(0xFF4F7B9B), "Distribution" to Color(0xFF6E6E6E), "Event" to Color(0xFFB8913E),
    )

    /** Silniki syntezy (jak engines.COLORS w 39player, w wersji na ciemne tło). */
    val EngineColors = mapOf(
        "VOCALOID" to Color(0xFF16726B), "UTAU" to Color(0xFFA3285A), "CeVIO" to Color(0xFF985200),
        "Synthesizer V" to Color(0xFF2F40A8), "VOICEROID" to Color(0xFF5E33A6), "VOICEVOX" to Color(0xFF7A5F00),
        "NEUTRINO" to Color(0xFF256B25), "VoiSona" to Color(0xFF16628F), "A.I.VOICE" to Color(0xFF8F2A85),
        "ACE Virtual Singer" to Color(0xFF2C6E5E), "New Type" to Color(0xFF6B5229),
        "V" to Color(0xFF16726B), "U" to Color(0xFFA3285A), "C" to Color(0xFF985200), "SV" to Color(0xFF2F40A8),
        "VR" to Color(0xFF5E33A6), "VV" to Color(0xFF7A5F00), "N" to Color(0xFF256B25), "VS" to Color(0xFF16628F),
        "AIV" to Color(0xFF8F2A85), "ACE" to Color(0xFF2C6E5E), "NT" to Color(0xFF6B5229),
    )
}

/** Kolor akcentu (zmienia się wg okładki odtwarzanego utworu, jeśli włączone). */
val LocalAccent = staticCompositionLocalOf { C39.Miku }

@Composable
fun Theme39(accent: Color = C39.Miku, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF00201D),
        secondary = C39.Pink,
        background = C39.Bg,
        onBackground = C39.Text,
        surface = C39.Surface,
        onSurface = C39.Text,
        surfaceVariant = C39.Surface2,
        onSurfaceVariant = C39.Text2,
        surfaceContainer = C39.Surface,
        surfaceContainerHigh = C39.Surface2,
        surfaceContainerHighest = C39.Surface3,
        surfaceContainerLow = C39.Surface,
        surfaceContainerLowest = C39.Bg,
        outline = C39.Line,
        outlineVariant = C39.Line,
        error = C39.Warn,
    )
    val type = Typography(
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 15.sp),
        bodyMedium = TextStyle(fontSize = 14.sp),
        bodySmall = TextStyle(fontSize = 12.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, typography = type) {
            // domyślny kolor tekstu i ikon poza komponentami Surface (inaczej Text byłby czarny na ciemnym tle)
            CompositionLocalProvider(LocalContentColor provides C39.Text, content = content)
        }
    }
}
