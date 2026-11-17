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
        val frac = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
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
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var addPl by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), C39.Bg, C39.Bg)))
            .background(Color(0x22000000))
            .pointerInput(Unit) { detectVerticalDragGestures { _, drag -> if (drag > 30) Nav.playerOpen = false } },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallIconButton(Icons39.ExpandMore, "Zwiń", tint = C39.Text, size = 28.dp) { Nav.playerOpen = false }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ODTWARZANIE", fontSize = 10.sp, color = C39.Text3, letterSpacing = 1.5.sp)
                    Text(source.ifEmpty { "Kolejka" }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SmallIconButton(Icons39.Queue, "Kolejka", tint = C39.Text) { Nav.playerOpen = false; Nav.go(Route.Queue) }
                SmallIconButton(Icons39.More, "Więcej", tint = C39.Text) { menu = true }
            }
            Spacer(Modifier.weight(0.6f))
            if (row != null) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).shadow(18.dp, RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = { if (total < -80) g.player.next() else if (total > 80) g.player.previous() },
                            ) { _, d -> total += d }
                        },
                ) {
                    Art(row.artKey, row.rel, 360.dp, Modifier.fillMaxSize(), corner = 16.dp)
                }
            } else Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(C39.Surface2))
            Spacer(Modifier.weight(0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            if (vdb != null) {
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vdb.songId?.let { Pill("VocaDB ${T.vdbId(it)}", if (vdb.issue.isNotEmpty()) C39.Warn else C39.Miku, onClick = { info = true }) }
                    if (vdb.songType.isNotEmpty() && vdb.songType != "Original song") Pill(vdb.songType, C39.Gold)
                    vdb.tags.take(4).forEach { t -> Pill(t.name, C39.TagColors[t.category] ?: C39.Text2, onClick = { Nav.playerOpen = false; Nav.go(Route.Tag(t.id)) }) }
                }
            }
            Spacer(Modifier.height(14.dp))
            val dur = ui.durationMs.coerceAtLeast(1)
            val pos = if (seeking) seekPos else ui.positionMs.toFloat()
            Slider(
                value = pos.coerceIn(0f, dur.toFloat()), valueRange = 0f..dur.toFloat(),
                onValueChange = { seeking = true; seekPos = it },
                onValueChangeFinished = { g.player.seekTo(seekPos.toLong()); seeking = false },
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = C39.Surface3),
            )
            Row {
                Text(T.durationMs(pos.toLong()), color = C39.Text2, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (ui.buffering) Text("buforowanie…", color = C39.Text3, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(T.durationMs(ui.durationMs), color = C39.Text2, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                SmallIconButton(Icons39.Shuffle, "Losowo", tint = if (ui.shuffle) accent else C39.Text2, size = 26.dp) { g.player.setShuffle(!ui.shuffle) }
                SmallIconButton(Icons39.Prev, "Poprzedni", tint = C39.Text, size = 38.dp) { g.player.previous() }
                Box(
                    Modifier.size(74.dp).clip(CircleShape).background(accent).clickable { g.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (ui.isPlaying) Icons39.Pause else Icons39.Play, "Odtwórz/Pauza", Modifier.size(40.dp), tint = Color(0xFF00201D))
                }
                SmallIconButton(Icons39.Next, "Następny", tint = C39.Text, size = 38.dp) { g.player.next() }
                SmallIconButton(
                    if (ui.repeat == Player.REPEAT_MODE_ONE) Icons39.RepeatOne else Icons39.Repeat, "Powtarzanie",
                    tint = if (ui.repeat != Player.REPEAT_MODE_OFF) accent else C39.Text2, size = 26.dp,
                ) { g.player.cycleRepeat() }
            }
            ui.error?.let {
                Text(it, color = C39.Warn, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            Spacer(Modifier.weight(0.4f))
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                SmallIconButton(Icons39.PlaylistAdd, "Dodaj do playlisty", tint = C39.Text2) { addPl = true }
                SmallIconButton(Icons39.Info, "Informacje (VocaDB)", tint = C39.Text2) { info = true }
                val local = row?.let { g.files.isLocal(it.id) } ?: false
                Icon(if (local) Icons39.DownloadDone else Icons39.Cloud, if (local) "Na telefonie" else "Strumień z NAS",
                    Modifier.padding(9.dp).size(22.dp), tint = if (local) accent else C39.Text3)
            }
        }
    }
    if (menu && row != null) TrackActionsSheet(listOf(row), ListCtx("Odtwarzacz")) { menu = false }
    if (info && row != null) TrackInfoSheet(row) { info = false }
    if (addPl && row != null) AddToPlaylistDialog(listOf(row)) { addPl = false }
}
