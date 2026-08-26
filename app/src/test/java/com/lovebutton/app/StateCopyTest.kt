package com.lovebutton.app

import com.lovebutton.app.ui.coldOpenLine
import com.lovebutton.app.ui.guideLine
import com.lovebutton.app.widget.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateCopyTest {

    @Test
    fun `every state has a line`() {
        WidgetState.entries.forEach { state ->
            assertTrue("$state has no line", guideLine(state, "Wifey").isNotBlank())
        }
    }

    @Test
    fun `no line leaves a placeholder unsubstituted`() {
        // A stray {partner} would ship as literal braces on screen.
        WidgetState.entries.forEach { state ->
            listOf(guideLine(state, "Wifey")).forEach { line ->
                assertFalse("unsubstituted placeholder in: $line", line.contains("{"))
                assertFalse("unsubstituted placeholder in: $line", line.contains("}"))
            }
        }
        assertFalse(coldOpenLine("Wifey", "Miss you", 0L).contains("{"))
    }

    @Test
    fun `the lines are the playful ones the guide pins`() {
        assertEquals("click the button!", guideLine(WidgetState.IDLE, "Wifey"))
        assertEquals("on its way to Wifey 0o0", guideLine(WidgetState.SENDING, "Wifey"))
        assertEquals("traveling in the interwebs (• ε •)", guideLine(WidgetState.SENT, "Wifey"))
        assertEquals("it buzzed Wifey's phone :3", guideLine(WidgetState.DELIVERED, "Wifey"))
        assertEquals("Wifey looked at it (>^o^)>", guideLine(WidgetState.SEEN, "Wifey"))
        assertEquals("didn't get through （◞‸◟）", guideLine(WidgetState.FAILED, "Wifey"))
    }

    @Test
    fun `the cold open line names the message and its age`() {
        val line = coldOpenLine("Wifey", "Miss you", 2 * 60 * 60 * 1000L)
        assertTrue(line, line.contains("Wifey"))
        assertTrue(line, line.contains("Miss you"))
        assertTrue(line, line.contains("2h"))
    }

    @Test
    fun `ages read sensibly across the ranges`() {
        assertTrue(coldOpenLine("W", "m", 5_000L).contains("just now"))
        assertTrue(coldOpenLine("W", "m", 7 * 60_000L).contains("7m"))
        assertTrue(coldOpenLine("W", "m", 3 * 3_600_000L).contains("3h"))
        assertTrue(coldOpenLine("W", "m", 2 * 86_400_000L).contains("2d"))
    }
}
