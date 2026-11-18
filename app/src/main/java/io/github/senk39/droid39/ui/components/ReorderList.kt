package io.github.senk39.droid39.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.senk39.droid39.data.TrackRow
import io.github.senk39.droid39.ui.Icons39
import io.github.senk39.droid39.ui.theme.C39
import kotlinx.coroutines.launch

/**
 * Zmiana kolejności utworów: przeciągnięcie za uchwyt (przy krawędzi listy przewija się sama) albo strzałki ↑ ↓.
 * Każde przeniesienie od razu woła [onMove] (utwór, utwór, przed którym ma stanąć – null: na koniec).
 */
@Composable
fun ReorderList(rows: List<TrackRow>, onMove: (TrackRow, TrackRow?) -> Unit) {
    val list = remember(rows) { rows.toMutableStateList() }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    var dragId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var startIndex by remember { mutableStateOf(-1) }

    fun commit(row: TrackRow) {
        val i = list.indexOfFirst { it.id == row.id }
        if (i >= 0 && i != startIndex) onMove(row, list.getOrNull(i + 1))
    }

    fun step(i: Int, delta: Int) {
        val j = i + delta
        if (j !in list.indices) return
        val row = list.removeAt(i)
        list.add(j, row)
        onMove(row, list.getOrNull(j + 1))
    }

    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 96.dp)) {
        itemsIndexed(list, key = { _, r -> r.id }) { i, r ->
            val dragging = r.id == dragId
            Row(
                Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f; shadowElevation = if (dragging) 8f else 0f }
                    .background(if (dragging) C39.Surface2 else C39.Bg)
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).pointerInput(r.id) {
                        detectDragGestures(
                            onDragStart = {
                                dragId = r.id
                                dragOffset = 0f
                                startIndex = list.indexOfFirst { it.id == r.id }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val info = state.layoutInfo
                                val me = info.visibleItemsInfo.firstOrNull { it.key == r.id } ?: return@detectDragGestures
                                val center = me.offset + dragOffset + me.size / 2f
                                val target = info.visibleItemsInfo.firstOrNull {
                                    it.key != r.id && center >= it.offset && center <= it.offset + it.size
                                }
                                if (target != null) {
                                    val from = list.indexOfFirst { it.id == r.id }
                                    list.add(target.index, list.removeAt(from))
                                    dragOffset += me.offset - target.offset
                                }
                                // przy górnej / dolnej krawędzi – przewijanie listy
                                if (center < info.viewportStartOffset + edge) scope.launch { state.scrollBy(-24f) }
                                else if (center > info.viewportEndOffset - edge) scope.launch { state.scrollBy(24f) }
                            },
                            onDragEnd = { commit(r); dragId = null; dragOffset = 0f },
                            onDragCancel = { commit(r); dragId = null; dragOffset = 0f },
                        )
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons39.DragHandle, "Przeciągnij, aby zmienić kolejność", tint = C39.Text2) }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = C39.Text)
                    Text(r.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = C39.Text3)
                }
                SmallIconButton(Icons39.ArrowUp, "W górę", tint = if (i > 0) C39.Text2 else C39.Line, size = 20.dp) { step(i, -1) }
                SmallIconButton(Icons39.ArrowDown, "W dół", tint = if (i < list.lastIndex) C39.Text2 else C39.Line, size = 20.dp) { step(i, 1) }
            }
        }
    }
}
