package com.lovebutton.app

import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.advancesTo
import com.lovebutton.app.widget.fromName
import com.lovebutton.app.widget.holdMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateTest {

    @Test
    fun `unknown or missing stored state reads as idle`() {
        // Glance's store survives reinstalls and version changes, so a value written
        // by an older build can come back. Anything unrecognised must land on IDLE
        // rather than throw inside a widget update, which the host would surface as
        // a blank tile with no way to recover.
        assertEquals(WidgetState.IDLE, fromName(null))
        assertEquals(WidgetState.IDLE, fromName(""))
        assertEquals(WidgetState.IDLE, fromName("ARCHIVED"))
        assertEquals(WidgetState.IDLE, fromName("nonsense"))
    }

    @Test
    fun `known state names round-trip`() {
        WidgetState.entries.forEach { state ->
            assertEquals(state, fromName(state.name))
        }
    }

    @Test
    fun `only the terminal states are held then cleared`() {
        // IDLE is the resting state so it is never "held". SENDING lasts as long as
        // the request does, which is not a fixed duration and must not be timed out
        // by the UI — the worker is the only thing that knows when it ended. SENT
        // and DELIVERED both wait out the pending window because a receipt may
        // still be coming; only SEEN and FAILED are genuinely terminal.
        assertNull(WidgetState.IDLE.holdMillis)
        assertNull(WidgetState.SENDING.holdMillis)
        assertEquals(20_000L, WidgetState.SENT.holdMillis)
        assertEquals(3_000L, WidgetState.FAILED.holdMillis)
        // DELIVERED holds the same window as SENT: a `seen` can still arrive, and
        // a shorter hold blinked the tile off and back on when she unlocked.
        assertEquals(20_000L, WidgetState.DELIVERED.holdMillis)
        // SEEN is terminal, so it is the only receipt state that is genuinely done.
        assertEquals(4_000L, WidgetState.SEEN.holdMillis)
    }

    /**
     * The bug this rank exists for, pinned at its source.
     *
     * The recipient reports `delivered` and `seen` as two independent WorkManager
     * jobs that run concurrently, so they reach the sender in whatever order the
     * network settles them — observed on hardware as `seen` at t+0ms and
     * `delivered` at t+54ms for the same send. Written in arrival order, the late
     * `delivered` undid the `seen` and the focal area sat on "it buzzed her
     * phone" forever. The ladder only ever moves forward.
     */
    @Test
    fun `a late delivered cannot undo a seen`() {
        assertFalse(WidgetState.SEEN.advancesTo(WidgetState.DELIVERED))
        assertFalse(WidgetState.SEEN.advancesTo(WidgetState.SENT))
        assertFalse(WidgetState.DELIVERED.advancesTo(WidgetState.SENT))
    }

    @Test
    fun `the ladder still advances in its own order`() {
        assertTrue(WidgetState.IDLE.advancesTo(WidgetState.SENDING))
        assertTrue(WidgetState.SENDING.advancesTo(WidgetState.SENT))
        assertTrue(WidgetState.SENT.advancesTo(WidgetState.DELIVERED))
        assertTrue(WidgetState.DELIVERED.advancesTo(WidgetState.SEEN))
    }

    @Test
    fun `a send can still be marked failed`() {
        // The transition that actually happens: the request threw while the tile
        // was mid-flight. Blocking this would leave a send stuck at "sending…".
        assertTrue(WidgetState.SENDING.advancesTo(WidgetState.FAILED))
    }

    @Test
    fun `a receipt outranks a local failure`() {
        // A receipt can only exist if the send reached the server, so it is better
        // evidence than this phone's own timed-out request.
        assertTrue(WidgetState.FAILED.advancesTo(WidgetState.DELIVERED))
        assertTrue(WidgetState.FAILED.advancesTo(WidgetState.SEEN))
        assertFalse(WidgetState.SEEN.advancesTo(WidgetState.FAILED))
    }

    @Test
    fun `a repeated state is not an advance`() {
        // Receipts are retried and can be delivered twice. Rewriting the same
        // state is harmless but pointless, and saying so keeps the rule total.
        WidgetState.entries.forEach { state ->
            assertFalse("$state advanced to itself", state.advancesTo(state))
        }
    }
}
