package com.lovebutton.app

import com.lovebutton.app.ui.swayOffsetPx
import org.junit.Assert.assertEquals
import org.junit.Test

class SwayingFaceTest {

    @Test
    fun `the middle of the drift is the resting place`() {
        assertEquals(0f, swayOffsetPx(0f, 9f, animationsOn = true), 0.001f)
    }

    @Test
    fun `the ends of the drift are one amplitude either side`() {
        assertEquals(9f, swayOffsetPx(1f, 9f, animationsOn = true), 0.001f)
        assertEquals(-9f, swayOffsetPx(-1f, 9f, animationsOn = true), 0.001f)
    }

    @Test
    fun `the drift is symmetric about the resting place`() {
        // Not decoration: an asymmetric sway reads as the text having slipped
        // rather than as the text breathing.
        listOf(0.25f, 0.5f, 0.75f).forEach { phase ->
            assertEquals(
                -swayOffsetPx(phase, 9f, animationsOn = true),
                swayOffsetPx(-phase, 9f, animationsOn = true),
                0.001f,
            )
        }
    }

    @Test
    fun `a phase beyond the ends cannot push the face further`() {
        // Nothing should ever hand this a phase outside the range, but the
        // placeholder is only sized for one amplitude either side, so a stray
        // value must clamp rather than shove the face out of its own box.
        assertEquals(9f, swayOffsetPx(4f, 9f, animationsOn = true), 0.001f)
        assertEquals(-9f, swayOffsetPx(-4f, 9f, animationsOn = true), 0.001f)
    }

    @Test
    fun `with animations off the face holds perfectly still`() {
        listOf(-1f, -0.5f, 0f, 0.5f, 1f).forEach { phase ->
            assertEquals(
                "phase $phase moved a face that should be still",
                0f,
                swayOffsetPx(phase, 9f, animationsOn = false),
                0.001f,
            )
        }
    }
}
