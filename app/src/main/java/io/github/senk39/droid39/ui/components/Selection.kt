package io.github.senk39.droid39.ui.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.senk39.droid39.App
import io.github.senk39.droid39.core.Text as T
import io.github.senk39.droid39.data.TrackRow
import io.github.senk39.droid39.ui.Icons39
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** Zaznaczone utwory listy (po id). Pusta – zwykły tryb: dotknięcie odtwarza. */
@Stable
class TrackSelection {
    private val ids = mutableStateMapOf<Int, Unit>()
    val active: Boolean get() = ids.isNotEmpty()
    val count: Int get() = ids.size
    fun isSelected(id: Int) = ids.containsKey(id)
    fun toggle(id: Int) {
        if (ids.containsKey(id)) ids.remove(id) else ids[id] = Unit
    }
    fun setAll(all: List<Int>) {
        ids.clear()
        all.forEach { ids[it] = Unit }
    }
    fun clear() = ids.clear()
}

/**
 * Pasek zaznaczania na dole listy: licznik „zaznaczone / wszystkie”, zaznacz wszystkie, a pod spodem: do playlisty
 * (także nowej), do kolejki, odtwórz jako następne, usuń (zależnie od miejsca, z potwierdzeniem), udostępnij.
 */
@Composable
fun SelectionBar(sel: TrackSelection, all: List<TrackRow>, ctx: ListCtx, modifier: Modifier = Modifier) {
    val g = App.graph
    val context = LocalContext.current
    val accent = LocalAccent.current
    val rows = all.filter { sel.isSelected(it.id) }.distinctBy { it.id }
    val total = all.distinctBy { it.id }.size
    var addToPl by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val inPlaylist = ctx.playlistRel != null && remember(ctx.playlistRel) { g.library.playlistEditable(ctx.playlistRel) }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    Column(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp))
            .background(C39.Surface2).padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallIconButton(Icons39.Close, "Zakończ zaznaczanie", tint = C39.Text) { sel.clear() }
            Text("${rows.size} / $total", Modifier.weight(1f), color = C39.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val allSelected = rows.size >= total
            Text(
                if (allSelected) "Odznacz wszystkie" else "Zaznacz wszystkie",
                Modifier.clip(RoundedCornerShape(50)).clickable {
                    if (allSelected) sel.clear() else sel.setAll(all.map { it.id })
                }.padding(horizontal = 12.dp, vertical = 8.dp),
                color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarAction(Icons39.PlaylistAdd, "Do playlisty") { addToPl = true }
            BarAction(Icons39.Queue, "Do kolejki") {
                g.player.enqueue(rows); toast("Dodano do kolejki: ${T.tracks(rows.size)}"); sel.clear()
            }
            BarAction(Icons39.PlaylistPlay, "Następne") {
                g.player.playNext(rows); toast("Zagrają jako następne: ${T.tracks(rows.size)}"); sel.clear()
            }
            BarAction(Icons39.Delete, if (inPlaylist) "Usuń z listy" else "Usuń") { delete = true }
            BarAction(Icons39.Share, "Udostępnij") { shareTracks(context, rows) }
        }
    }
    if (addToPl) AddToPlaylistDialog(rows) { addToPl = false; sel.clear() }
    if (delete) DeleteDialog(rows, ctx, inPlaylist, onDone = { done -> delete = false; if (done) sel.clear() })
}

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(24.dp), tint = C39.Text)
        Text(label, color = C39.Text2, fontSize = 11.sp, maxLines = 1)
    }
}

/**
 * Usuwanie zależnie od miejsca: na playliście – z playlisty (trafia do 39player); gdzie indziej – kopie pobrane na
 * telefon (biblioteka, playlisty, karta pamięci, NAS i PC bez zmian).
 */
