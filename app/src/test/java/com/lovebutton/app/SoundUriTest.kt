package com.lovebutton.app

import com.lovebutton.app.push.soundUriString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SoundUriTest {

    @Test
    fun `sound uri addresses the resource by name`() {
        assertEquals(
            "android.resource://com.lovebutton.app/raw/love",
            soundUriString("com.lovebutton.app", "love"),
        )
    }

    @Test
    fun `sound uri never ends in a numeric resource id`() {
        // The regression this guards: a channel's sound is frozen at creation, so a
        // URI built from a numeric resource id silently stops resolving as soon as
        // adding any resource renumbers the table — and an unresolvable sound plays
        // nothing, with no error. Checking that today's numbers happen to match is
        // not enough; the FORM has to be name-based.
        val uri = soundUriString("com.lovebutton.app", "thinking")
        val lastSegment = uri.substringAfterLast('/')
        assertFalse(
            "sound uri must not address the resource numerically: $uri",
            lastSegment.isNotEmpty() && lastSegment.all { it.isDigit() },
        )
    }
}
