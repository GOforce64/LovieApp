package com.lovebutton.app

import com.lovebutton.app.ui.borderRingOrder
import com.lovebutton.app.ui.gildBoxFor
import com.lovebutton.app.ui.gildOrder
import com.lovebutton.app.ui.fillRowsVisible
import com.lovebutton.app.ui.isBorderCell
import com.lovebutton.app.ui.rippleReached
import com.lovebutton.app.widget.PixelGrids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelLadderTest {

    private val block = listOf(
        ".....",
        ".XXX.",
        ".XXX.",
        ".XXX.",
        ".....",
    )

    @Test
    fun `the fill starts empty and ends full`() {
        assertEquals(0, fillRowsVisible(block, 0f))
        assertEquals(block.size, fillRowsVisible(block, 1f))
    }

    @Test
    fun `the fill rises monotonically`() {
        // It must never go backwards mid-send: a fill that dips reads as the
        // send failing and recovering, which is not what happened.
        var previous = -1
        var p = 0f
        while (p <= 1f) {
            val rows = fillRowsVisible(block, p)
            assertTrue("fill went backwards at $p", rows >= previous)
            previous = rows
            p += 0.05f
        }
    }

    @Test
    fun `progress outside zero to one is clamped`() {
        assertEquals(0, fillRowsVisible(block, -3f))
        assertEquals(block.size, fillRowsVisible(block, 9f))
    }

    @Test
    fun `the ripple starts at the centre and finishes everywhere`() {
        assertTrue("the centre must go first", rippleReached(block, 2, 2, 0.05f))
        for (r in block.indices) for (c in block[r].indices) {
            assertTrue("everything must be reached by the end", rippleReached(block, r, c, 1f))
        }
    }

    @Test
    fun `the ripple has not reached the far corner immediately`() {
        assertFalse(rippleReached(block, 0, 0, 0.05f))
    }

    @Test
    fun `the gold ring visits every border cell exactly once`() {
        val ring = borderRingOrder(block)
        val expected = mutableSetOf<Pair<Int, Int>>()
        for (r in block.indices) for (c in block[r].indices) {
            val ch = block[r][c]
            if ((ch == 'X' || ch == 's') && com.lovebutton.app.ui.isBorderCell(block, r, c)) {
                expected.add(r to c)
            }
        }
        assertEquals("the ring must not repeat a cell", ring.size, ring.toSet().size)
        assertEquals(expected, ring.toSet())
    }

    @Test
    fun `the gold ring is a loop, each step adjacent to the last`() {
        // A ring that jumps across the shape reads as flickering, not as light
        // travelling around an outline.
        val ring = borderRingOrder(block)
        ring.zipWithNext().forEach { (a, b) ->
            val dr = kotlin.math.abs(a.first - b.first)
            val dc = kotlin.math.abs(a.second - b.second)
            assertTrue("jump from $a to $b", dr <= 1 && dc <= 1)
        }
    }

    /**
     * The same guarantee on the art that actually ships.
     *
     * `block` is one tidy ring, so it passes under walks that strand themselves
     * on the real icons. The heart and the bubble are single outlines and must
     * gild without a jump; the paw's toes and the call icon's parts are separate
     * blobs, so there one jump per extra piece is the shape, not a bug.
     */
    @Test
    fun `every shipped icon gilds without jumping inside a piece`() {
        PixelGrids.GRIDS.forEach { (name, grid) ->
            val ring = borderRingOrder(grid)
            val border = buildSet {
                grid.forEachIndexed { r, row ->
                    row.forEachIndexed { c, ch ->
                        // Matches borderRingOrder: only plain solid cells gild,
                        // because shine and shade keep their own colours in seen.
                        if (ch == 'X' && isBorderCell(grid, r, c)) add(r to c)
                    }
                }
            }
            assertEquals("$name lost or repeated a cell", border.size, ring.size)
            assertEquals("$name did not cover its outline", border, ring.toSet())

            val jumps = ring.zipWithNext().count { (a, b) ->
                kotlin.math.abs(a.first - b.first) > 1 || kotlin.math.abs(a.second - b.second) > 1
            }
            val pieces = disjointPieces(border)
            assertEquals("$name jumped inside one piece of its outline", pieces - 1, jumps)
        }
    }

    @Test
    fun `an icon with a gild box lights that box and nothing outside it`() {
        val grid = PixelGrids.GRIDS.getValue("call")
        val box = gildBoxFor("call")!!
        val order = gildOrder("call", grid)

        assertTrue("nothing to gild", order.isNotEmpty())
        assertEquals("the gild repeated a cell", order.size, order.toSet().size)

        // Only shine and shade cells, and only ones inside the box: the top face
        // and the side face are the same two tones, so a gild that escaped the
        // box would turn the phone's whole 3D shading gold.
        order.forEach { (r, c) ->
            assertTrue("gilded outside the box", r in box.rows && c in box.cols)
            assertTrue("gilded a plain cell", grid[r][c] == 's' || grid[r][c] == 'd')
        }

        val expected = box.rows.flatMap { r ->
            box.cols.filter { c -> grid[r][c] == 's' || grid[r][c] == 'd' }.map { c -> r to c }
        }.toSet()
        assertEquals("the gild missed part of its box", expected, order.toSet())
    }

    @Test
    fun `an icon without a gild box still runs its outline`() {
        val grid = PixelGrids.GRIDS.getValue("heart")
        assertEquals(borderRingOrder(grid), gildOrder("heart", grid))
    }

    /** How many touching groups the border falls into, counted independently. */
    private fun disjointPieces(border: Set<Pair<Int, Int>>): Int {
        val seen = mutableSetOf<Pair<Int, Int>>()
        var pieces = 0
        border.forEach { cell ->
            if (!seen.add(cell)) return@forEach
            pieces++
            val stack = ArrayDeque(listOf(cell))
            while (stack.isNotEmpty()) {
                val (r, c) = stack.removeLast()
                for (dr in -1..1) for (dc in -1..1) {
                    val n = (r + dr) to (c + dc)
                    if (n != (r to c) && n in border && seen.add(n)) stack.addLast(n)
                }
            }
        }
        return pieces
    }
}
