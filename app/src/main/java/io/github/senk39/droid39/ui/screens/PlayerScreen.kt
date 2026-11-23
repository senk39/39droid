@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package io.github.senk39.droid39.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import io.github.senk39.droid39.App
import io.github.senk39.droid39.core.Text as T
import io.github.senk39.droid39.data.TrackRow
import io.github.senk39.droid39.ui.Icons39
import io.github.senk39.droid39.ui.Nav
import io.github.senk39.droid39.ui.Route
import io.github.senk39.droid39.ui.components.AddToPlaylistDialog
import io.github.senk39.droid39.ui.components.SheetItem
import io.github.senk39.droid39.ui.components.Art
import io.github.senk39.droid39.ui.components.HeartIcon
import io.github.senk39.droid39.ui.components.ListCtx
import io.github.senk39.droid39.ui.components.Pill
import io.github.senk39.droid39.ui.components.SmallIconButton
import io.github.senk39.droid39.ui.components.TrackActionsSheet
import io.github.senk39.droid39.ui.components.TrackInfoSheet
import io.github.senk39.droid39.ui.components.rememberLoad
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun rememberCurrentTrack(): TrackRow? {
    val g = App.graph
    val now by g.player.now.collectAsState()
    val snapV by g.library.snapVersion.collectAsState()
    return rememberLoad(now.id, snapV) { now.id?.let { g.library.track(it) } }
}

