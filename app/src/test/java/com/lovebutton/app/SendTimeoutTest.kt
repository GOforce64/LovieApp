package com.lovebutton.app

import com.lovebutton.app.data.PENDING_WINDOW_MS
import com.lovebutton.app.data.SendSnapshot
import com.lovebutton.app.data.displayState
import com.lovebutton.app.data.windowClosed
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.isAwaitingOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 20-second timeout, from both ends.
 *
 * A send can hang in two ways that used to look identical to the screen: this
 * phone never got online to make the request, or her phone never came online to
 * acknowledge it. Both left the focal area saying "traveling in the interwebs"
 * forever, including on every cold open afterwards.
 */
class SendTimeoutTest {

    private fun snap(state: WidgetState, at: Long) =
        SendSnapshot(sendId = "s1", msgId = 1, state = state, at = at)

    @Test
    fun `the window is closed at exactly the pending window, not a tick later`() {
        assertFalse(windowClosed(at = 0L, now = PENDING_WINDOW_MS - 1))
        assertTrue(windowClosed(at = 0L, now = PENDING_WINDOW_MS))
        assertTrue(windowClosed(at = 0L, now = PENDING_WINDOW_MS + 1))
    }

    @Test
    fun `a clock that went backwards does not close the window`() {
        // NTP correction, or the user changing the time. Treating a negative age
        // as "expired" would grey out a send that had only just been tapped.
        assertFalse(windowClosed(at = 10_000L, now = 0L))
    }

    @Test
    fun `the two waiting states are the ones that can time out`() {
        assertTrue(WidgetState.SENDING.isAwaitingOutcome)
        assertTrue(WidgetState.SENT.isAwaitingOutcome)
        assertFalse(WidgetState.IDLE.isAwaitingOutcome)
        assertFalse(WidgetState.FAILED.isAwaitingOutcome)
        assertFalse(WidgetState.DELIVERED.isAwaitingOutcome)
        assertFalse(WidgetState.SEEN.isAwaitingOutcome)
    }

    @Test
    fun `a send still inside the window shows what it stored`() {
        val now = PENDING_WINDOW_MS - 1
        assertEquals(WidgetState.SENDING, snap(WidgetState.SENDING, 0L).displayState(now))
        assertEquals(WidgetState.SENT, snap(WidgetState.SENT, 0L).displayState(now))
    }

    @Test
    fun `her phone never acknowledged, so it goes grey`() {
        // The reported bug: the server accepted it, no receipt ever came, and the
        // focal sat on "traveling in the interwebs" indefinitely.
        assertEquals(WidgetState.FAILED, snap(WidgetState.SENT, 0L).displayState(PENDING_WINDOW_MS))
    }

    @Test
    fun `this phone never got online, so it goes grey too`() {
        assertEquals(
            WidgetState.FAILED,
            snap(WidgetState.SENDING, 0L).displayState(PENDING_WINDOW_MS),
        )
    }

    @Test
    fun `an outcome that actually arrived is never rewritten`() {
        // A receipt is evidence. However old it is, it happened, and the app is
        // the half of this that remembers rather than forgets (spec 6.1).
        val ancient = 86_400_000L
        listOf(WidgetState.DELIVERED, WidgetState.SEEN, WidgetState.FAILED).forEach { state ->
            assertEquals(state, snap(state, 0L).displayState(ancient))
        }
    }

    @Test
    fun `idle is left alone however old it is`() {
        assertEquals(WidgetState.IDLE, snap(WidgetState.IDLE, 0L).displayState(86_400_000L))
    }

    @Test
    fun `grey stays grey, however long the screen is left open`() {
        // The ruling: once it has timed out it does not recover on its own. The
        // next tap is what starts a new story, and that replaces the record.
        val stale = snap(WidgetState.SENT, 0L)
        listOf(PENDING_WINDOW_MS, 60_000L, 3_600_000L, 86_400_000L).forEach { now ->
            assertEquals(WidgetState.FAILED, stale.displayState(now))
        }
    }
}
