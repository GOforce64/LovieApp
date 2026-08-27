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
import kotlin.math.roundToInt

private fun isSolid(ch: Char) = ch == 'X' || ch == 's' || ch == 'd'

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

/** Enough backtracking for the shipped grids; a walk needs a few thousand at most. */
private const val WALK_BUDGET = 300_000

/**
 * How often the face blinks, and how long a blink takes.
 *
 * Only while the face is lit: holes are not drawn on the resting outline, so
 * there would be nothing to close.
 */
private const val BLINK_PERIOD_MS = 3400f
private const val BLINK_CLOSE_MS = 260f

/**
 * How many cells to eat off each end of an eye, 0 to 2 and back over one blink.
 *
 * Public for the same reason [fillRowsVisible] and [rippleReached] are: the
 * shape of the motion is worth asserting, and none of it needs a Canvas.
 */
fun blinkAmount(phase: Float): Int {
    val t = phase * BLINK_PERIOD_MS
    if (t >= BLINK_CLOSE_MS) return 0
    val b = t / BLINK_CLOSE_MS
    val triangle = if (b < 0.5f) b * 2f else (1f - b) * 2f
    return (triangle * 2f).roundToInt()
}

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
            // Plain solid only. A shine cell keeps its highlight in the seen
            // state and a shade cell keeps its shadow, so neither ever turns
            // gold — - carrying them in the ring would spend a third of the
            // phone's gild on cells that visibly do nothing, and the light
            // would appear to stall as it crossed the shaded side.
            if (ch == 'X' && isBorderCell(grid, r, c)) cells.add(r to c)
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
 * The cells that turn gold on `seen`, in the order they light.
 *
 * Most icons run the gold around their outline. An icon with a gild box lights
 * that box instead, row by row — for the phone that is the screen coming on and
 * then the keypad going down, which reads as the handset waking rather than as a
 * frame being drawn.
 */
fun gildOrder(gridName: String, grid: List<String>): List<Cell> {
    val box = gildBoxFor(gridName) ?: return borderRingOrder(grid)
    val cells = mutableListOf<Cell>()
    for (r in box.rows) {
        if (r !in grid.indices) continue
        for (c in box.cols) {
            if (c !in grid[r].indices) continue
            val ch = grid[r][c]
            if (ch == 's' || ch == 'd') cells.add(r to c)
        }
    }
    return cells
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

    val blinkPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BLINK_PERIOD_MS.toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "blink",
    )

    // With animations off, every state renders as its plain finished picture.
    val progress = if (!animationsOn) 1f else when (state) {
        WidgetState.SENDING -> loop
        else -> shot
    }
    val ring = remember(grid) { gildOrder(gridNameFor(msgId), grid) }

    val lit = state == WidgetState.SENT ||
        state == WidgetState.DELIVERED ||
        state == WidgetState.SEEN
    val blinkK = if (!animationsOn || !lit) 0 else blinkAmount(blinkPhase)
    // Closing the eyes only changes hole cells, never the outline, so the ring
    // above stays valid and does not need recomputing per frame.
    val drawn = shrinkEyes(grid, eyesFor(gridNameFor(msgId)), blinkK)

    Canvas(modifier = modifier) {
        val cell = minOf(size.width / drawn[0].length, size.height / drawn.size)
        val goldCount = (ring.size * progress).toInt()
        val gilded = if (state == WidgetState.SEEN) ring.take(goldCount).toHashSet() else emptySet()

        drawn.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                val argb: Int? = when {
                    !isSolid(ch) -> null

                    state == WidgetState.SENDING -> {
                        val filledFrom = drawn.size - fillRowsVisible(drawn, progress)
                        if (r >= filledFrom) cellColor(drawn, r, c, WidgetState.SENT)
                        else cellColor(drawn, r, c, WidgetState.IDLE)
                    }

                    state == WidgetState.DELIVERED ->
                        if (rippleReached(drawn, r, c, progress)) cellColor(drawn, r, c, WidgetState.DELIVERED)
                        else cellColor(drawn, r, c, WidgetState.SENT)

                    state == WidgetState.SEEN -> when {
                        // The gild wins over the tone now, which is what lets an
                        // icon light its own shine and shade cells. For icons
                        // that gild their outline nothing changes: only plain
                        // solid cells ever reach the ring in that case.
                        (r to c) in gilded -> PixelPalette.Gold
                        ch == 's' -> PixelPalette.Shine
                        ch == 'd' -> shadeFor(WidgetState.SEEN)
                        else -> cellColor(drawn, r, c, WidgetState.DELIVERED)
                    }

                    else -> cellColor(drawn, r, c, state)
                }

                argb?.let {
                    val (topLeft, size) = cellRect(r, c, cell)
                    drawRect(color = Color(it), topLeft = topLeft, size = size)
                }
            }
        }
    }
}
