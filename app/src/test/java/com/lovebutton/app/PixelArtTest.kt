package com.lovebutton.app

import com.lovebutton.app.ui.cellColor
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
}
