package io.github.senk39.droid39.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.graphics.toArgb
import io.github.senk39.droid39.App
import io.github.senk39.droid39.ui.screens.dominantColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Widżet odtwarzacza (okładka obok tekstu). Pozostałe rodzaje różnią się tylko rozmiarem i domyślnym układem. */
open class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetUpdater.renderAll(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        WidgetUpdater.renderAll(context)

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) WidgetStyle.delete(context, id)
    }

    companion object {
        const val KIND_PLAYER = "player"
        const val KIND_COVER = "cover"
        const val KIND_BAR = "bar"

        val PROVIDERS = listOf(PlayerWidget::class.java, PlayerWidgetCover::class.java, PlayerWidgetBar::class.java)

        fun kindOf(className: String?): String = when (className) {
            PlayerWidgetCover::class.java.name -> KIND_COVER
            PlayerWidgetBar::class.java.name -> KIND_BAR
            else -> KIND_PLAYER
        }

        /** Wszystkie widżety 39droid na ekranach: (id, rodzaj). */
        fun placed(ctx: Context): List<Pair<Int, String>> {
            val m = AppWidgetManager.getInstance(ctx)
            return PROVIDERS.flatMap { cls -> m.getAppWidgetIds(ComponentName(ctx, cls)).map { it to kindOf(cls.name) } }
        }
    }
}

/** Duża okładka nad tekstem (3×3). */
class PlayerWidgetCover : PlayerWidget()

/** Wąski pasek (4×1): okładka, tytuł i przyciski w jednym rzędzie. */
class PlayerWidgetBar : PlayerWidget()

/**
 * Odświeżanie widżetów: usługa odtwarzania zgłasza zmianę utworu i stanu odtwarzania, a stan trafia też do pamięci
 * (widżet po restarcie telefonu pokazuje ostatni utwór).
 */
object WidgetUpdater {
    private var job: Job? = null
    private var lastArt: Pair<String?, Bitmap?>? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun loadNow(ctx: Context): NowState {
        val json = prefs(ctx).getString("now", null) ?: return NowState()
        return runCatching {
            val o = JSONObject(json)
            NowState(
                trackId = o.optInt("id", -1).takeIf { it >= 0 },
                title = o.optString("title"), line2 = o.optString("line2"),
                line3 = o.optString("line3").ifEmpty { null },
                artKey = o.optString("art").ifEmpty { null }, rel = o.optString("rel").ifEmpty { null },
                playing = o.optBoolean("playing"),
            )
        }.getOrDefault(NowState())
    }

    private fun saveNow(ctx: Context, s: NowState) {
        val o = JSONObject().put("id", s.trackId ?: -1).put("title", s.title).put("line2", s.line2)
            .put("line3", s.line3 ?: "").put("art", s.artKey ?: "").put("rel", s.rel ?: "").put("playing", s.playing)
        prefs(ctx).edit().putString("now", o.toString()).apply()
    }

    /** Stan widżetu dla utworu (tytuł; kompozytor i wokaliści albo wykonawca). */
    fun stateFor(trackId: Int?, playing: Boolean): NowState? {
        val lib = App.graph.library
        val row = trackId?.let { lib.track(it) } ?: return null
        val lines = Credits.of(row.rel, row.artist, row.composer, lib.vdb(row.id)?.artists ?: emptyList())
        return NowState(row.id, row.title.ifBlank { row.rel.substringAfterLast('/') }, lines.line2, lines.line3, row.artKey,
            row.rel, playing)
    }

    /** Wołane przez usługę odtwarzania (zmiana utworu, odtwarzanie / pauza); kilka zmian naraz – jedno odświeżenie. */
    fun nowChanged(ctx: Context, trackId: Int?, playing: Boolean) {
        val app = ctx.applicationContext
        job?.cancel()
        job = App.graph.scope.launch(Dispatchers.IO) {
            delay(250)
            val s = stateFor(trackId, playing) ?: loadNow(app).copy(playing = playing)
            saveNow(app, s)
            render(app, s)
        }
    }

    /** Wszystkie widżety z zapamiętanego stanu (np. dodanie widżetu, zmiana rozmiaru, zmiana wyglądu). */
    fun renderAll(ctx: Context) {
        val app = ctx.applicationContext
        App.graph.scope.launch(Dispatchers.IO) { render(app, loadNow(app)) }
    }

    /** Okładka i kolor z okładki dla stanu (zapamiętane dla ostatniego utworu). */
    suspend fun artFor(s: NowState): Pair<Bitmap?, Int?> {
        val cached = lastArt
        val bmp = if (cached != null && cached.first == s.artKey + s.rel) cached.second
        else (if (s.artKey != null || s.rel != null) runCatching { App.graph.art.load(s.artKey, s.rel, 360) }.getOrNull() else null)
            .also { lastArt = (s.artKey + s.rel) to it }
        val accent = bmp?.let { runCatching { dominantColor(it).toArgb() }.getOrNull() }
        return bmp to accent
    }

    private suspend fun render(ctx: Context, s: NowState) {
        val (bmp, accent) = artFor(s)
        val m = AppWidgetManager.getInstance(ctx)
        for ((id, kind) in PlayerWidget.placed(ctx)) {
            val style = WidgetStyle.load(ctx, id, kind)
            val h = m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val compact = kind == PlayerWidget.KIND_BAR || (h in 1..99)
            val views = withContext(Dispatchers.Default) { WidgetRenderer.build(ctx, style, s, bmp, accent, compact) }
            runCatching { m.updateAppWidget(id, views) }
        }
    }
}
