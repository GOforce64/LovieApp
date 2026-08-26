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
}
