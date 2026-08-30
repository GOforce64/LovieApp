package com.lovebutton.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.lovebutton.app.data.CurrentSend
import com.lovebutton.app.data.currentSendStore
import com.lovebutton.app.widget.WidgetState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CurrentSendTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * DataStore hands out one instance per class-loader, and this store holds
     * exactly one record, so without this every test after the first inherits
     * the previous one's send.
     */
    @Before
    fun emptyTheStore() = runBlocking {
        context.currentSendStore.edit { it.clear() }
        Unit
    }

    @Test
    fun `nothing is remembered before the first send`() = runTest {
        assertNull(CurrentSend(context).current())
    }

    @Test
    fun `starting a send records it as sending`() = runTest {
        val store = CurrentSend(context)
        store.start("send-a", msgId = 3, now = 1_000L)

        val snap = store.current()
        assertEquals("send-a", snap?.sendId)
        assertEquals(3, snap?.msgId)
        assertEquals(WidgetState.SENDING, snap?.state)
        assertEquals(1_000L, snap?.at)
    }

    @Test
    fun `updating the current send advances its state`() = runTest {
        val store = CurrentSend(context)
        store.start("send-b", msgId = 1)
        store.update("send-b", WidgetState.DELIVERED)

        assertEquals(WidgetState.DELIVERED, store.current()?.state)
    }

    /**
     * The invariant that stops a late receipt hijacking a newer send.
     *
     * Receipts arrive by push and can land after the user has already sent
     * something else. Without the id check, an old "seen" would repaint the
     * focal area for a message that is not on screen.
     */
    @Test
    fun `an update for a different send is ignored`() = runTest {
        val store = CurrentSend(context)
        store.start("newer", msgId = 2)
        store.update("older", WidgetState.SEEN)

        val snap = store.current()
        assertEquals("newer", snap?.sendId)
        assertEquals(WidgetState.SENDING, snap?.state)
    }

    @Test
    fun `an update before any send is ignored rather than throwing`() = runTest {
        val store = CurrentSend(context)
        store.update("ghost", WidgetState.SEEN)

        assertNull(store.current())
    }

    @Test
    fun `starting a new send replaces the previous one entirely`() = runTest {
        val store = CurrentSend(context)
        store.start("first", msgId = 1)
        store.update("first", WidgetState.SEEN)
        store.start("second", msgId = 4, now = 55L)

        val snap = store.current()
        assertEquals("second", snap?.sendId)
        assertEquals(4, snap?.msgId)
        assertEquals(WidgetState.SENDING, snap?.state)
        assertEquals(55L, snap?.at)
    }

    /**
     * The race observed on hardware, as a test.
     *
     * The recipient reports `delivered` and `seen` as two concurrent jobs, so the
     * sender can receive them in either order — measured as `seen` first and
     * `delivered` 54ms behind it. Without this the later `delivered` overwrote
     * the `seen` and the focal area never reached gold again for the rest of the
     * session.
     */
    @Test
    fun `a delivered arriving after a seen does not undo it`() = runTest {
        val store = CurrentSend(context)
        store.start("raced", msgId = 1)
        store.update("raced", WidgetState.SEEN)
        store.update("raced", WidgetState.DELIVERED)

        assertEquals(WidgetState.SEEN, store.current()?.state)
    }

    @Test
    fun `a slow send response does not undo a receipt that beat it`() = runTest {
        // SendWorker writes SENT when its own POST returns, which can be after the
        // receipt for that same send has already arrived by push.
        val store = CurrentSend(context)
        store.start("beaten", msgId = 1)
        store.update("beaten", WidgetState.SEEN)
        store.update("beaten", WidgetState.SENT)

        assertEquals(WidgetState.SEEN, store.current()?.state)
    }

    @Test
    fun `the ladder still advances the whole way`() = runTest {
        val store = CurrentSend(context)
        store.start("orderly", msgId = 1)
        listOf(WidgetState.SENT, WidgetState.DELIVERED, WidgetState.SEEN).forEach {
            store.update("orderly", it)
        }

        assertEquals(WidgetState.SEEN, store.current()?.state)
    }

    @Test
    fun `a failure is still recorded`() = runTest {
        val store = CurrentSend(context)
        store.start("doomed", msgId = 1)
        store.update("doomed", WidgetState.FAILED)

        assertEquals(WidgetState.FAILED, store.current()?.state)
    }

    @Test
    fun `a new send starts over even after a seen`() = runTest {
        // start() is the reset. Monotonicity governs one send's ladder, not the
        // store, or the second message of the evening could never leave gold.
        val store = CurrentSend(context)
        store.start("first", msgId = 1)
        store.update("first", WidgetState.SEEN)
        store.start("second", msgId = 2)

        val snap = store.current()
        assertEquals("second", snap?.sendId)
        assertEquals(WidgetState.SENDING, snap?.state)
    }

    @Test
    fun `a send reclaims the bubble from an older message that jumped it`() = runTest {
        val store = CurrentSend(context)
        store.start("mine", 1, now = 500L)
        // Hers arrives while ours is still in flight, so it wins by default —
        // there is no server timestamp on ours yet to compare it against.
        store.receive("hers", 3, serverAt = 5_000L)
        assertEquals("hers", store.current()?.sendId)

        // Ours turns out to be the newer of the two. Both phones must end on it.
        store.markSentAt("mine", msgId = 1, tappedAt = 500L, serverAt = 6_000L)

        val snap = store.current()
        assertEquals("mine", snap?.sendId)
        assertEquals(1, snap?.msgId)
        assertEquals(true, snap?.fromMe)
        assertEquals(6_000L, snap?.serverAt)
        // The tap's own clock, not the reclaim's, so the timeout window is not
        // silently restarted by a slow response.
        assertEquals(500L, snap?.at)
    }

    @Test
    fun `a send does not reclaim from a message that really is newer`() = runTest {
        val store = CurrentSend(context)
        store.start("mine", 1, now = 500L)
        store.receive("hers", 3, serverAt = 9_000L)
        store.markSentAt("mine", msgId = 1, tappedAt = 500L, serverAt = 6_000L)

        // The agreed rule: the newest message holds the bubble, even mid-ladder.
        assertEquals("hers", store.current()?.sendId)
    }

    @Test
    fun `a newer send of our own is never undone by an older one finishing late`() = runTest {
        val store = CurrentSend(context)
        store.start("first", 1, now = 500L)
        store.start("second", 2, now = 600L)

        // The first send's response arrives after the second replaced it. It must
        // not resurrect itself: that record was retired by a deliberate tap.
        store.markSentAt("first", msgId = 1, tappedAt = 500L, serverAt = 9_999L)

        assertEquals("second", store.current()?.sendId)
    }
}
