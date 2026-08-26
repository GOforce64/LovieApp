package com.lovebutton.app

import androidx.compose.ui.graphics.Color
import com.lovebutton.app.ui.Sticker
import com.lovebutton.app.ui.stickerColorFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThemeTest {

    @Test
    fun `each message has its own sticker colour`() {
        val colours = (1..4).map { stickerColorFor(it) }
        assertEquals("the four must be distinguishable by hue", 4, colours.toSet().size)
    }

    @Test
    fun `an unknown message falls back to plain surface`() {
        assertEquals(Sticker.Surface, stickerColorFor(99))
    }

    @Test
    fun `the theme does not redeclare the ladder colours`() {
        // If a state colour appeared here too, the guide could one day describe
        // a colour the widget no longer draws. They live in PixelPalette only.
        val chrome = listOf(
            Sticker.Ground, Sticker.Ink, Sticker.Surface,
            Sticker.Mint, Sticker.Blossom, Sticker.Butter,
        )
        val ladder = listOf(
            Color(0xFFD81B60), Color(0xFFFF6FA5), Color(0xFFFFC64B),
        )
        ladder.forEach { state ->
            assertFalse("chrome must not contain the ladder colour $state", chrome.contains(state))
        }
    }
}
