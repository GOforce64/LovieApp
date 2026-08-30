package com.lovebutton.app

import com.lovebutton.app.ui.coldOpenLine
import com.lovebutton.app.ui.guideFace
import com.lovebutton.app.ui.guideLine
import com.lovebutton.app.ui.guideWords
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
    fun `every state has a face to sway`() {
        // The whole point of the sway is that the screen never looks frozen. A
        // state with no face is a state where the screen sits perfectly still —
        // and IDLE, the state it rests in longest, was exactly that state.
        WidgetState.entries.forEach { state ->
            assertTrue("$state has no face", guideFace(state).isNotBlank())
        }
    }

    @Test
    fun `the line is its words and its face, in that order`() {
        // The guide renders guideLine; the focal area renders the halves and
        // animates one of them. If these two ever disagree the app grows a
        // second vocabulary for the same six states, which is the thing
        // StateCopy exists to prevent.
        WidgetState.entries.forEach { state ->
            assertEquals(
                guideWords(state, "Wifey") + " " + guideFace(state),
                guideLine(state, "Wifey"),
            )
        }
    }

    @Test
    fun `the words carry no face of their own`() {
        WidgetState.entries.forEach { state ->
            val words = guideWords(state, "Wifey")
            assertFalse("$state doubles up its face: $words", words.contains(guideFace(state)))
        }
    }

    @Test
    fun `the lines are the playful ones the guide pins`() {
        assertEquals("click the button! (・ω・)", guideLine(WidgetState.IDLE, "Wifey"))
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
