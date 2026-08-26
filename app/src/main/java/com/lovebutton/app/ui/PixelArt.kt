package com.lovebutton.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.lovebutton.app.widget.PixelGrids
import com.lovebutton.app.widget.PixelPalette
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.gridNameFor

private fun isSolid(ch: Char) = ch == 'X' || ch == 's'

/**
 * Whether a solid cell sits on the silhouette's edge.
 *
 * Derived exactly as the generator derives it — a solid cell touching an empty
 * cell, a hole, or the grid border. Deriving rather than authoring is what stops
 * the outline and the fill from disagreeing; the same rule has to hold here or
 * the Canvas heart and the VectorDrawable heart would have different outlines.
 */
fun isBorderCell(grid: List<String>, row: Int, col: Int): Boolean {
    if (row == 0 || col == 0 || row == grid.size - 1 || col == grid[row].length - 1) return true
    val neighbours = listOf(row - 1 to col, row + 1 to col, row to col - 1, row to col + 1)
    return neighbours.any { (r, c) ->
        val ch = grid[r][c]
        ch == '.' || ch == 'o'
    }
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
@Composable
fun PixelIcon(msgId: Int, state: WidgetState, modifier: Modifier = Modifier) {
    val grid = PixelGrids.GRIDS[gridNameFor(msgId)] ?: return
    Canvas(modifier = modifier) {
        val cell = minOf(size.width / grid[0].length, size.height / grid.size)
        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, _ ->
                cellColor(grid, r, c, state)?.let { argb ->
                    drawRect(
                        color = Color(argb),
                        topLeft = Offset(c * cell, r * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
