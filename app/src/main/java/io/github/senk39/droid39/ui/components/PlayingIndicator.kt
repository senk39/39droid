package io.github.senk39.droid39.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.senk39.droid39.R
import io.github.senk39.droid39.ui.Icons39
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Style animacji „teraz gra” na okładce odtwarzanego utworu (Ustawienia → Wygląd i interfejs). */
val PLAYING_ANIMS = listOf(
    "bars" to "Paski", "wave" to "Fala", "dots" to "Kropki", "pulse" to "Puls", "note" to "Nuta", "static" to "Bez animacji",
)

/**
 * Zapętlona animacja odtwarzanej muzyki: [style] – "bars" (trzy skaczące paski), "wave" (fala pięciu pasków),
 * "dots" (podskakujące kropki), "pulse" (rozchodzące się kręgi), "note" (kołysząca się nuta), "static" (bez ruchu).
 */
@Composable
fun PlayingIndicator(style: String, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    if (style == "static") {
        Icon(Icons39.Equalizer, null, modifier, tint = color)
        return
    }
    val period = when (style) { "pulse" -> 1600; "note" -> 1400; "wave" -> 1000; else -> 1200 }
    val t = rememberInfiniteTransition(label = "playing")
    // faza 0..1; częstotliwości to całkowite wielokrotności fazy – pętla bez przeskoku
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing)), label = "phase")
    if (style == "note") {
        Icon(
            painterResource(R.drawable.ic_w_note), null,
            modifier.graphicsLayer {
                translationY = sin(2 * PI * p).toFloat() * size.height * 0.12f
                rotationZ = sin(2 * PI * p + PI / 2).toFloat() * 12f
            },
            tint = color,
        )
        return
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun wave(k: Int, offset: Float) = (0.5f + 0.5f * sin(2 * PI * (k * p + offset)).toFloat())
        when (style) {
            "wave" -> {
                val n = 5
                val bw = w / (n * 2 - 1)
                for (i in 0 until n) {
                    val a = 0.2f + 0.8f * wave(1, -i / n.toFloat())
                    val bh = h * a
                    drawRoundRect(color, Offset(i * 2 * bw, (h - bh) / 2), Size(bw, bh), CornerRadius(bw / 2, bw / 2))
                }
            }
            "dots" -> {
                val r = w / 9
                for (i in 0 until 3) {
                    val up = abs(sin(PI * (p - i / 6f)).toFloat())
                    val cx = w / 2 + (i - 1) * r * 3.2f
                    drawCircle(color, r, Offset(cx, h - r - up * (h - 2 * r) * 0.8f))
                }
            }
            "pulse" -> {
                val maxR = minOf(w, h) / 2
                drawCircle(color, maxR * 0.28f, Offset(w / 2, h / 2))
                for (k in 0 until 2) {
                    val q = (p + k / 2f) % 1f
                    drawCircle(
                        color.copy(alpha = color.alpha * (1f - q)), maxR * (0.3f + 0.65f * q), Offset(w / 2, h / 2),
                        style = Stroke(width = w / 14, cap = StrokeCap.Round),
                    )
                }
            }
            else -> {                                                  // trzy paski
                val bw = w / 5
                val freq = intArrayOf(1, 2, 1)
                val off = floatArrayOf(0f, 0.3f, 0.6f)
                for (i in 0 until 3) {
                    val bh = h * (0.2f + 0.8f * wave(freq[i], off[i]))
                    drawRoundRect(color, Offset(i * 2 * bw, h - bh), Size(bw, bh), CornerRadius(bw / 3, bw / 3))
                }
            }
        }
    }
}
