package com.lovebutton.app

import com.lovebutton.app.data.SendSnapshot
import com.lovebutton.app.data.receivedWins
import com.lovebutton.app.widget.WidgetState
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
        assertTrue(receivedWins(null, 5_000L))
    }

    @Test
    fun `a message newer than what is showing takes the bubble`() {
        assertTrue(receivedWins(mine(serverAt = 5_000L), 5_001L))
        assertTrue(receivedWins(hers(serverAt = 5_000L), 5_001L))
    }

    @Test
    fun `a message older than what is showing does not`() {
        // Pushes can arrive out of order, and a late one must not drag the
        // bubble backwards to a message that has already been superseded.
        assertFalse(receivedWins(mine(serverAt = 5_000L), 4_999L))
        assertFalse(receivedWins(hers(serverAt = 5_000L), 4_999L))
    }

    @Test
    fun `a message with the very same timestamp does not steal the bubble`() {
        // Strictly newer, so one push delivered twice is a no-op rather than a
        // rewrite of the record it already produced.
        assertFalse(receivedWins(mine(serverAt = 5_000L), 5_000L))
        assertFalse(receivedWins(hers(serverAt = 5_000L), 5_000L))
    }

    @Test
    fun `a send still in flight yields to anything that arrives`() {
        // Your own send has no server timestamp until its response lands, so
        // there is nothing to compare it against. The ruling is that the newest
        // message takes the bubble even mid-ladder, and the tile you tapped
        // still runs its own full ladder regardless.
        assertTrue(receivedWins(mine(serverAt = null, state = WidgetState.SENDING), 1L))
        assertTrue(receivedWins(mine(serverAt = null, state = WidgetState.SENT), 1L))
    }
}
