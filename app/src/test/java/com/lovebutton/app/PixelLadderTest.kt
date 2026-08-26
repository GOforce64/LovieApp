package com.lovebutton.app

import com.lovebutton.app.ui.borderRingOrder
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
                        if ((ch == 'X' || ch == 's') && isBorderCell(grid, r, c)) add(r to c)
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
