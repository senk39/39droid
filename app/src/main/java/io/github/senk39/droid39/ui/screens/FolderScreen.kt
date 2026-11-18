package io.github.senk39.droid39.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.senk39.droid39.App
import io.github.senk39.droid39.core.Text as T
import io.github.senk39.droid39.data.FolderEntry
import io.github.senk39.droid39.ui.Icons39
import io.github.senk39.droid39.ui.Nav
import io.github.senk39.droid39.ui.Route
import io.github.senk39.droid39.ui.components.ListCtx
import io.github.senk39.droid39.ui.components.Loading
import io.github.senk39.droid39.ui.components.TrackListScreen
import io.github.senk39.droid39.ui.components.rememberLoad
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.LocalAccent

/**
 * Przeglądanie biblioteki po folderach (jak F:\MUZYKA na PC i kopia na NAS): podfoldery z liczbą utworów, pod nimi
 * utwory leżące bezpośrednio w folderze. „Odtwórz” / „Losowo” w folderze bez własnych utworów grają cały folder
 * z podfolderami. Gest „wstecz” wraca o poziom wyżej.
 */
@Composable
fun FolderScreen(path: String) {
    val lib = App.graph.library
    val snapV by lib.snapVersion.collectAsState()
    val listing = rememberLoad(path, snapV) { lib.folderListing(path) }
    val name = if (path.isEmpty()) "Foldery" else path.substringAfterLast('/')
    Column(Modifier.fillMaxSize()) {
        TopBar(if (path.isEmpty()) "Foldery" else path.replace("/", " › "))
        if (listing == null) {
            Loading()
            return@Column
        }
        val subtitle = buildString {
            if (listing.folders.isNotEmpty()) append("${listing.folders.size} ${foldersWord(listing.folders.size)} · ")
            append(T.tracks(listing.total))
        }
        TrackListScreen(
            rows = listing.tracks,
            ctx = ListCtx("Folder: $name"),
            title = name,
            subtitle = subtitle,
            headerRel = listing.tracks.firstOrNull()?.rel,
            headerArt = listing.tracks.firstOrNull()?.artKey,
            emptyText = if (listing.folders.isEmpty()) "Pusty folder" else "",
            playRows = { lib.folderTracks(path) },
            header = {
                items(listing.folders, key = { "dir:" + it.path }) { f -> FolderRow(f) }
            },
        )
    }
}

@Composable
private fun FolderRow(f: FolderEntry) {
    Row(
        Modifier.fillMaxWidth().clickable { Nav.go(Route.Folder(f.path)) }.padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons39.Folder, null, Modifier.size(24.dp), tint = LocalAccent.current)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(T.tracks(f.count), color = C39.Text3, fontSize = 12.sp)
        }
        Icon(Icons39.Chevron, null, Modifier.size(20.dp), tint = C39.Text3)
    }
}

private fun foldersWord(n: Int): String = when {
    n == 1 -> "folder"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "foldery"
    else -> "folderów"
}
