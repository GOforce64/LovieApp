package com.lovebutton.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lovebutton.app.data.PENDING_WINDOW_MS
import com.lovebutton.app.data.PendingSends
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingSendsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a remembered send resolves to its widget`() = runTest {
        val pending = PendingSends(context)
        pending.remember("send-a", 42)

        assertEquals(42, pending.widgetFor("send-a"))
    }

    @Test
    fun `an unknown send resolves to nothing`() = runTest {
        assertNull(PendingSends(context).widgetFor("never-sent"))
    }

    @Test
    fun `a forgotten send resolves to nothing`() = runTest {
        val pending = PendingSends(context)
        pending.remember("send-b", 7)
        pending.forget("send-b")

        assertNull(pending.widgetFor("send-b"))
    }

    /**
     * The bug that kept a tile filled forever, pinned at its source.
     *
     * `SendWorker.settle` used to wait exactly PENDING_WINDOW_MS and then ask
     * `widgetFor` whether the entry was still live, treating null as "a receipt
     * already claimed this tile, leave it alone". But an entry written before
     * the request is always past the window by the time that wait ends, so the
     * answer was always null and the tile was never returned to idle.
     *
     * This test is what makes that non-obvious: the entry has not been forgotten
     * and nothing has gone wrong, yet the lookup still says null.
     */
    @Test
    fun `an entry is already expired the moment the pending window elapses`() = runTest {
        val pending = PendingSends(context)
        pending.remember("send-c", 13)

        val whenSettleUsedToLook = System.currentTimeMillis() + PENDING_WINDOW_MS + 1
        assertNull(pending.widgetFor("send-c", now = whenSettleUsedToLook))
    }

    @Test
    fun `an entry inside the window still resolves`() = runTest {
        val pending = PendingSends(context)
        pending.remember("send-d", 5)

        val halfway = System.currentTimeMillis() + PENDING_WINDOW_MS / 2
        assertEquals(5, pending.widgetFor("send-d", now = halfway))
    }

    @Test
    fun `remainingMs counts down from the send, not from now`() = runTest {
        val pending = PendingSends(context)
        pending.remember("send-g", 3)

        // Five seconds after the send, a `seen` can still land for fifteen more.
        val fiveSecondsIn = System.currentTimeMillis() + 5_000L
        val left = pending.remainingMs("send-g", now = fiveSecondsIn)

        // Allowing a little slack for the real clock inside remember().
        assertTrue("expected about 15s left, got $left", left in 14_000L..15_000L)
    }

    @Test
    fun `remainingMs is zero once the window has closed`() = runTest {
        val pending = PendingSends(context)
        pending.remember("send-h", 4)

        val past = System.currentTimeMillis() + PENDING_WINDOW_MS + 5_000L
        assertEquals(0L, pending.remainingMs("send-h", now = past))
    }

    @Test
    fun `remainingMs is zero for a send it never saw`() = runTest {
        assertEquals(0L, PendingSends(context).remainingMs("never-sent"))
    }

    @Test
    fun `forgetExpired leaves entries that are still inside the window`() = runTest {
        val pending = PendingSends(context)
        pending.remember("send-e", 9)

        pending.forgetExpired(now = System.currentTimeMillis())

        assertEquals(9, pending.widgetFor("send-e"))
    }

    @Test
    fun `forgetExpired removes entries past the window`() = runTest {
        val pending = PendingSends(context)
        pending.remember("send-f", 11)

        pending.forgetExpired(now = System.currentTimeMillis() + PENDING_WINDOW_MS + 1)

        // Gone from the store outright, not merely hidden by widgetFor's expiry:
        // a generous `now` would still surface it if the row were left behind.
        assertNull(pending.widgetFor("send-f", now = 0L))
    }
}
