package io.github.senk39.droid39.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.senk39.droid39.ui.Icons39
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Przeciągnięcie palcem od lewej krawędzi ekranu w prawo = „wstecz” (o poziom wyżej: Anime/BanG Dream! → Anime →
 * ekran główny). Działa także przy nawigacji przyciskami, gdy system nie ma gestu „wstecz”; przy nawigacji gestami
 * krawędź obsługuje system (to samo działanie). Ekran przesuwa się za palcem; puszczenie po ponad 1/3 szerokości albo
 * szybki ruch – powrót, wcześniej – ekran wraca na miejsce.
 */
@Composable
fun EdgeSwipeBack(enabled: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    val edge = with(density) { 28.dp.toPx() }
    val flingPx = with(density) { 900.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxSize().pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.x > edge) return@awaitEachGesture
                val vt = VelocityTracker()
                vt.addPosition(down.uptimeMillis, down.position)
                var dragging = false
                var dx = 0f
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    vt.addPosition(ch.uptimeMillis, ch.position)
                    dx = ch.position.x - down.position.x
                    val dy = ch.position.y - down.position.y
                    if (!dragging) {
                        if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture   // przewijanie w pionie
                        if (dx > slop) dragging = true
                    }
                    if (dragging) {
                        ch.consume()                                                    // lista / karuzela nie dostaje ruchu
                        scope.launch { offset.snapTo(dx.coerceAtLeast(0f)) }
                    }
                    if (!ch.pressed) break
                }
                if (!dragging) return@awaitEachGesture
                val back = dx > size.width / 3f || vt.calculateVelocity().x > flingPx
                scope.launch {
                    if (back) {
                        offset.animateTo(size.width.toFloat(), tween(140))
                        onBack()
                        offset.snapTo(0f)
                    } else offset.animateTo(0f, tween(180))
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = offset.value }) { content() }
        if (offset.value > 0f) {
            // strzałka „wstecz” przy krawędzi – jak systemowy gest
            val progress = (offset.value / with(density) { 120.dp.toPx() }).coerceIn(0f, 1f)
            Box(
                Modifier.align(Alignment.CenterStart)
                    .offset { IntOffset((offset.value * 0.35f).roundToInt() - with(density) { 44.dp.roundToPx() } + (progress * with(density) { 56.dp.toPx() }).roundToInt(), 0) }
                    .size(44.dp).clip(CircleShape).background(C39.Surface3.copy(alpha = 0.92f)).padding(10.dp),
            ) {
                Icon(Icons39.Back, null, Modifier.fillMaxSize().graphicsLayer { alpha = progress }, tint = LocalAccent.current)
            }
        }
    }
}
