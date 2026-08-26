package com.lovebutton.app

import com.lovebutton.app.R
import com.lovebutton.app.widget.PixelPalette
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.gridNameFor
import com.lovebutton.app.widget.iconFor
import com.lovebutton.app.widget.tintColorFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetArtTest {

    @Test
    fun `each message maps to its own grid`() {
        assertEquals("heart", gridNameFor(1))
        assertEquals("bubble", gridNameFor(2))
        assertEquals("paw", gridNameFor(3))
        assertEquals("call", gridNameFor(4))
    }

    @Test
    fun `an unknown message falls back to the heart`() {
        // Glance state outlives reinstalls, so an id from another build can
        // arrive. Falling back beats throwing inside a widget update.
        assertEquals("heart", gridNameFor(99))
    }

    @Test
    fun `the ladder maps to five distinct pictures`() {
        val icons = listOf(
            WidgetState.IDLE, WidgetState.SENDING, WidgetState.SENT,
            WidgetState.DELIVERED, WidgetState.SEEN,
        ).map { iconFor(1, it) }

        assertEquals("the five ladder states must not share art", 5, icons.toSet().size)
    }

    @Test
    fun `failed reuses the outline`() {
        // It is not a point on the fill scale — it is the sequence abandoned —
        // so it borrows idle's picture and is told apart by the tint.
        assertEquals(iconFor(1, WidgetState.IDLE), iconFor(1, WidgetState.FAILED))
    }

    @Test
    fun `only failed is tinted`() {
        assertNotNull(tintColorFor(WidgetState.FAILED))
        listOf(
            WidgetState.IDLE, WidgetState.SENDING, WidgetState.SENT,
            WidgetState.DELIVERED, WidgetState.SEEN,
        ).forEach { assertNull("$it must not be tinted", tintColorFor(it)) }
    }

    @Test
    fun `the palette carries the three state colours the spec pins`() {
        assertEquals(0xFFD81B60.toInt(), PixelPalette.Sent)
        assertEquals(0xFFFF6FA5.toInt(), PixelPalette.Delivered)
        assertEquals(0xFFFFC64B.toInt(), PixelPalette.Gold)
    }

    @Test
    fun `the heart drawables are the ones the guide will show`() {
        assertEquals(R.drawable.ic_heart_outline, iconFor(1, WidgetState.IDLE))
        assertEquals(R.drawable.ic_heart_half, iconFor(1, WidgetState.SENDING))
        assertEquals(R.drawable.ic_heart_filled, iconFor(1, WidgetState.SENT))
        assertEquals(R.drawable.ic_heart_delivered, iconFor(1, WidgetState.DELIVERED))
        assertEquals(R.drawable.ic_heart_seen, iconFor(1, WidgetState.SEEN))
    }
}