@Composable
private fun DeleteDialog(rows: List<TrackRow>, ctx: ListCtx, inPlaylist: Boolean, onDone: (Boolean) -> Unit) {
    val g = App.graph
    val context = LocalContext.current
    if (inPlaylist) {
        val name = ctx.playlistRel!!.substringAfterLast('/').substringBeforeLast('.')
        AlertDialog(
            onDismissRequest = { onDone(false) },
            title = { Text("Usunąć z playlisty „$name”?") },
            text = { Text("${T.tracks(rows.size)} zniknie z tej playlisty (także w 39player po synchronizacji). Utwory zostają w bibliotece.") },
            confirmButton = {
                TextButton(onClick = {
                    g.scope.launch(Dispatchers.IO) { g.library.removeFromPlaylist(ctx.playlistRel, rows) }
                    Toast.makeText(context, "Usunięto z playlisty: ${T.tracks(rows.size)}", Toast.LENGTH_SHORT).show()
                    onDone(true)
                }) { Text("Usuń", color = C39.Warn) }
            },
            dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Anuluj") } },
        )
        return
    }
    val downloaded = remember(rows) { rows.filter { g.files.find(it.rel)?.source == "dl" } }
    if (downloaded.isEmpty()) {
        AlertDialog(
            onDismissRequest = { onDone(false) },
            title = { Text("Nic do usunięcia z telefonu") },
            text = { Text("Żaden z zaznaczonych utworów nie jest pobrany na telefon. Pliki z karty pamięci, NAS i PC " +
                "nie są stąd usuwane – utwór z playlisty usuniesz na ekranie tej playlisty.") },
            confirmButton = { TextButton(onClick = { onDone(false) }) { Text("OK") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("Usunąć pobrane kopie z telefonu?") },
        text = {
            Text("Pobrane na telefon: ${T.tracks(downloaded.size)} z ${rows.size} zaznaczonych. Biblioteka, playlisty oraz " +
                "pliki na karcie pamięci, NAS i PC się nie zmienią – utwory dalej zagrają z NAS. Utwory z reguł " +
                "„Zawsze na telefonie” mogą zostać pobrane ponownie.")
        },
        confirmButton = {
            TextButton(onClick = {
                g.scope.launch(Dispatchers.IO) {
                    downloaded.forEach { g.files.removeDownload(it.rel) }
                    g.files.recomputeAvailable(g.library)
                }
                Toast.makeText(context, "Usunięto z telefonu: ${T.tracks(downloaded.size)}", Toast.LENGTH_SHORT).show()
                onDone(true)
            }) { Text("Usuń z telefonu", color = C39.Warn) }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Anuluj") } },
    )
}

/**
 * Udostępnianie: pliki audio utworów, które są na telefonie (pobrane albo na karcie); utwory tylko na NAS – jako
 * tekst „Tytuł – Wykonawca”.
 */
fun shareTracks(context: Context, rows: List<TrackRow>) {
    val files = App.graph.files
    val uris = ArrayList<Uri>()
    val textOnly = ArrayList<String>()
    for (r in rows) {
        val lf = files.resolve(r.rel)
        val uri = when {
            lf == null -> null
            lf.path.startsWith("content://") -> Uri.parse(lf.path)        // kopia na karcie (folder wybrany w systemie)
            else -> File(lf.path).takeIf { it.exists() }?.let { FileProvider.getUriForFile(context, "${context.packageName}.share", it) }
        }
        if (uri != null) uris += uri
        else textOnly += "${r.title.ifEmpty { r.rel.substringAfterLast('/') }} – ${r.artist}"
    }
    val intent = when {
        uris.isEmpty() -> Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, textOnly.joinToString("\n"))
        uris.size == 1 -> Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, uris[0])
        else -> Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    if (uris.isNotEmpty()) {
        if (textOnly.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, "Bez pliku (tylko na NAS):\n" + textOnly.joinToString("\n"))
        val clip = ClipData.newRawUri("", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Udostępnij ${T.tracks(rows.size)}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
