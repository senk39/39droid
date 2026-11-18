package io.github.senk39.droid39.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Przeciągnięcie palcem w prawo = „wstecz” (o poziom wyżej: Anime/BanG Dream! → Anime → ekran główny). Gest można
 * zacząć w lewej części ekranu, nie tylko przy krawędzi; działa też przy nawigacji przyciskami. W trakcie przeciągania
 * bieżący ekran przesuwa się za palcem, a spod niego wyłania się ekran, do którego się wraca ([behind]).
 *
 *  * od samej krawędzi (28 dp) gest ma pierwszeństwo przed wszystkim pod palcem,
 *  * dalej od krawędzi – tylko gdy nic innego nie przejęło ruchu (suwak, przewijane w bok półki, gesty na okładce),
 *  * ruch w pionie zostaje przewijaniem listy.
 */
@Composable
fun EdgeSwipeBack(
    enabled: Boolean,
    onBack: () -> Unit,
    behind: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    val edge = with(density) { 28.dp.toPx() }
    val flingPx = with(density) { 800.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxSize().pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val fromEdge = down.position.x <= edge
                if (!fromEdge && down.position.x > size.width * 0.6f) return@awaitEachGesture
                val pass = if (fromEdge) PointerEventPass.Initial else PointerEventPass.Main
                val vt = VelocityTracker()
                vt.addPosition(down.uptimeMillis, down.position)
                var dragging = false
                var dx = 0f
                while (true) {
                    val ev = awaitPointerEvent(pass)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!dragging && !fromEdge && ch.isConsumed) return@awaitEachGesture   // ruch przejął element pod palcem
                    vt.addPosition(ch.uptimeMillis, ch.position)
                    dx = ch.position.x - down.position.x
                    val dy = ch.position.y - down.position.y
                    if (!dragging) {
                        if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture     // przewijanie w pionie
                        if (dx > slop * 1.5f && dx > abs(dy) * 1.5f) dragging = true
                        else if (dx < -slop) return@awaitEachGesture                         // ruch w lewo – nie nasz
                    }
                    if (dragging) {
                        ch.consume()
                        scope.launch { offset.snapTo(dx.coerceAtLeast(0f)) }
                    }
                    if (!ch.pressed) break
                }
                if (!dragging) return@awaitEachGesture
                val back = dx > size.width * 0.3f || vt.calculateVelocity().x > flingPx
                scope.launch {
                    if (back) {
                        offset.animateTo(size.width.toFloat(), tween(160))
                        onBack()
                        offset.snapTo(0f)
                    } else offset.animateTo(0f, tween(200))
                }
            }
        },
    ) {
        val x = offset.value
        if (x > 0f) {
            // ekran, do którego się wraca: lekko przesunięty w lewo i przyciemniony, odsłania się za palcem
            val progress = (x / with(density) { 400.dp.toPx() }).coerceIn(0f, 1f)
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = -size.width * 0.25f * (1f - progress) }) { behind() }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * (1f - progress))))
        }
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = x }) { content() }
        if (x > 0f) {
            // cień na krawędzi przesuwanego ekranu
            Box(
                Modifier.align(Alignment.CenterStart).offset { IntOffset(x.roundToInt() - with(density) { 12.dp.roundToPx() }, 0) }
                    .width(12.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f)))),
            )
        }
    }
}
