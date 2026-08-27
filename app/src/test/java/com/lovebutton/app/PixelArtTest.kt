package com.lovebutton.app

import com.lovebutton.app.ui.cellColor
import com.lovebutton.app.ui.cellRect
import com.lovebutton.app.ui.shadeFor
import com.lovebutton.app.ui.isBorderCell
import com.lovebutton.app.widget.PixelPalette
import com.lovebutton.app.widget.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelArtTest {

    // A 5x5 block with a solid interior, small enough to reason about by hand.
    private val block = listOf(
        ".....",
        ".XXX.",
        ".XXX.",
        ".XXX.",
        ".....",
    )

    @Test
    fun `an empty cell draws nothing in every state`() {
        WidgetState.entries.forEach { state ->
            assertNull("$state painted an empty cell", cellColor(block, 0, 0, state))
        }
    }

    @Test
    fun `a solid cell touching empty space is border`() {
        assertTrue(isBorderCell(block, 1, 1))
        assertTrue(isBorderCell(block, 1, 2))
    }

    @Test
    fun `a solid cell surrounded by solid cells is interior`() {
        assertFalse(isBorderCell(block, 2, 2))
    }

    @Test
    fun `a solid cell on the grid edge is border`() {
        val full = listOf("XX", "XX")
        assertTrue(isBorderCell(full, 0, 0))
        assertTrue(isBorderCell(full, 1, 1))
    }

    @Test
    fun `idle draws only the outline`() {
        assertEquals(PixelPalette.Idle, cellColor(block, 1, 1, WidgetState.IDLE))
        assertNull("idle must leave the interior empty", cellColor(block, 2, 2, WidgetState.IDLE))
    }

    @Test
    fun `failed draws the outline in grey`() {
        assertEquals(PixelPalette.Failed, cellColor(block, 1, 1, WidgetState.FAILED))
        assertNull(cellColor(block, 2, 2, WidgetState.FAILED))
    }

    @Test
    fun `sent fills crimson inside a rose border`() {
        assertEquals(PixelPalette.Sent, cellColor(block, 2, 2, WidgetState.SENT))
        assertEquals(PixelPalette.Border, cellColor(block, 1, 1, WidgetState.SENT))
    }

    @Test
    fun `delivered fills pink inside a rose border`() {
        assertEquals(PixelPalette.Delivered, cellColor(block, 2, 2, WidgetState.DELIVERED))
        assertEquals(PixelPalette.Border, cellColor(block, 1, 1, WidgetState.DELIVERED))
    }

    @Test
    fun `seen keeps the pink fill and gilds the border`() {
        // Seen is delivered plus gold. If the fill also changed, the two states
        // would differ in two ways and the guide would have to explain both.
        assertEquals(PixelPalette.Delivered, cellColor(block, 2, 2, WidgetState.SEEN))
        assertEquals(PixelPalette.Gold, cellColor(block, 1, 1, WidgetState.SEEN))
    }

    @Test
    fun `a shine cell is painted in the shine colour whatever the state`() {
        val shiny = listOf(".....", ".XsX.", ".XXX.", ".XXX.", ".....")
        listOf(WidgetState.SENT, WidgetState.DELIVERED, WidgetState.SEEN).forEach {
            assertEquals("$it lost the shine", PixelPalette.Shine, cellColor(shiny, 1, 2, it))
        }
    }

    @Test
    fun `a hole draws nothing`() {
        // 'o' is cut out of the filled variant — it is how the bubble gets a face.
        val holed = listOf(".....", ".XXX.", ".XoX.", ".XXX.", ".....")
        assertNull(cellColor(holed, 2, 2, WidgetState.SENT))
    }

    // ---- the shade tone ----

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF
    private fun luminance(argb: Int) = 0.299 * red(argb) + 0.587 * green(argb) + 0.114 * blue(argb)

    @Test
    fun `the shade is darker than the fill in every lit state`() {
        // The whole point of the tone: a shaded face has to stay shaded all the
        // way up the ladder. Derived from each state's own fill, it does.
        mapOf(
            WidgetState.SENT to PixelPalette.Sent,
            WidgetState.DELIVERED to PixelPalette.Delivered,
            WidgetState.SEEN to PixelPalette.Delivered,
        ).forEach { (state, fill) ->
            val shade = shadeFor(state)
            assertTrue(
                "$state: shade is not darker than its fill",
                luminance(shade) < luminance(fill),
            )
        }
    }

    @Test
    fun `pinning the shade to the border colour would invert it`() {
        // Documents why shadeFor derives rather than reusing PixelPalette.Border:
        // the border is LIGHTER than the fill while a send is in flight and
        // darker once it has landed, so a pinned shade flips polarity halfway up.
        assertTrue(
            "border is not lighter than the sent fill — the inversion is gone",
            luminance(PixelPalette.Border) > luminance(PixelPalette.Sent),
        )
        assertTrue(
            "border is not darker than the delivered fill",
            luminance(PixelPalette.Border) < luminance(PixelPalette.Delivered),
        )
    }

    @Test
    fun `the shade keeps the alpha channel opaque`() {
        WidgetState.entries.forEach { state ->
            assertEquals(
                "$state lost its alpha",
                0xFF,
                (shadeFor(state) shr 24) and 0xFF,
            )
        }
    }

    // ---- cell snapping ----

    @Test
    fun `neighbouring cells share an edge exactly`() {
        // 16 cells across 100 pixels is 6.25 each: the fractional case that made
        // the white card show through as a grid of pale seams.
        val cell = 100f / 16f
        for (i in 0 until 15) {
            val (left, leftSize) = cellRect(0, i, cell)
            val (right, _) = cellRect(0, i + 1, cell)
            assertEquals("column $i left a gap or overlapped", right.x, left.x + leftSize.width, 0f)

            val (top, topSize) = cellRect(i, 0, cell)
            val (below, _) = cellRect(i + 1, 0, cell)
            assertEquals("row $i left a gap or overlapped", below.y, top.y + topSize.height, 0f)
        }
    }

    @Test
    fun `every cell lands on whole pixels`() {
        val cell = 132f / 22f * 1.37f   // deliberately not a round number
        (0 until 22).forEach { i ->
            val (offset, size) = cellRect(i, i, cell)
            assertEquals(offset.x, Math.round(offset.x).toFloat(), 0f)
            assertEquals(offset.y, Math.round(offset.y).toFloat(), 0f)
            assertTrue("a cell collapsed to nothing", size.width > 0f && size.height > 0f)
        }
    }
}
