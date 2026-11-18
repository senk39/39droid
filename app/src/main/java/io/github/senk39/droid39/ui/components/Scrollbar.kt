package io.github.senk39.droid39.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.senk39.droid39.ui.theme.LocalAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/** LazyColumn z paskiem przewijania (pojawia się przy przewijaniu; uchwyt można przeciągać – szybkie przewijanie). */
@Composable
fun ScrollColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
    content: LazyListScope.() -> Unit,
) {
    ScrollbarBox(state, modifier) { LazyColumn(state = state, contentPadding = contentPadding, content = content) }
}

/**
 * Pasek przewijania nad listą. Widoczny w trakcie przewijania i chwilę po nim; przeciągnięcie uchwytu przewija
 * listę proporcjonalnie (przy kilkunastu tysiącach utworów to jedyny szybki sposób). Dotknięcia poza uchwytem
 * trafiają do listy bez zmian.
 */
@Composable
fun ScrollbarBox(state: LazyListState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val accent = LocalAccent.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    val active = state.isScrollInProgress || dragging
    LaunchedEffect(active) {
        if (active) visible = true
        else {
            delay(1500)
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 120 else 450), label = "scrollbar")
    val thumbW = with(density) { 5.dp.toPx() }
    val thumbWDrag = with(density) { 8.dp.toPx() }
    val margin = with(density) { 3.dp.toPx() }
    val minThumb = with(density) { 48.dp.toPx() }
    val grabZone = with(density) { 36.dp.toPx() }
    val grabSlop = with(density) { 12.dp.toPx() }

    /** (górna krawędź uchwytu, wysokość uchwytu) albo null, gdy lista mieści się na ekranie. */
    fun metrics(height: Float): Pair<Float, Float>? {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        val total = info.totalItemsCount
        if (total == 0 || items.isEmpty()) return null
        val avg = items.sumOf { it.size }.toFloat() / items.size
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val contentH = avg * total
        if (contentH <= viewport * 1.05f) return null
        val thumbH = max(minThumb, height * viewport / contentH).coerceAtMost(height * 0.5f)
        val fraction = if (dragging) dragFraction
        else ((state.firstVisibleItemIndex * avg + state.firstVisibleItemScrollOffset) / (contentH - viewport)).coerceIn(0f, 1f)
        return fraction * (height - thumbH) to thumbH
    }

    Box(
        modifier.fillMaxSize().pointerInput(state) {
            awaitEachGesture {
                // przejęcie gestu tylko, gdy zaczyna się na widocznym uchwycie (faza Initial – przed listą)
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!visible) return@awaitEachGesture
                val m = metrics(size.height.toFloat()) ?: return@awaitEachGesture
                val onThumb = down.position.x >= size.width - grabZone &&
                    down.position.y in (m.first - grabSlop)..(m.first + m.second + grabSlop)
                if (!onThumb) return@awaitEachGesture
                down.consume()
                val grab = (down.position.y - m.first).coerceIn(0f, m.second)
                val track = (size.height - m.second).coerceAtLeast(1f)
                dragFraction = (m.first / track).coerceIn(0f, 1f)
                dragging = true
                try {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        ch.consume()
                        if (!ch.pressed) break
                        dragFraction = ((ch.position.y - grab) / track).coerceIn(0f, 1f)
                        val total = state.layoutInfo.totalItemsCount
                        if (total > 0) {
                            val target = (dragFraction * (total - 1)).roundToInt()
                            scope.launch { state.scrollToItem(target) }
                        }
                    }
                } finally {
                    dragging = false
                }
            }
        },
    ) {
        content()
        Canvas(Modifier.fillMaxSize()) {
            if (alpha <= 0.01f) return@Canvas
            val m = metrics(size.height) ?: return@Canvas
            val w = if (dragging) thumbWDrag else thumbW
            drawRoundRect(
                color = accent.copy(alpha = (if (dragging) 0.95f else 0.7f) * alpha),
                topLeft = Offset(size.width - w - margin, m.first),
                size = Size(w, m.second),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}
