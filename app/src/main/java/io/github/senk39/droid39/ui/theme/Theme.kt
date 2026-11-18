package io.github.senk39.droid39.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Kolory zależne od motywu (tło, karty, tekst i akcenty, które na jasnym tle muszą być ciemniejsze). */
data class Palette(
    val light: Boolean,
    val bg: Color, val surface: Color, val surface2: Color, val surface3: Color, val line: Color,
    val text: Color, val text2: Color, val text3: Color,
    val miku: Color, val gold: Color, val green: Color,
)

val DarkPalette = Palette(
    false, Color(0xFF0D0F12), Color(0xFF15181D), Color(0xFF1C2027), Color(0xFF262B33), Color(0xFF2B3038),
    Color(0xFFEDEFF2), Color(0xFFA3A9B3), Color(0xFF6C737E),
    Color(0xFF39C5BB), Color(0xFFE9B949), Color(0xFF3FB950),
)

val LightPalette = Palette(
    true, Color(0xFFF4F5F7), Color(0xFFFFFFFF), Color(0xFFEBEDF1), Color(0xFFDFE3E8), Color(0xFFD3D8DF),
    Color(0xFF15181D), Color(0xFF4A515C), Color(0xFF767E8A),
    Color(0xFF1C968D), Color(0xFFA8780C), Color(0xFF2B8A3E),
)

/**
 * Kolory w duchu PowerAmp: w ciemnym motywie prawie czarne tło, karty o odcień jaśniejsze, akcent Miku (#39C5BB);
 * w jasnym – jasnoszare tło, białe karty i przyciemnione akcenty (czytelne na białym).
 */
object C39 {
    /** Aktualny motyw – odczyt w kompozycji odświeża ekran po zmianie. */
    var palette by mutableStateOf(DarkPalette)

    val Bg get() = palette.bg
    val Surface get() = palette.surface
    val Surface2 get() = palette.surface2
    val Surface3 get() = palette.surface3
    val Line get() = palette.line
    val Text get() = palette.text
    val Text2 get() = palette.text2
    val Text3 get() = palette.text3
    val Miku get() = palette.miku
    val MikuDark = Color(0xFF1E6E68)
    val Pink = Color(0xFFE12885)
    val Heart = Color(0xFFFF4F7B)
    val Warn = Color(0xFFE5484D)
    val Gold get() = palette.gold
    val Green get() = palette.green
    val Lastfm = Color(0xFFD51007)

    /** Tekst i ikony na wypełnionym kolorem akcentu przycisku: biały w jasnym motywie, ciemny w ciemnym. */
    val OnAccent get() = if (palette.light) Color.White else Color(0xFF00201D)

    /** Akcent z okładki bywa jasny – na jasnym tle przyciemniony, żeby tekst i ikony były czytelne. */
    fun readableAccent(c: Color): Color =
        if (palette.light && c.luminance() > 0.32f) lerp(c, Color.Black, 0.38f) else c

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
val LocalAccent = staticCompositionLocalOf { DarkPalette.miku }

@Composable
fun Theme39(accent: Color = C39.Miku, content: @Composable () -> Unit) {
    val scheme = if (C39.palette.light) lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        secondary = C39.Pink,
        background = C39.Bg,
        onBackground = C39.Text,
        surface = C39.Surface,
        onSurface = C39.Text,
        surfaceVariant = C39.Surface2,
        onSurfaceVariant = C39.Text2,
        surfaceContainer = C39.Surface,
        surfaceContainerHigh = C39.Surface,
        surfaceContainerHighest = C39.Surface2,
        surfaceContainerLow = C39.Surface,
        surfaceContainerLowest = C39.Bg,
        outline = C39.Line,
        outlineVariant = C39.Line,
        error = C39.Warn,
    ) else darkColorScheme(
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
