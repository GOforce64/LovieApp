package com.lovebutton.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lovebutton.app.data.UnseenSends
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UnseenSendsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a remembered send waits to be drained`() = runTest {
        val unseen = UnseenSends(context)
        unseen.remember("send-a")

        assertEquals(listOf("send-a"), unseen.peek())
    }

    @Test
    fun `draining returns everything waiting`() = runTest {
        val unseen = UnseenSends(context)
        unseen.remember("send-a")
        unseen.remember("send-b")

        assertEquals(setOf("send-a", "send-b"), unseen.drain().toSet())
    }

    /**
     * The property that stops one unlock reporting the same message twice.
     */
    @Test
    fun `draining empties the store`() = runTest {
        val unseen = UnseenSends(context)
        unseen.remember("send-a")
        unseen.drain()

        assertTrue(unseen.peek().isEmpty())
        assertTrue(unseen.drain().isEmpty())
    }

    @Test
    fun `draining nothing is not an error`() = runTest {
        assertTrue(UnseenSends(context).drain().isEmpty())
    }

    @Test
    fun `remembering the same send twice yields one entry`() = runTest {
        // FCM can redeliver a push. Two remembers must not become two receipts.
        val unseen = UnseenSends(context)
        unseen.remember("send-a")
        unseen.remember("send-a")

        assertEquals(listOf("send-a"), unseen.drain())
    }
}
