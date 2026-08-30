package com.lovebutton.app

import com.lovebutton.app.data.SendSnapshot
import com.lovebutton.app.data.receivedWins
import com.lovebutton.app.ui.guideFace
import com.lovebutton.app.ui.guideWords
import com.lovebutton.app.ui.receivedLine
import com.lovebutton.app.widget.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which message owns the bubble.
 *
 * Both phones must land on the same answer, so the contest is decided by the
 * server's clock and nothing else. This phone's own `at` exists for the
 * twenty-second timeout and is deliberately never consulted here — comparing it
 * to a server timestamp would be two clocks pretending to be one.
 */
class SharedBubbleTest {

    private fun mine(serverAt: Long?, state: WidgetState = WidgetState.SENT) =
        SendSnapshot("s1", 1, state, at = 1_000L, fromMe = true, serverAt = serverAt)

    private fun hers(serverAt: Long) =
        SendSnapshot("s2", 2, WidgetState.SEEN, at = 1_000L, fromMe = false, serverAt = serverAt)

    @Test
    fun `an empty bubble takes whatever arrives`() {
        assertTrue(receivedWins(null, 5_000L, "z"))
    }

    @Test
    fun `a message newer than what is showing takes the bubble`() {
        assertTrue(receivedWins(mine(serverAt = 5_000L), 5_001L, "a"))
        assertTrue(receivedWins(hers(serverAt = 5_000L), 5_001L, "a"))
    }

    @Test
    fun `a message older than what is showing does not`() {
        // Pushes can arrive out of order, and a late one must not drag the
        // bubble backwards to a message that has already been superseded.
        assertFalse(receivedWins(mine(serverAt = 5_000L), 4_999L, "z"))
        assertFalse(receivedWins(hers(serverAt = 5_000L), 4_999L, "z"))
    }

    @Test
    fun `the very same push delivered twice changes nothing`() {
        // Same timestamp and same id, so neither half of the comparison passes
        // and the record it already produced is left alone.
        assertFalse(receivedWins(mine(serverAt = 5_000L), 5_000L, "s1"))
        assertFalse(receivedWins(hers(serverAt = 5_000L), 5_000L, "s2"))
    }

    @Test
    fun `two sends in the same second are still ordered, and both phones agree`() {
        // sent_at is epoch SECONDS, so a tie is routine rather than exotic. Left
        // as "strictly newer" each phone would keep its own message and the two
        // bubbles would disagree — the one outcome the shared bubble exists to
        // prevent. The id breaks it, arbitrarily but identically on both phones.
        assertTrue(receivedWins(mine(serverAt = 5_000L), 5_000L, "s9"))
        assertFalse(receivedWins(mine(serverAt = 5_000L), 5_000L, "s0"))

        // The property that matters: whichever phone asks, the same message wins.
        val a = SendSnapshot("aaa", 1, WidgetState.SENT, 1L, fromMe = true, serverAt = 5_000L)
        val b = SendSnapshot("bbb", 2, WidgetState.SENT, 1L, fromMe = true, serverAt = 5_000L)
        assertTrue("b must win on a's phone", receivedWins(a, 5_000L, b.sendId))
        assertFalse("b must also win on b's phone", receivedWins(b, 5_000L, a.sendId))
    }

    @Test
    fun `a send still in flight yields to anything that arrives`() {
        // Your own send has no server timestamp until its response lands, so
        // there is nothing to compare it against. The ruling is that the newest
        // message takes the bubble even mid-ladder, and the tile you tapped
        // still runs its own full ladder regardless.
        assertTrue(receivedWins(mine(serverAt = null, state = WidgetState.SENDING), 1L, "a"))
        assertTrue(receivedWins(mine(serverAt = null, state = WidgetState.SENT), 1L, "a"))
    }

    @Test
    fun `a message she sent names her, in every state`() {
        // The stored state of a received message is SEEN, but the copy must not
        // lean on that: it branches on who sent it, so a state that somehow
        // reached a received record could never produce "Wifey looked at it" on
        // Wifey's own phone.
        WidgetState.entries.forEach { state ->
            assertEquals("Wifey sent you this", guideWords(state, "Wifey", fromMe = false))
            assertTrue("$state has no face", guideFace(state, fromMe = false).isNotBlank())
        }
    }

    @Test
    fun `my own lines are untouched`() {
        WidgetState.entries.forEach { state ->
            assertEquals(guideWords(state, "Wifey"), guideWords(state, "Wifey", fromMe = true))
            assertEquals(guideFace(state), guideFace(state, fromMe = true))
        }
    }

    @Test
    fun `the line under a received message names the message, not the reader`() {
        // "Wifey saw your Miss you" is what the sending side says. On the side
        // that received it the only useful facts are what it was and how long
        // ago — telling her she saw it would be reporting her own action back
        // to her.
        val line = receivedLine("Miss you", 5 * 60_000L)
        assertTrue(line, line.contains("Miss you"))
        assertTrue(line, line.contains("5m"))
        assertFalse(line, line.contains("saw"))
    }
}
