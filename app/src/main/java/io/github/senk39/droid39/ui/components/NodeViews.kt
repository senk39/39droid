@file:OptIn(ExperimentalLayoutApi::class)

package io.github.senk39.droid39.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.senk39.droid39.core.Text as T
import io.github.senk39.droid39.data.NodeRow
import io.github.senk39.droid39.ui.Icons39
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.LocalAccent

fun nodeIcon(kind: String) = when (kind) {
    "vocap", "vocap_root" -> Icons39.Person
    "vocal", "variant", "vocal_root", "vocal_section" -> Icons39.Mic
    "album" -> Icons39.Album
    "engine", "engine_root" -> Icons39.Equalizer
    else -> Icons39.Folder
}

/** Pigułki silników syntezy przy wokaliście (pełne nazwy albo skróty jak na VocaDB). */
@Composable
fun EnginePills(engines: List<String>) {
    engines.take(4).forEach { e ->
        Spacer(Modifier.width(4.dp))
        Pill(e, C39.EngineColors[e.trimEnd('?')] ?: C39.Text2)
    }
}

@Composable
fun NodeRowView(n: NodeRow, showArt: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt && n.art != null) Art(n.art, null, 44.dp, iconFallback = nodeIcon(n.kind))
        else Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(C39.Surface2), contentAlignment = Alignment.Center) {
            Icon(nodeIcon(n.kind), null, Modifier.size(22.dp), tint = LocalAccent.current)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.vdbArtistId != null) {
                    Spacer(Modifier.width(6.dp)); VdbDot(true, false)
                }
                EnginePills(n.engines)
            }
            val sub = buildString {
                append(T.tracks(n.count))
                if (n.likes > 0) append(" · ♥ ").append(n.likes)
            }
            Text(sub, color = C39.Text3, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons39.Chevron, null, Modifier.size(18.dp), tint = C39.Text3)
    }
}

/** Kafelek z okładką (albumy składanek, półka vocaP, Serie). */
@Composable
fun CoverTile(title: String, subtitle: String?, artKey: String?, size: Dp = 128.dp, onClick: () -> Unit) {
    Column(Modifier.width(size).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(bottom = 4.dp)) {
        Art(artKey, null, size, corner = 10.dp, iconFallback = Icons39.Album)
        Spacer(Modifier.height(5.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        if (subtitle != null) Text(subtitle, color = C39.Text3, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun CoverShelf(title: String, nodes: List<NodeRow>, onOpen: (NodeRow) -> Unit) {
    if (nodes.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(nodes, key = { it.id }) { n -> CoverTile(n.name, T.tracks(n.count), n.art) { onOpen(n) } }
    }
}

@Composable
fun ChipFlow(items: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    FlowRow(modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, act) ->
            Text(
                label, Modifier.clip(RoundedCornerShape(50)).background(C39.Surface2).clickable(onClick = act)
                    .padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp,
            )
        }
    }
}
