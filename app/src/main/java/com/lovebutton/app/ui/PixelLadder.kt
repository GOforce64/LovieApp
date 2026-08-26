package com.lovebutton.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.lovebutton.app.widget.PixelGrids
import com.lovebutton.app.widget.PixelPalette
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.gridNameFor
import kotlin.math.abs
import kotlin.math.hypot

private fun isSolid(ch: Char) = ch == 'X' || ch == 's'

/** How many rows, counted from the bottom, the rising fill has covered. */
fun fillRowsVisible(grid: List<String>, progress: Float): Int =
    (progress.coerceIn(0f, 1f) * grid.size).toInt().coerceIn(0, grid.size)

/** Whether the delivered ripple, expanding from the centre, has reached a cell. */
fun rippleReached(grid: List<String>, row: Int, col: Int, progress: Float): Boolean {
    val cr = (grid.size - 1) / 2f
    val cc = (grid[0].length - 1) / 2f
    val maxRadius = hypot(grid.size.toFloat(), grid[0].length.toFloat())
    return hypot(row - cr, col - cc) <= progress.coerceIn(0f, 1f) * maxRadius
}

private typealias Cell = Pair<Int, Int>

/** Enough backtracking for an 11x11 icon; a walk needs a few thousand at most. */
private const val WALK_BUDGET = 300_000

/**
 * The border cells split into pieces that actually touch each other.
 *
 * The paw's toes and the call icon's parts are separate blobs, so their border
 * is not one ring and no single adjacent walk over it can exist. Gilding one
 * piece and then starting the next is the only honest reading of those shapes.
 */
private fun componentsOf(cells: List<Cell>, neighbours: Map<Cell, List<Cell>>): List<List<Cell>> {
    val seen = HashSet<Cell>()
    val components = mutableListOf<List<Cell>>()
    for (cell in cells) {
        if (!seen.add(cell)) continue
        val stack = ArrayDeque(listOf(cell))
        val component = mutableListOf<Cell>()
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            component.add(next)
            neighbours.getValue(next).forEach { if (seen.add(it)) stack.addLast(it) }
        }
        components.add(component.sortedWith(compareBy({ it.first }, { it.second })))
    }
    return components
}

/**
 * A walk touching every cell of one piece exactly once, or null if there is none.
 *
 * Plain nearest-neighbour was tried first and strands itself: on the 5x5 test
 * block it paints its way into a corner and has to jump the shape to finish.
 * So this backtracks, and prefers the most constrained cell at each step
 * (Warnsdorff) so it almost never has to. The budget is per start cell — shared
 * across starts, the first hopeless start drains it and the rest never run.
 */
private fun walkOnce(component: List<Cell>, neighbours: Map<Cell, List<Cell>>): List<Cell>? {
    val inComponent = component.toHashSet()
    for (from in component) {
        var budget = WALK_BUDGET
        val path = mutableListOf(from)
        val visited = hashSetOf(from)

        fun step(): Boolean {
            if (path.size == component.size) return true
            if (--budget <= 0) return false
            val candidates = neighbours.getValue(path.last())
                .filter { it in inComponent && it !in visited }
                .sortedBy { n -> neighbours.getValue(n).count { it in inComponent && it !in visited } }
            for (n in candidates) {
                path.add(n)
                visited.add(n)
                if (step()) return true
                path.removeAt(path.size - 1)
                visited.remove(n)
            }
            return false
        }

        if (step()) return path
    }
    return null
}

/**
 * The border cells ordered as a walk around the silhouette.
 *
 * Sorting by angle leaves jumps wherever the outline is more than one cell
 * thick, and a jump reads as flicker rather than as light running around the
 * edge. So the outline is split into pieces that touch, and each piece is
 * walked cell to adjacent cell. A piece with no such walk falls back to scan
 * order: it flickers, but it still gilds every cell exactly once.
 */
fun borderRingOrder(grid: List<String>): List<Cell> {
    val cells = mutableListOf<Cell>()
    grid.forEachIndexed { r, row ->
        row.forEachIndexed { c, ch ->
            if (isSolid(ch) && isBorderCell(grid, r, c)) cells.add(r to c)
        }
    }
    if (cells.isEmpty()) return emptyList()

    // Diagonal neighbours count as adjacent, which is what "is a loop" means
    // on a pixel grid and what the test asserts.
    val neighbours: Map<Cell, List<Cell>> = cells.associateWith { (r, c) ->
        cells.filter { (nr, nc) -> (nr != r || nc != c) && abs(nr - r) <= 1 && abs(nc - c) <= 1 }
    }

    return componentsOf(cells, neighbours).flatMap { walkOnce(it, neighbours) ?: it }
}

/**
 * The focal heart, animated per pixel.
 *
 * The widget swaps between five finished pictures because RemoteViews can do
 * little else. This is Compose, so each state gets motion that says what
 * happened: the fill rises while the request is in flight, the buzz ripples
 * outward when her phone goes off, and gold runs around the outline when she
 * looks. `seen` is the only state with a flourish, because it is the only one
 * that is the point of the app.
 */
@Composable
fun AnimatedPixelIcon(msgId: Int, state: WidgetState, modifier: Modifier = Modifier) {
    val grid = PixelGrids.GRIDS[gridNameFor(msgId)] ?: return
    val context = LocalContext.current
    val animationsOn = remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }

    val transition = rememberInfiniteTransition(label = "ladder")
    val loop by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "loop",
    )

    // One-shot progress for the states that play once and settle.
    var shot by remember(state) { mutableFloatStateOf(0f) }
    LaunchedEffect(state) {
        shot = 0f
        val steps = 24
        repeat(steps) {
            kotlinx.coroutines.delay(28)
            shot = (it + 1) / steps.toFloat()
        }
        shot = 1f
    }

    // With animations off, every state renders as its plain finished picture.
    val progress = if (!animationsOn) 1f else when (state) {
        WidgetState.SENDING -> loop
        else -> shot
    }
    val ring = remember(grid) { borderRingOrder(grid) }

    Canvas(modifier = modifier) {
        val cell = minOf(size.width / grid[0].length, size.height / grid.size)
        val goldCount = (ring.size * progress).toInt()
        val gilded = if (state == WidgetState.SEEN) ring.take(goldCount).toHashSet() else emptySet()

        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                val argb: Int? = when {
                    !isSolid(ch) -> null

                    state == WidgetState.SENDING -> {
                        val filledFrom = grid.size - fillRowsVisible(grid, progress)
                        if (r >= filledFrom) cellColor(grid, r, c, WidgetState.SENT)
                        else cellColor(grid, r, c, WidgetState.IDLE)
                    }

                    state == WidgetState.DELIVERED ->
                        if (rippleReached(grid, r, c, progress)) cellColor(grid, r, c, WidgetState.DELIVERED)
                        else cellColor(grid, r, c, WidgetState.SENT)

                    state == WidgetState.SEEN -> when {
                        ch == 's' -> PixelPalette.Shine
                        (r to c) in gilded -> PixelPalette.Gold
                        else -> cellColor(grid, r, c, WidgetState.DELIVERED)
                    }

                    else -> cellColor(grid, r, c, state)
                }

                argb?.let {
                    drawRect(
                        color = Color(it),
                        topLeft = Offset(c * cell, r * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
