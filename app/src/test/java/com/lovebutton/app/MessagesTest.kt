package com.lovebutton.app

import com.lovebutton.app.data.DEV_CHANNEL_ID
import com.lovebutton.app.data.MESSAGES
import com.lovebutton.app.data.messageForId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesTest {

    @Test
    fun `catalogue has the four spec messages with ids 1 to 4`() {
        assertEquals(4, MESSAGES.size)
        assertEquals(listOf(1, 2, 3, 4), MESSAGES.map { it.id })
    }

    @Test
    fun `each message has non-blank text`() {
        MESSAGES.forEach { message ->
            assert(message.text.isNotBlank()) { "message ${message.id} has blank text" }
        }
    }

    @Test
    fun `messageForId returns the matching message`() {
        assertEquals("I love you", messageForId(1)?.text)
        assertEquals("Call me when you can", messageForId(4)?.text)
    }

    @Test
    fun `messageForId returns null for an unknown id`() {
        // The server validates msg_id too, but a push could still carry an id this
        // build does not know about — an older app receiving a newer message. The
        // receiving code must be able to detect that rather than crash.
        assertNull(messageForId(0))
        assertNull(messageForId(5))
        assertNull(messageForId(-1))
    }

    @Test
    fun `every message uses the temporary dev channel for now`() {
        // Channel sounds are frozen at creation (spec 6.3), so the real per-message
        // channels are not created until their sounds are final in milestone 4.
        MESSAGES.forEach { message ->
            assertEquals(DEV_CHANNEL_ID, message.channelId)
        }
    }
}
