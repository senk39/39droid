package io.github.senk39.droid39.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.media3.session.MediaButtonReceiver
import io.github.senk39.droid39.MainActivity
import io.github.senk39.droid39.R

/** Co pokazuje widżet: bieżący (albo ostatni) utwór i stan odtwarzania. */
data class NowState(
    val trackId: Int? = null,
    val title: String = "39droid",
    val line2: String = "Nic nie gra – dotknij, aby otworzyć",
    val line3: String? = null,
    val artKey: String? = null,
    val rel: String? = null,
    val playing: Boolean = false,
)

/** Widżet jako RemoteViews – ten sam kod dla ekranu głównego i podglądu w ustawieniach widżetu. */
object WidgetRenderer {
    private const val MIKU = 0xFF39C5BB.toInt()

    private data class Colors(val bg: Int, val text: Int, val text2: Int)

    private fun colors(ctx: Context, theme: String, accent: Int?): Colors {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val light = theme == "light" || (theme == "system" && !night)
        return when {
            theme == "art" -> Colors(blend(accent ?: MIKU, Color.BLACK, 0.62f), Color.WHITE, 0xFFD9DCE1.toInt())
            light -> Colors(Color.WHITE, 0xFF15181D.toInt(), 0xFF4A515C.toInt())
            else -> Colors(0xFF121418.toInt(), Color.WHITE, 0xFFB4B9C1.toInt())
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    private fun withAlpha(c: Int, a: Float) = Color.argb((255 * a).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    private enum class Bg { NONE, FILL, OUTLINE }

    /** Przycisk jako obrazek [sizeDp]: tło (brak / wypełnione kółko / obrys) i ikona w kolorze. */
    private fun buttonBitmap(ctx: Context, iconRes: Int, sizeDp: Int, bg: Bg, bgColor: Int, iconColor: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val px = (sizeDp * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        val r = px / 2f
        when (bg) {
            Bg.FILL -> canvas.drawCircle(r, r, r, paint)
            Bg.OUTLINE -> {
                val w = 1.5f * d
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = w
                canvas.drawCircle(r, r, r - w, paint)
            }
            Bg.NONE -> Unit
        }
        val icon = ctx.getDrawable(iconRes)?.mutate() ?: return bmp
        val inset = (px * if (bg == Bg.NONE) 0.18f else 0.26f).toInt()
        icon.setBounds(inset, inset, px - inset, px - inset)
        icon.setTint(iconColor)
        icon.draw(canvas)
        return bmp
    }

    /** Tekst w wybranym stylu (pogrubienie, kursywa, krój cienki/wąski, wielkie litery). */
    fun styled(text: String, style: String): CharSequence {
        val t = if (style == "caps") text.uppercase() else text
        val s = SpannableString(t)
        val span: Any? = when (style) {
            "bold" -> StyleSpan(Typeface.BOLD)
            "italic" -> StyleSpan(Typeface.ITALIC)
            "bold_italic" -> StyleSpan(Typeface.BOLD_ITALIC)
            "light" -> TypefaceSpan("sans-serif-light")
            "condensed" -> TypefaceSpan("sans-serif-condensed")
            "caps" -> StyleSpan(Typeface.BOLD)
            else -> null
        }
        if (span != null) s.setSpan(span, 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    /** Odtwórz / pauza jak w słuchawkach: działa także, gdy aplikacja nie jest uruchomiona (wznawia ostatnią kolejkę). */
    private fun mediaButton(ctx: Context, keyCode: Int): PendingIntent {
        val i = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(ctx, MediaButtonReceiver::class.java)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        return PendingIntent.getBroadcast(ctx, keyCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** „Poprzedni” / „następny” – przez kontroler odtwarzacza aplikacji (WidgetActionReceiver). */
    private fun appAction(ctx: Context, action: String): PendingIntent {
        val i = Intent(action).setClass(ctx, WidgetActionReceiver::class.java)
        return PendingIntent.getBroadcast(ctx, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 39, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * [compact] – niski widżet (pasek): przyciski obok tekstu, bez trzeciej linii. [accent] – kolor z okładki
     * (null – turkus 39droid).
     */
    fun build(ctx: Context, style: WidgetStyle, now: NowState, art: Bitmap?, accent: Int?, compact: Boolean): RemoteViews {
        val layout = when {
            compact && style.layout == "right" -> R.layout.widget_bar_right
            compact -> R.layout.widget_bar_left
            style.layout == "right" -> R.layout.widget_right
            style.layout == "top" -> R.layout.widget_top
            else -> R.layout.widget_left
        }
        val v = RemoteViews(ctx.packageName, layout)
        val c = colors(ctx, style.theme, accent)
        val acc = accent ?: MIKU

        // tło: kolor, krycie, zaokrąglenie
        v.setInt(R.id.w_bg, "setColorFilter", c.bg)
        v.setInt(R.id.w_bg, "setImageAlpha", (style.opacity.coerceIn(0, 100) * 255 / 100))
        v.setViewOutlinePreferredRadius(R.id.w_bg, style.radiusDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)

        // okładka (zaokrąglenie dopasowane do tła)
        if (art != null) v.setImageViewBitmap(R.id.w_art, art) else v.setImageViewResource(R.id.w_art, R.drawable.widget_art_placeholder)
        v.setViewOutlinePreferredRadius(R.id.w_art, (style.radiusDp * 0.6f).coerceAtMost(18f), TypedValue.COMPLEX_UNIT_DIP)

        // teksty
        fun text(id: Int, value: String?, spec: TextSpec, color: Int) {
            if (value.isNullOrBlank()) {
                v.setViewVisibility(id, View.GONE)
                return
            }
            v.setViewVisibility(id, View.VISIBLE)
            v.setTextViewText(id, styled(value, spec.style))
            v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, spec.sizeSp.toFloat())
            v.setTextColor(id, color)
        }
        text(R.id.w_title, now.title, style.title, c.text)
        text(R.id.w_line2, now.line2, style.line2, c.text2)
        val vocals = if (style.featPrefix) now.line3 else now.line3?.removePrefix("feat. ")
        text(R.id.w_line3, if (compact) null else vocals, style.line3, c.text2)

        // przyciski – rysowane jako obrazki (kółko + ikona): barwienie tła przez RemoteViews różni się między
        // launcherami (np. obrys wypełniał się kolorem)
        val onAccent = if (Color.luminance(acc) > 0.55f) 0xFF102020.toInt() else Color.WHITE
        val soft = withAlpha(c.text, 0.16f)
        val small = if (compact) 36 else 40
        val big = if (compact) 42 else 48
        for ((id, isPlay) in listOf(R.id.w_prev to false, R.id.w_play to true, R.id.w_next to false)) {
            val icon = when {
                id == R.id.w_prev -> R.drawable.ic_w_prev
                id == R.id.w_next -> R.drawable.ic_w_next
                now.playing -> R.drawable.ic_w_pause
                else -> R.drawable.ic_w_play
            }
            val (bg, bgColor, iconColor) = when (style.buttons) {
                "plain" -> Triple(Bg.NONE, 0, c.text)
                "accent" -> Triple(Bg.NONE, 0, acc)
                "outline" -> if (isPlay) Triple(Bg.OUTLINE, acc, acc) else Triple(Bg.OUTLINE, c.text2, c.text)
                "filled_all" -> if (isPlay) Triple(Bg.FILL, acc, onAccent) else Triple(Bg.FILL, soft, c.text)
                else -> if (isPlay) Triple(Bg.FILL, acc, onAccent) else Triple(Bg.NONE, 0, c.text)
            }
            v.setViewPadding(id, 0, 0, 0, 0)
            v.setImageViewBitmap(id, buttonBitmap(ctx, icon, if (isPlay) big else small, bg, bgColor, iconColor))
        }
        v.setOnClickPendingIntent(R.id.w_prev, appAction(ctx, WidgetActionReceiver.ACTION_PREVIOUS))
        v.setOnClickPendingIntent(R.id.w_play, mediaButton(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        v.setOnClickPendingIntent(R.id.w_next, appAction(ctx, WidgetActionReceiver.ACTION_NEXT))
        v.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
        return v
    }
}
