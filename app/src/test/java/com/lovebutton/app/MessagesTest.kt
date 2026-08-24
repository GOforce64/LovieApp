package com.lovebutton.app

import com.lovebutton.app.data.MESSAGES
import com.lovebutton.app.data.messageForId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `each message has its own channel id`() {
        // Spec 6.3: one channel per message, because the sound is a property of the
        // channel. A shared channel would mean a shared sound.
        assertEquals(listOf("msg_1", "msg_2", "msg_3", "msg_4"), MESSAGES.map { it.channelId })
        assertEquals(4, MESSAGES.map { it.channelId }.toSet().size)
    }

    @Test
    fun `each message has its own sound resource`() {
        // A zero resource id means the raw file is missing or misnamed, which would
        // silently produce a channel with the default sound — and that cannot be
        // fixed afterwards without deleting the channel.
        MESSAGES.forEach { message ->
            assertNotEquals("message ${message.id} has no sound", 0, message.soundRes)
        }
        assertEquals(4, MESSAGES.map { it.soundRes }.toSet().size)
    }

    @Test
    fun `channel ids do not collide with the retired dev channel`() {
        // dev_buzz_v1 is deleted at startup. If a real channel reused that id it
        // would be deleted along with it on every launch.
        assertTrue(MESSAGES.none { it.channelId == "dev_buzz_v1" })
    }
}
