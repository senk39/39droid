package io.github.senk39.droid39.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.senk39.droid39.App
import io.github.senk39.droid39.isLightTheme
import io.github.senk39.droid39.ui.screens.Button39
import io.github.senk39.droid39.ui.screens.Choice
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.DarkPalette
import io.github.senk39.droid39.ui.theme.LightPalette
import io.github.senk39.droid39.ui.theme.LocalAccent
import io.github.senk39.droid39.ui.theme.Theme39
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wygląd widżetu: układ, tło (kolor, krycie, zaokrąglenie), rozmiar i styl każdego tekstu, styl przycisków – z podglądem
 * prawdziwego widżetu. Otwierane przy dodawaniu widżetu, z menu widżetu („Ustawienia widżetu”) i z ustawień 39droid.
 */
class WidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // bez zapisu (wstecz) launcher nie doda widżetu
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        val kind = PlayerWidget.kindOf(info?.provider?.className)
        val light = isLightTheme(App.graph.settings.theme,
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES)
        C39.palette = if (light) LightPalette else DarkPalette
        enableEdgeToEdge()
        val initial = WidgetStyle.load(this, widgetId, kind)
        setContent {
            Theme39 {
                Box(Modifier.fillMaxSize().background(C39.Bg).safeDrawingPadding()) {
                    ConfigScreen(kind, initial, onSave = { save(it) }, onCancel = { finish() })
                }
            }
        }
    }

    private fun save(style: WidgetStyle) {
        WidgetStyle.save(this, widgetId, style)
        WidgetUpdater.renderAll(this)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigScreen(kind: String, initial: WidgetStyle, onSave: (WidgetStyle) -> Unit, onCancel: () -> Unit) {
    var style by remember { mutableStateOf(initial) }
    val ctx = LocalContext.current
    // podgląd: bieżący (albo ostatni) utwór; bez niego – przykładowy utwór Vocaloid
    var now by remember { mutableStateOf<NowState?>(null) }
    var art by remember { mutableStateOf<Pair<Bitmap?, Int?>>(null to null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val saved = WidgetUpdater.loadNow(ctx)
            val s = if (saved.trackId != null) saved
            else NowState(title = "ゴーストルール", line2 = "DECO*27", line3 = "feat. 初音ミク", playing = true)
            art = WidgetUpdater.artFor(s)
            now = s
        }
    }
    val compact = kind == PlayerWidget.KIND_BAR
    Column(Modifier.fillMaxSize()) {
        Text("Wygląd widżetu", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp), fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold, color = C39.Text)
        // podgląd na tle przypominającym tapetę (widać przezroczystość)
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF5B8DEF), Color(0xFFE86FA3), Color(0xFFF2C14E))))
                .padding(16.dp),
        ) {
            val n = now
            if (n != null) {
                AndroidView(
                    factory = { FrameLayout(it) },
                    update = { frame ->
                        frame.removeAllViews()
                        frame.addView(WidgetRenderer.build(frame.context, style, n, art.first, art.second, compact).apply(frame.context, frame))
                    },
                    modifier = Modifier.fillMaxWidth().height(
                        when {
                            compact -> 72.dp
                            style.layout == "top" -> 300.dp
                            else -> 150.dp
                        }
                    ),
                )
            } else Spacer(Modifier.height(150.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Section("Układ")
            Chips(WidgetStyle.LAYOUTS.filterKeys { !compact || it != "top" }, style.layout) { style = style.copy(layout = it) }
            if (compact) Note("Pasek ma okładkę z boku – jest za niski na okładkę nad tekstem.")

            Section("Tło")
            Chips(WidgetStyle.THEMES, style.theme) { style = style.copy(theme = it) }
            Labeled("Krycie tła", "${style.opacity}%") {
                Slider39(style.opacity.toFloat(), 0f..100f, 19) { style = style.copy(opacity = it.toInt()) }
            }
            Labeled("Zaokrąglenie rogów", "${style.radiusDp} dp") {
                Slider39(style.radiusDp.toFloat(), 0f..WidgetStyle.RADIUS_MAX.toFloat(), 0) { style = style.copy(radiusDp = it.toInt()) }
            }

            TextBlock("Tytuł utworu", style.title) { style = style.copy(title = it) }
            TextBlock("Kompozytor (Vocaloid) / wykonawca", style.line2) { style = style.copy(line2 = it) }
            if (!compact) TextBlock("Wokaliści (Vocaloid)", style.line3) { style = style.copy(line3 = it) }

            Section("Przyciski")
            Chips(WidgetStyle.BUTTONS, style.buttons) { style = style.copy(buttons = it) }
            Note("Kolor przycisków bierze się z okładki bieżącego utworu.")
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button39("Zapisz") { onSave(style) }
            Button39("Przywróć domyślne", outlined = true) { style = WidgetStyle.defaultFor(kind) }
            Spacer(Modifier.weight(1f))
            Button39("Anuluj", outlined = true) { onCancel() }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp), fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold, color = LocalAccent.current)
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 12.sp, color = C39.Text3)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: Map<String, String>, selected: String, onPick: (String) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, label) in options) Choice(label, k == selected) { onPick(k) }
    }
}

@Composable
private fun Labeled(label: String, value: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row {
            Text(label, fontSize = 14.sp, color = C39.Text, modifier = Modifier.weight(1f))
            Text(value, fontSize = 14.sp, color = C39.Text2)
        }
        content()
    }
}

@Composable
private fun Slider39(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    val accent = LocalAccent.current
    Slider(value, onChange, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = C39.Surface3))
}

@Composable
private fun TextBlock(title: String, spec: TextSpec, onChange: (TextSpec) -> Unit) {
    Section(title)
    Labeled("Wielkość", "${spec.sizeSp} sp") {
        Slider39(spec.sizeSp.toFloat(), TextSpec.SIZE_MIN.toFloat()..TextSpec.SIZE_MAX.toFloat(),
            TextSpec.SIZE_MAX - TextSpec.SIZE_MIN - 1) { onChange(spec.copy(sizeSp = it.toInt())) }
    }
    Chips(TextSpec.STYLES, spec.style) { onChange(spec.copy(style = it)) }
    Spacer(Modifier.width(1.dp).height(4.dp))
}