/** Kolor dominujący okładki (średnia ważona nasyceniem) – akcent interfejsu jak w PowerAmp. */
fun dominantColor(bmp: Bitmap): Color {
    val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
    var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
    val hsv = FloatArray(3)
    for (x in 0 until 24) for (y in 0 until 24) {
        val p = small.getPixel(x, y)
        android.graphics.Color.colorToHSV(p, hsv)
        val weight = (hsv[1] * hsv[2]).toDouble() + 0.02
        r += android.graphics.Color.red(p) * weight; g += android.graphics.Color.green(p) * weight; b += android.graphics.Color.blue(p) * weight; w += weight
    }
    val c = android.graphics.Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
    android.graphics.Color.colorToHSV(c, hsv)
    if (hsv[1] < 0.25f) return C39.Miku                         // szara okładka – zostaje kolor Miku
    hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
    hsv[2] = hsv[2].coerceIn(0.75f, 0.95f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

@Composable
fun MiniPlayer() {
    val g = App.graph
    val ui by g.player.ui.collectAsState()
    val row = rememberCurrentTrack()
    if (ui.currentId == null || row == null) return
    val accent = LocalAccent.current
    val overlay by g.library.overlayVersion.collectAsState()
    val heart = remember(overlay, row) { g.library.heart(row) }
    Column(
        Modifier.fillMaxWidth().background(C39.Surface).navigationBarsPadding()
            .pointerInput(Unit) { detectVerticalDragGestures { _, drag -> if (drag < -12) Nav.playerOpen = true } },
    ) {
        val durMs = if (ui.durationMs > 0) ui.durationMs else (row.duration * 1000).toLong()
        val frac = if (durMs > 0) (ui.positionMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { frac }, Modifier.fillMaxWidth().height(2.dp), color = accent, trackColor = C39.Surface3)
        Row(
            Modifier.fillMaxWidth().clickable { Nav.playerOpen = true }.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(row.artKey, row.rel, 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(row.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = C39.Text2, fontSize = 12.sp)
            }
            HeartIcon(heart, 20.dp, onClick = { g.scope.launch(Dispatchers.IO) { g.library.cycleHeart(row, false); g.player.heartChanged() } })
            SmallIconButton(if (ui.isPlaying) Icons39.Pause else Icons39.Play, "Odtwórz/Pauza", tint = C39.Text, size = 30.dp) { g.player.toggle() }
            SmallIconButton(Icons39.Next, "Następny", tint = C39.Text, size = 26.dp) { g.player.next() }
        }
    }
}

@Composable
fun PlayerScreen() {
    val g = App.graph
    val ui by g.player.ui.collectAsState()
    val source by g.player.source.collectAsState()
    val row = rememberCurrentTrack()
    val accent = LocalAccent.current
    val overlay by g.library.overlayVersion.collectAsState()
    val vdb = rememberLoad("vdb", row?.id) { row?.let { g.library.vdb(it.id) } }
    val wiki = rememberLoad("wiki", row?.id) { row?.let { g.library.wikiLinks(it.id) } } ?: emptyList()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var addPl by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableFloatStateOf(0f) }
    var tagMenu by remember { mutableStateOf<io.github.senk39.droid39.data.TagRow?>(null) }
    val swipe = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), C39.Bg, C39.Bg)))
            .background(Color(0x22000000))
            // w dół – zwinięcie odtwarzacza; w górę – menu utworu (jak przycisk z trzema kropkami)
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > swipe) Nav.playerOpen = false else if (total < -swipe) menu = true },
                ) { _, drag -> total += drag }
            },
    ) {
        // Układ zależy od okna: wysokie – okładka nad tytułem; niskie – okładka w prawo, tytuł obok po lewej;
        // szerokie (telefon poziomo) – po lewej tytuł, pasek i przyciski, po prawej duża okładka. Przyciski
        // sterowania są zawsze widoczne: w wąskim oknie bez „Losowo” i mniejsze, w bardzo wąskim – bez okładki.
        val compact = maxHeight < 340.dp
        val header: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallIconButton(Icons39.ExpandMore, "Zwiń", tint = C39.Text, size = 28.dp) { Nav.playerOpen = false }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ODTWARZANIE", fontSize = 10.sp, color = C39.Text3, letterSpacing = 1.5.sp, maxLines = 1)
                    Text(source.ifEmpty { "Kolejka" }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SmallIconButton(Icons39.Queue, "Kolejka", tint = C39.Text) { Nav.playerOpen = false; Nav.go(Route.Queue) }
                SmallIconButton(Icons39.More, "Więcej", tint = C39.Text) { menu = true }
            }
        }
        val artBox: @Composable (Modifier) -> Unit = { mod ->
            if (row != null) {
                Box(
                    mod.shadow(18.dp, RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = { if (total < -80) g.player.next() else if (total > 80) g.player.previous() },
                            ) { _, d -> total += d }
                        },
                ) {
                    Art(row.artKey, row.rel, 360.dp, Modifier.fillMaxSize(), corner = 16.dp, large = true)
                }
            } else Box(mod.clip(RoundedCornerShape(16.dp)).background(C39.Surface2))
        }
        val infoRow: @Composable (Modifier) -> Unit = { mod ->
            Row(mod, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row?.title ?: "—", Modifier.basicMarquee(), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(row?.artist ?: "", color = C39.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.basicMarquee())
                    val album = row?.album.orEmpty()
                    if (album.isNotEmpty()) Text(album, color = C39.Text3, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (row != null) {
                    val heart = remember(overlay, row) { g.library.heart(row) }
                    HeartIcon(heart, 30.dp, onClick = { g.scope.launch(Dispatchers.IO) { g.library.cycleHeart(row, false); g.player.heartChanged() } })
                }
            }
        }
        val pills: @Composable () -> Unit = {
            if (!compact && (vdb != null || wiki.isNotEmpty())) {
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    wiki.forEach { w ->                         // odnośniki do wiki piosenek (Love Live, BanG Dream…)
                        val p = w.page ?: w.albumPage
                        if (p != null) Pill(w.source.name, Color(android.graphics.Color.parseColor(w.source.color)), onClick = { uri.openUri(w.source.url(p)) })
                    }
                    if (vdb != null) vdb.songId?.let { Pill("VocaDB ${T.vdbId(it)}", if (vdb.issue.isNotEmpty()) C39.Warn else C39.Miku, onClick = { info = true }) }
                    if (vdb != null && vdb.songType.isNotEmpty() && vdb.songType != "Original song") Pill(vdb.songType, C39.Gold)
                    vdb?.tags.orEmpty().take(4).forEach { t ->
                        Pill(t.name, C39.TagColors[t.category] ?: C39.Text2,
                            onClick = { Nav.playerOpen = false; Nav.go(Route.Tag(t.id)) }, onLongClick = { tagMenu = t })
                    }
                }
            }
        }
        // przed przygotowaniem odtwarzacza (np. kolejka przywrócona po uruchomieniu) długość jest nieznana –
        // wtedy z biblioteki
        val dur = (if (ui.durationMs > 0) ui.durationMs else ((row?.duration ?: 0.0) * 1000).toLong()).coerceAtLeast(1)
        val pos = if (seeking) seekPos else ui.positionMs.toFloat()
        val seek: @Composable () -> Unit = {
            SeekBar(pos.coerceIn(0f, dur.toFloat()), dur.toFloat(), onChange = { seeking = true; seekPos = it },
                onDone = { g.player.seekTo(seekPos.toLong()); seeking = false })
            Row {
                Text(T.durationMs(pos.toLong()), color = C39.Text2, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (ui.buffering) Text("buforowanie…", color = C39.Text3, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(T.durationMs(dur), color = C39.Text2, fontSize = 12.sp)
            }
        }
        // przyciski dopasowane do szerokości: od 330 dp – wszystkie; węższe – bez „Losowo”, mniejsze
        val controls: @Composable (Dp, Dp) -> Unit = { width, playMax ->
            val full = width >= 330.dp
            val tiny = width < 250.dp
            val play = minOf(playMax, if (full) 74.dp else if (tiny) 52.dp else 62.dp)
            val skip = if (full) 38.dp else if (tiny) 30.dp else 34.dp
            val side = if (full) 26.dp else if (tiny) 22.dp else 24.dp
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                if (full) SmallIconButton(Icons39.Shuffle, "Losowo", tint = if (ui.shuffle) accent else C39.Text2, size = side) { g.player.setShuffle(!ui.shuffle) }
                SmallIconButton(Icons39.Prev, "Poprzedni", tint = C39.Text, size = skip) { g.player.previous() }
                Box(
                    Modifier.size(play).clip(CircleShape).background(accent).clickable { g.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (ui.isPlaying) Icons39.Pause else Icons39.Play, "Odtwórz/Pauza", Modifier.size(play * 0.54f), tint = C39.OnAccent)
                }
                SmallIconButton(Icons39.Next, "Następny", tint = C39.Text, size = skip) { g.player.next() }
                SmallIconButton(
                    if (ui.repeat == Player.REPEAT_MODE_ONE) Icons39.RepeatOne else Icons39.Repeat, "Powtarzanie",
                    tint = if (ui.repeat != Player.REPEAT_MODE_OFF) accent else C39.Text2, size = side,
                ) { g.player.cycleRepeat() }
            }
            ui.error?.let {
                Text(it, color = C39.Warn, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        val bottomRow: @Composable () -> Unit = {
            if (!compact) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                SmallIconButton(Icons39.PlaylistAdd, "Dodaj do playlisty", tint = C39.Text2) { addPl = true }
                SmallIconButton(Icons39.Info, "Informacje o utworze", tint = C39.Text2) { info = true }
                val local = row?.let { g.files.isLocal(it.id) } ?: false
                Icon(if (local) Icons39.DownloadDone else Icons39.Cloud, if (local) "Na telefonie" else "Strumień z NAS",
                    Modifier.padding(9.dp).size(22.dp), tint = if (local) accent else C39.Text3)
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        ) {
            val wide = maxWidth >= 560.dp && maxWidth > maxHeight * 1.25f
            if (wide) {
                // poziomo: po prawej okładka na całą wysokość, po lewej reszta
                val artSide = minOf(maxHeight - 16.dp, maxWidth * 0.5f)
                val leftW = maxWidth - artSide - 28.dp
                val playMax = if (maxHeight < 380.dp) 62.dp else 74.dp
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(leftW).fillMaxSize()) {
                        header()
                        Spacer(Modifier.weight(1f))
                        infoRow(Modifier.fillMaxWidth())
                        pills()
                        Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
                        seek()
                        controls(leftW, playMax)
                        Spacer(Modifier.weight(1f))
                        bottomRow()
                    }
                    Spacer(Modifier.width(28.dp))
                    artBox(Modifier.size(artSide))
                }
            } else {
                val contentW = maxWidth
                Column(Modifier.fillMaxSize()) {
                    header()
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val infoH = 84.dp
                        val tallArt = minOf(maxWidth, maxHeight - infoH - 12.dp)
                        if (tallArt >= maxWidth * 0.6f) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(Modifier.weight(0.6f))
                                artBox(Modifier.size(tallArt))
                                Spacer(Modifier.weight(0.5f))
                                infoRow(Modifier.fillMaxWidth())
                            }
                        } else {
                            // bardzo wąskie okno – bez okładki, cała szerokość dla tytułu
                            val side = if (maxWidth < 260.dp) 0.dp else minOf(maxHeight - 8.dp, maxWidth * 0.5f).coerceAtLeast(0.dp)
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                infoRow(Modifier.weight(1f))
                                if (side >= 40.dp) {
                                    Spacer(Modifier.width(12.dp))
                                    artBox(Modifier.size(side))
                                }
                            }
                        }
                    }
                    pills()
                    Spacer(Modifier.height(if (compact) 4.dp else 14.dp))
                    seek()
                    Spacer(Modifier.height(8.dp))
                    controls(contentW, 74.dp)
                    Spacer(Modifier.height(4.dp))
                    bottomRow()
                }
            }
        }
    }
    if (menu && row != null) TrackActionsSheet(listOf(row), ListCtx("Odtwarzacz")) { menu = false }
    tagMenu?.let { t -> TagActionsSheet(t) { tagMenu = null } }
    if (info && row != null) TrackInfoSheet(row) { info = false }
    if (addPl && row != null) AddToPlaylistDialog(listOf(row)) { addPl = false }
}

