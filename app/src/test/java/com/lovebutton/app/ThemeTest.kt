package com.lovebutton.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lovebutton.app.ui.Fredoka
import com.lovebutton.app.ui.Quicksand
import com.lovebutton.app.ui.Sticker
import com.lovebutton.app.ui.StickerLabel
import com.lovebutton.app.ui.StickerType
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
    fun `the message labels are Fredoka at seventeen`() {
        assertEquals(Fredoka, StickerLabel.fontFamily)
        assertEquals(FontWeight.SemiBold, StickerLabel.fontWeight)
        assertEquals(17.sp, StickerLabel.fontSize)
    }

    @Test
    fun `the label face does not leak into the rest of the app`() {
        // bodyLarge is what Material hands to any Text with no style of its own,
        // and it dresses the enrolment code field. The label got its own style
        // precisely so this one stays Quicksand.
        assertEquals(Quicksand, StickerType.bodyLarge.fontFamily)
        assertEquals(16.sp, StickerType.bodyLarge.fontSize)
        assertEquals(Quicksand, StickerType.bodyMedium.fontFamily)
        assertEquals(Quicksand, StickerType.labelMedium.fontFamily)
        assertEquals(Fredoka, StickerType.titleMedium.fontFamily)
        assertEquals(19.sp, StickerType.titleMedium.fontSize)
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
