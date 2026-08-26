package com.lovebutton.app

import com.lovebutton.app.push.couldBeLookingNow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predicate that decides whether a message is seen the instant it lands.
 *
 * Getting this backwards would not fail loudly — it would quietly report "she
 * read it" for a phone face-down on a table, which is the one lie this feature
 * must never tell.
 */
class PresenceTest {

    @Test
    fun `awake and unlocked means she could be looking`() {
        assertTrue(couldBeLookingNow(interactive = true, keyguardLocked = false))
    }

    @Test
    fun `awake but locked does not count`() {
        // The screen lit up *because* of the notification. That is the phone
        // reacting, not a person reading.
        assertFalse(couldBeLookingNow(interactive = true, keyguardLocked = true))
    }

    @Test
    fun `unlocked but asleep does not count`() {
        // A phone in a pocket that simply has not re-locked yet.
        assertFalse(couldBeLookingNow(interactive = false, keyguardLocked = false))
    }

    @Test
    fun `asleep and locked does not count`() {
        assertFalse(couldBeLookingNow(interactive = false, keyguardLocked = true))
    }
}