/**
 * Pasek postępu z wybieranym wskaźnikiem pozycji (Ustawienia → Wygląd i interfejs): pionowa kreska, kółko albo brak –
 * wtedy postęp pokazuje sam kolor paska; kropka na końcu paska – opcjonalna.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(value: Float, max: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val g = App.graph
    val changes by g.settings.changes.collectAsState()
    val thumbStyle = remember(changes) { g.settings.seekThumb }
    val dot = remember(changes) { g.settings.seekStopDot }
    val accent = LocalAccent.current
    val colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = C39.Surface3)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Slider(
        value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = 0f..max,
        colors = colors, interactionSource = interaction,
        thumb = {
            when (thumbStyle) {
                "circle" -> Box(Modifier.size(18.dp).shadow(2.dp, CircleShape).background(accent, CircleShape))
                "none" -> Spacer(Modifier.size(width = 2.dp, height = 18.dp))
                else -> SliderDefaults.Thumb(interactionSource = interaction, colors = colors)
            }
        },
        track = { st ->
            val m = when (thumbStyle) {
                "circle" -> Modifier.height(6.dp)
                "none" -> Modifier.height(10.dp)
                else -> Modifier
            }
            val gap = if (thumbStyle == "bar") 6.dp else 0.dp
            if (dot) SliderDefaults.Track(sliderState = st, modifier = m, colors = colors, thumbTrackGapSize = gap)
            else SliderDefaults.Track(sliderState = st, modifier = m, colors = colors, thumbTrackGapSize = gap, drawStopIndicator = null)
        },
    )
}

/** Przytrzymanie tagu w odtwarzaczu: wszystkie utwory tagu – jako następne, do kolejki albo do playlisty. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TagActionsSheet(tag: io.github.senk39.droid39.data.TagRow, onDismiss: () -> Unit) {
    val g = App.graph
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rows = rememberLoad("tag", tag.id) { g.library.tagTracks(tag.id) }
    var addPl by remember { mutableStateOf(false) }
    fun toast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C39.Surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text("Tag: ${tag.name}", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium)
            Text(rows?.let { T.tracks(it.size) } ?: "Wczytywanie…", Modifier.padding(horizontal = 20.dp), color = C39.Text2, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            val r = rows.orEmpty()
            SheetItem(Icons39.PlaylistPlay, "Odtwórz wszystkie jako następne") {
                if (r.isNotEmpty()) { g.player.playNext(r); toast("Zagrają jako następne: ${T.tracks(r.size)}") }
                onDismiss()
            }
            SheetItem(Icons39.Queue, "Dodaj wszystkie do kolejki") {
                if (r.isNotEmpty()) { g.player.enqueue(r); toast("Dodano do kolejki: ${T.tracks(r.size)}") }
                onDismiss()
            }
            SheetItem(Icons39.PlaylistAdd, "Dodaj wszystkie do playlisty…") { if (r.isNotEmpty()) addPl = true }
            SheetItem(Icons39.Tag, "Pokaż utwory z tagu") { Nav.playerOpen = false; Nav.go(Route.Tag(tag.id)); onDismiss() }
        }
    }
    if (addPl) AddToPlaylistDialog(rows.orEmpty()) { addPl = false; onDismiss() }
}
