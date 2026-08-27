package com.lovebutton.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.round
import com.lovebutton.app.widget.PixelGrids
import com.lovebutton.app.widget.PixelPalette
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.gridNameFor

private fun isSolid(ch: Char) = ch == 'X' || ch == 's' || ch == 'd'

/**
 * Whether a solid cell sits on the silhouette's edge.
 *
 * Derived exactly as the generator derives it — a solid cell touching an empty
 * cell or the grid border. Deriving rather than authoring is what stops the
 * outline and the fill from disagreeing; the same rule has to hold here or the
 * Canvas heart and the VectorDrawable heart would have different outlines.
 *
 * A hole is INSIDE the silhouette and casts no outline. This used to count holes
 * as outside, which ringed every eye and every mouth in the dark border colour
 * and was why the speech bubble's face read as uncanny — and it meant this
 * function and the generator disagreed on 22 cells of the old bubble while the
 * comment above claimed they agreed.
 */
fun isBorderCell(grid: List<String>, row: Int, col: Int): Boolean {
    if (row == 0 || col == 0 || row == grid.size - 1 || col == grid[row].length - 1) return true
    val neighbours = listOf(row - 1 to col, row + 1 to col, row to col - 1, row to col + 1)
    return neighbours.any { (r, c) -> grid[r][c] == '.' }
}

private fun darken(argb: Int, f: Float): Int {
    val a = (argb ushr 24) and 0xFF
    val r = (((argb ushr 16) and 0xFF) * f).toInt()
    val g = (((argb ushr 8) and 0xFF) * f).toInt()
    val b = ((argb and 0xFF) * f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * The shade tone for a state — a third value between the border and the fill.
 *
 * Derived from that state's own fill rather than pinned to the border colour,
 * and the distinction matters: the border is *lighter* than the fill while a
 * send is in flight and darker once it has landed, so a pinned shade would flip
 * from shadow to highlight halfway up the ladder. `shade_of` in the generator
 * uses the same factor, so the Canvas and the VectorDrawable agree.
 */
fun shadeFor(state: WidgetState): Int {
    val fill = if (state == WidgetState.SEEN || state == WidgetState.DELIVERED) {
        PixelPalette.Delivered
    } else {
        PixelPalette.Sent
    }
    return darken(fill, 0.72f)
}

/**
 * The box a gild happens inside, for an icon that does not gild its outline.
 *
 * The phone lights its own screen and keypad on `seen` rather than running gold
 * around its rim. Those cells are shine and shade like the top face and the
 * side face are, so cell type alone cannot tell them apart — the region is what
 * separates "the parts that switch on" from "the parts that are just shaped".
 */
data class GildBox(val rows: IntRange, val cols: IntRange)

fun gildBoxFor(gridName: String): GildBox? = when (gridName) {
    "call" -> GildBox(rows = 5..17, cols = 5..12)
    else -> null
}

/** Which columns hold eyes that blink, and how far down the grid they run. */
data class Eyes(val cols: List<Int>, val maxRow: Int)

fun eyesFor(gridName: String): Eyes? = when (gridName) {
    "bubble" -> Eyes(cols = listOf(5, 10), maxRow = 6)
    else -> null
}

/**
 * The eyes part-closed: each vertical run of eye holes eaten from both ends.
 *
 * The eyes are holes in the grid so the widget gets the face for free as a
 * static picture; closing them is the app's job alone. The smile's raised
 * corners stand in the same two columns as the eyes, which is what `maxRow` is
 * for — without it a blink flattens the mouth along with the eyes.
 */
fun shrinkEyes(grid: List<String>, eyes: Eyes?, k: Int): List<String> {
    if (eyes == null || k <= 0) return grid
    val rows = grid.map { it.toCharArray() }
    for (col in eyes.cols) {
        var r = 0
        while (r < grid.size) {
            if (grid[r][col] != 'o') {
                r++
                continue
            }
            val start = r
            while (r < grid.size && grid[r][col] == 'o') r++
            val end = r - 1
            if (start > eyes.maxRow) continue
            for (i in 0 until k) {
                if (start + i <= end - i) {
                    rows[start + i][col] = 'X'
                    rows[end - i][col] = 'X'
                }
            }
        }
    }
    return rows.map { String(it) }
}

/**
 * The colour one cell is painted in one state, or null to leave it empty.
 *
 * This is the Canvas equivalent of what `pixel_icons.py` bakes into each
 * VectorDrawable, and it must agree with it cell for cell.
 */
fun cellColor(grid: List<String>, row: Int, col: Int, state: WidgetState): Int? {
    val ch = grid[row][col]
    if (!isSolid(ch)) return null

    val border = isBorderCell(grid, row, col)
    return when (state) {
        WidgetState.IDLE -> if (border) PixelPalette.Idle else null
        WidgetState.FAILED -> if (border) PixelPalette.Failed else null
        else -> when {
            ch == 's' -> PixelPalette.Shine
            ch == 'd' -> shadeFor(state)
            state == WidgetState.SEEN -> if (border) PixelPalette.Gold else PixelPalette.Delivered
            state == WidgetState.DELIVERED -> if (border) PixelPalette.Border else PixelPalette.Delivered
            else -> if (border) PixelPalette.Border else PixelPalette.Sent
        }
    }
}

/**
 * One message's icon, drawn cell by cell at whatever size it is given.
 *
 * Canvas rather than the VectorDrawable because a VectorDrawable cannot be lit
 * one cell at a time, and lighting cells one at a time is the entire animation
 * (see PixelLadder.kt). The grid comes from the generator, so this is the same
 * artwork rather than a second drawing of it.
 */
/**
 * One cell's rectangle, snapped so that neighbouring cells share an edge exactly.
 *
 * `cell` is only a whole number of device pixels when the grid divides the
 * canvas evenly. The old 11x11 grids happened to do that at every density this
 * app runs at; 16, 20 and 22 do not. Drawing at raw fractional offsets then
 * leaves a hairline gap along most cell edges, and the white card behind shows
 * through them — which is what the pale squares around every pixel were.
 */
fun cellRect(row: Int, col: Int, cell: Float): Pair<Offset, Size> {
    val x0 = round(col * cell)
    val y0 = round(row * cell)
    val x1 = round((col + 1) * cell)
    val y1 = round((row + 1) * cell)
    return Offset(x0, y0) to Size(x1 - x0, y1 - y0)
}

@Composable
fun PixelIcon(msgId: Int, state: WidgetState, modifier: Modifier = Modifier) {
    val grid = PixelGrids.GRIDS[gridNameFor(msgId)] ?: return
    Canvas(modifier = modifier) {
        val cell = minOf(size.width / grid[0].length, size.height / grid.size)
        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, _ ->
                cellColor(grid, r, c, state)?.let { argb ->
                    val (topLeft, size) = cellRect(r, c, cell)
                    drawRect(color = Color(argb), topLeft = topLeft, size = size)
                }
            }
        }
    }
}
