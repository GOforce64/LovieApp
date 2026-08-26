package com.lovebutton.app

import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.fromName
import com.lovebutton.app.widget.holdMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
