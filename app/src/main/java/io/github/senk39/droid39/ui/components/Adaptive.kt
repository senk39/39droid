package io.github.senk39.droid39.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration

/** Ile kolumn mają listy: 2, gdy okno jest szerokie (telefon poziomo, tablet, szeroki podzielony ekran). */
@Composable
fun listColumns(): Int = if (LocalConfiguration.current.screenWidthDp >= 600) 2 else 1

/** Elementy listy w [cols] kolumnach (wiersz po [cols] obok siebie); przy jednej kolumnie – zwykła lista. */
fun <T> LazyListScope.gridItems(
    list: List<T>,
    cols: Int,
    key: ((Int, T) -> Any)? = null,
    content: @Composable (Int, T) -> Unit,
) {
    if (cols <= 1) {
        items(list.size, key = key?.let { k -> { i: Int -> k(i, list[i]) } }) { i -> content(i, list[i]) }
        return
    }
    val rows = (list.size + cols - 1) / cols
    items(rows, key = key?.let { k -> { r: Int -> "row:" + k(r * cols, list[r * cols]) } }) { r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            for (c in 0 until cols) {
                val i = r * cols + c
                Box(Modifier.weight(1f)) { if (i < list.size) content(i, list[i]) }
            }
        }
    }
}

/** Kilka gotowych wierszy (np. pozycje ekranu głównego) w [cols] kolumnach. */
fun LazyListScope.gridBlocks(cols: Int, keyPrefix: String, blocks: List<@Composable () -> Unit>) =
    gridItems(blocks, cols, key = { i, _ -> "$keyPrefix$i" }) { _, b -> b() }
