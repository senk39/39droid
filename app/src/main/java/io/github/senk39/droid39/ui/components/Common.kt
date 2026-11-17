@file:OptIn(ExperimentalFoundationApi::class)

package io.github.senk39.droid39.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import io.github.senk39.droid39.App
import io.github.senk39.droid39.ui.Icons39
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Wczytanie danych poza wątkiem głównym (null – jeszcze się wczytuje). */
@Composable
fun <T> rememberLoad(vararg keys: Any?, load: suspend () -> T): T? {
    val state by produceState<T?>(initialValue = null, *keys) { value = withContext(Dispatchers.IO) { load() } }
    return state
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun Art(artKey: String?, rel: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 6.dp, iconFallback: ImageVector = Icons39.Note) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val art = App.graph.art
    val bmp by produceState(initialValue = art.peek(artKey, px), artKey, px) {
        if (value == null) value = art.load(artKey, rel, px)
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner)).background(
            Brush.linearGradient(listOf(C39.Surface3, C39.Surface2))
        ),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(iconFallback, null, Modifier.size(size * 0.45f), tint = C39.Text3)
        }
    }
}

/** Ikona postaci / jednostki Love Live! z assets/ll_icons. */
@Composable
fun LlIcon(name: String?, size: Dp, modifier: Modifier = Modifier) {
    if (name == null) return
    val ctx = LocalContext.current
    val img: ImageBitmap? = remember(name) {
        runCatching { ctx.assets.open("ll_icons/$name").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
    }
    if (img != null) Image(img, null, modifier.size(size), contentScale = ContentScale.Fit)
}

/** Serduszko: 0 – kontur, 1 – ♥ polubione, 2 – ♥♥ najlepsze (dwa nachodzące serca, jak w 39player). */
@Composable
fun HeartIcon(level: Int, size: Dp = 20.dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val m = if (onClick != null) modifier.clip(CircleShape).clickable(onClick = onClick).padding(6.dp) else modifier
    Box(m.size(size + if (level == 2) size * 0.35f else 0.dp), contentAlignment = Alignment.CenterStart) {
        when (level) {
            0 -> Icon(Icons39.HeartOutline, "Polub", Modifier.size(size), tint = C39.Text3)
            1 -> Icon(Icons39.Heart, "Polubione", Modifier.size(size), tint = C39.Heart)
            else -> {
                Icon(Icons39.Heart, null, Modifier.size(size * 0.9f).offset(x = size * 0.42f, y = -(size * 0.08f)), tint = C39.Heart.copy(alpha = 0.55f))
                Icon(Icons39.Heart, "Najlepsze", Modifier.size(size * 0.9f).offset(y = size * 0.08f), tint = C39.Heart)
            }
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = true, onClick: (() -> Unit)? = null) {
    val base = modifier.clip(RoundedCornerShape(50))
    val m = (if (onClick != null) base.clickable(onClick = onClick) else base)
        .background(if (filled) color.copy(alpha = 0.22f) else Color.Transparent)
        .padding(horizontal = 7.dp, vertical = 2.dp)
    Text(text, m, color = if (filled) lighten(color) else color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

fun lighten(c: Color): Color = Color(
    red = (c.red + (1f - c.red) * 0.45f), green = (c.green + (1f - c.green) * 0.45f), blue = (c.blue + (1f - c.blue) * 0.45f), alpha = 1f
)

/** Oznaczenie VocaDB: kropka turkusowa (podpięty), czerwona (niezgodność z VocaDB), szara kreska (potwierdzony brak). */
@Composable
fun VdbDot(linked: Boolean, issue: Boolean, none: Boolean = false) {
    when {
        linked -> Box(Modifier.size(7.dp).clip(CircleShape).background(if (issue) C39.Warn else C39.Miku))
        none -> Box(Modifier.width(8.dp).height(2.dp).background(C39.Text3))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), Modifier.weight(1f), color = LocalAccent.current, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        action?.invoke()
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LocalAccent.current, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun EmptyState(title: String, text: String, icon: ImageVector = Icons39.LibraryMusic, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(56.dp), tint = C39.Text3)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, color = C39.Text2, style = MaterialTheme.typography.bodyMedium)
        action?.invoke()
    }
}

@Composable
fun IconTextRow(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = LocalAccent.current, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = tint)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = C39.Text2, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, color = C39.Text3, style = MaterialTheme.typography.bodySmall)
        Icon(Icons39.Chevron, null, Modifier.size(18.dp), tint = C39.Text3)
    }
}

@Composable
fun SmallIconButton(icon: ImageVector, desc: String, tint: Color = C39.Text2, size: Dp = 22.dp, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).clickable(onClick = onClick).padding(9.dp), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(size), tint = tint)
    }
}
