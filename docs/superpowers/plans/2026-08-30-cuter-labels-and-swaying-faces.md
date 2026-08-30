# Cuter Labels and Swaying Faces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Set the four message labels in Fredoka 17sp, and make the kaomoji face in the home screen's focal line drift gently left and right forever, so the screen never reads as frozen.

**Architecture:** The label change is one new named `TextStyle` in `Theme.kt`, applied at the single call site that renders a message label — deliberately *not* a change to `bodyLarge`, which is Compose's default text style and also dresses the enrolment code field. The sway splits the state copy into words and face so the face can be animated on its own, then re-joins them inside one `Text` via `InlineTextContent`, which keeps the face part of the sentence's own line-breaking instead of a sibling that wraps separately. The animation is a `rememberInfiniteTransition` reversing between two offsets, with the arithmetic pulled out into a pure function so it can be tested without a Compose runtime.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 typography, `InlineTextContent`, `rememberTextMeasurer`, `rememberInfiniteTransition`), JUnit 4 for unit tests.

**Spec:** No separate spec document — this is a bounded change to shipped screens. The decisions it rests on are recorded in the Decisions section below. Background: `love-button-spec.md` §6.1 and §7.1, and `docs/superpowers/specs/2026-08-26-in-app-redesign-design.md` §4.3–§4.5 and §6.

---

## Global Constraints

- **Only the four message labels change typeface.** The state line, guide, setup, enrolment and footer keep Fredoka 19sp / Quicksand exactly as they are. This is an explicit user requirement, not a preference.
- **`bodyLarge` must not be modified.** Material 3's `Text` falls back to `LocalTextStyle`, which `MaterialTheme` supplies as `typography.bodyLarge`, and `OutlinedTextField` in `EnrolScreen.kt:76` renders its input through it. Changing `bodyLarge` changes the enrolment screen.
- **Label typeface:** Fredoka, `FontWeight.SemiBold`, `17.sp`. Chosen by the user against nine alternatives.
- **Message wording is unchanged.** `MESSAGES` in `data/Messages.kt` keeps `"I love you"`, `"Thinking of you"`, `"Miss you"`, `"Call me when you can"` verbatim. Those strings are also the notification body and the notification channel names.
- **One voice for the states.** `guideLine` is what both the focal area and the guide render, and it must stay that way (`ui/StateCopy.kt` header comment; redesign design doc §5). Splitting it for animation must not create a second vocabulary.
- **Reduced motion is honoured** the way the ladder already honours it: `Settings.Global.ANIMATOR_DURATION_SCALE > 0f`, read once and remembered — the exact pattern at `ui/PixelLadder.kt:200-206`.
- **No Compose UI tests.** This module tests pure logic with JUnit only; rendering is verified by hand on hardware (spec §11). Every task here therefore extracts the testable arithmetic into a plain function.
- **Fixed focal height.** The focal card is pinned at `FocalHeight = 196.dp` (`ui/HomeScreen.kt:74`) so the four stickers and the footer never move. Nothing in this plan may make the focal content taller.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/com/lovebutton/app/ui/Theme.kt` | The Sticker Book chrome: colours, type roles | Add `StickerLabel`; widen three `private` vals to `internal` so tests can assert against them |
| `app/src/main/java/com/lovebutton/app/ui/StateCopy.kt` | The words for each state, one set for the whole app | Split each line into words + face; add an idle face |
| `app/src/main/java/com/lovebutton/app/ui/SwayingFace.kt` | **New.** The sway arithmetic, and the state line that animates its face | Create |
| `app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt` | The home screen layout | Two call sites: the label style, and the focal line |
| `app/src/test/java/com/lovebutton/app/ThemeTest.kt` | Theme guards | Add label-style and no-leak assertions |
| `app/src/test/java/com/lovebutton/app/StateCopyTest.kt` | Copy guards | Update pinned lines; add face-coverage assertions |
| `app/src/test/java/com/lovebutton/app/SwayingFaceTest.kt` | **New.** Sway arithmetic | Create |

`SwayingFace.kt` is a new file rather than more of `HomeScreen.kt` because `HomeScreen.kt` is already 340 lines carrying the screen's height budget, and an animated text primitive is a different responsibility from a layout that has to fit a phone.

---

## Decisions

These were settled before the plan and should not be relitigated during execution.

1. **Fredoka 17sp**, chosen from a rendered comparison of ten faces on a mock of the real screen.
2. **The face animates only on the home focal line.** The guide screen lists all six states at once; six faces drifting in a list is noise, and the guide's job is explanation, not liveliness.
3. **Idle gains a face.** `WidgetState.IDLE` is the only state whose line has no kaomoji (`StateCopy.kt:14`), and it is precisely the state the screen rests in — a sway that skips idle is invisible exactly when the app looks stuck. The idle face is `(・ω・)`, waiting rather than sad. This also changes the guide's idle row, which is correct: one voice.
4. **Inline content, not a Row.** At Fredoka 19sp in a 288dp-wide card, the longer lines already wrap. A `Row` or `FlowRow` of two `Text`s would break the sentence at the words/face seam and centre the face beside a two-line block. `InlineTextContent` keeps the face inside the paragraph's own line-breaking.
5. **The placeholder is wider than the face by twice the amplitude**, and the face is centred in it, so a swaying glyph can never reach the edge of its own box.
6. **If the focal line wraps, it wraps always.** Rather than letting the card be one line tall for `click the button!` and two for `traveling in the interwebs (• ε •)`, the line becomes a permanent two-line block, split as near the middle as the words allow. A slot that is always the same height is the same principle the settled line already follows (`HomeScreen.kt:90`, "reserved so its arrival moves nothing above it") and the same bug the height budget was written for in commit 8096b47. This is conditional on Task 3 Step 8 check 4 actually finding a wrap — see the note there, and the height it costs.
7. **Amplitude 3dp, period 1800ms, `FastOutSlowInEasing`, reversing.** Slow enough to read as breathing rather than jitter; large enough to be visible at arm's length. Consistent with the redesign's ruling that the pandas are static and only the focal art moves (commit 91a4f41) — a kaomoji is text, not a panda.

---

## Phase 1 — the two changes agreed

### Task 1: The four labels in Fredoka 17sp

**Files:**
- Modify: `app/src/main/java/com/lovebutton/app/ui/Theme.kt:48-60`
- Modify: `app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt:285`
- Test: `app/src/test/java/com/lovebutton/app/ThemeTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `val StickerLabel: TextStyle` in `com.lovebutton.app.ui`; `internal val Fredoka: FontFamily`, `internal val Quicksand: FontFamily`, `internal val StickerType: Typography` in the same package.

- [x] **Step 1: Write the failing test**

Add to `app/src/test/java/com/lovebutton/app/ThemeTest.kt` — new imports at the top of the file:

```kotlin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lovebutton.app.ui.Fredoka
import com.lovebutton.app.ui.Quicksand
import com.lovebutton.app.ui.StickerLabel
import com.lovebutton.app.ui.StickerType
```

and these two test methods inside the class:

```kotlin
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
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ThemeTest*'`

Expected: FAIL to compile — `Unresolved reference: StickerLabel`, `Unresolved reference: Fredoka`.

- [x] **Step 3: Make the type roles visible and add the label style**

In `app/src/main/java/com/lovebutton/app/ui/Theme.kt`, change the three `private` declarations to `internal` and add `StickerLabel` after them. Replace lines 48–60 with:

```kotlin
internal val Fredoka = FontFamily(Font(R.font.fredoka_semibold, FontWeight.SemiBold))
internal val Quicksand = FontFamily(
    Font(R.font.quicksand_medium, FontWeight.Medium),
    Font(R.font.quicksand_bold, FontWeight.Bold),
)

/**
 * The four message buttons, and only those.
 *
 * A role of its own rather than a change to `bodyLarge`, which is what Material
 * hands to any Text that names no style — including the enrolment code field.
 * Repainting that role would have moved four labels and one text field, and only
 * the four were meant to move.
 *
 * Fredoka rather than Quicksand because the buttons are the loudest thing on the
 * screen after the pandas, and the body voice was making them read as a settings
 * list. One point smaller than the display role above it, so the focal line
 * stays the largest text on the screen.
 */
val StickerLabel = TextStyle(fontFamily = Fredoka, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

internal val StickerType = Typography(
    headlineMedium = TextStyle(fontFamily = Fredoka, fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = Fredoka, fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Quicksand, fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontFamily = Quicksand, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Quicksand, fontSize = 12.sp, fontWeight = FontWeight.Bold),
)
```

- [x] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*ThemeTest*'`

Expected: PASS, 5 tests.

- [x] **Step 5: Apply the style at the one call site**

In `app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt`, line 285, replace:

```kotlin
                            Text(message.text, style = MaterialTheme.typography.bodyLarge)
```

with:

```kotlin
                            Text(message.text, style = StickerLabel)
```

`StickerLabel` is in the same package, so no import is needed. Delete nothing else — `MaterialTheme` is still used elsewhere in the file.

- [x] **Step 6: Verify the app still builds**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/ui/Theme.kt \
        app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt \
        app/src/test/java/com/lovebutton/app/ThemeTest.kt
git commit -m "feat(ui): the message buttons speak in the display voice"
```

---

### Task 2: Split the state copy, and give idle a face

**Files:**
- Modify: `app/src/main/java/com/lovebutton/app/ui/StateCopy.kt:13-20`
- Test: `app/src/test/java/com/lovebutton/app/StateCopyTest.kt:32-40`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `fun guideWords(state: WidgetState, partnerName: String): String` and `fun guideFace(state: WidgetState): String` in `com.lovebutton.app.ui`. `guideLine(state, partnerName)` keeps its existing signature and returns `"${guideWords(...)} ${guideFace(...)}"`.

- [x] **Step 1: Write the failing test**

In `app/src/test/java/com/lovebutton/app/StateCopyTest.kt`, add these imports beside the existing ones:

```kotlin
import com.lovebutton.app.ui.guideFace
import com.lovebutton.app.ui.guideWords
```

Replace the `the lines are the playful ones the guide pins` test (lines 32–40) with:

```kotlin
    @Test
    fun `the lines are the playful ones the guide pins`() {
        assertEquals("click the button! (・ω・)", guideLine(WidgetState.IDLE, "Wifey"))
        assertEquals("on its way to Wifey 0o0", guideLine(WidgetState.SENDING, "Wifey"))
        assertEquals("traveling in the interwebs (• ε •)", guideLine(WidgetState.SENT, "Wifey"))
        assertEquals("it buzzed Wifey's phone :3", guideLine(WidgetState.DELIVERED, "Wifey"))
        assertEquals("Wifey looked at it (>^o^)>", guideLine(WidgetState.SEEN, "Wifey"))
        assertEquals("didn't get through （◞‸◟）", guideLine(WidgetState.FAILED, "Wifey"))
    }

    @Test
    fun `every state has a face to sway`() {
        // The whole point of the sway is that the screen never looks frozen. A
        // state with no face is a state where the screen sits perfectly still —
        // and IDLE, the state it rests in longest, was exactly that state.
        WidgetState.entries.forEach { state ->
            assertTrue("$state has no face", guideFace(state).isNotBlank())
        }
    }

    @Test
    fun `the line is its words and its face, in that order`() {
        // The guide renders guideLine; the focal area renders the halves and
        // animates one of them. If these two ever disagree the app grows a
        // second vocabulary for the same six states, which is the thing
        // StateCopy exists to prevent.
        WidgetState.entries.forEach { state ->
            assertEquals(
                guideWords(state, "Wifey") + " " + guideFace(state),
                guideLine(state, "Wifey"),
            )
        }
    }

    @Test
    fun `the words carry no face of their own`() {
        WidgetState.entries.forEach { state ->
            val words = guideWords(state, "Wifey")
            assertFalse("$state doubles up its face: $words", words.contains(guideFace(state)))
        }
    }
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*StateCopyTest*'`

Expected: FAIL to compile — `Unresolved reference: guideFace`.

- [x] **Step 3: Split the copy**

In `app/src/main/java/com/lovebutton/app/ui/StateCopy.kt`, replace lines 13–20 (the whole `guideLine` function, keeping the KDoc block above it) with:

```kotlin
fun guideWords(state: WidgetState, partnerName: String): String = when (state) {
    WidgetState.IDLE -> "click the button!"
    WidgetState.SENDING -> "on its way to $partnerName"
    WidgetState.SENT -> "traveling in the interwebs"
    WidgetState.DELIVERED -> "it buzzed $partnerName's phone"
    WidgetState.SEEN -> "$partnerName looked at it"
    WidgetState.FAILED -> "didn't get through"
}

/**
 * The face at the end of every line, kept separate so the focal area can move it.
 *
 * Idle has one where it used to have none. It is the state the screen rests in,
 * so a face that only appears once something is in flight would leave the app
 * perfectly still exactly when a reader is asking themselves whether it works.
 *
 * Every state must have one — a blank here is a state that sits frozen, which
 * `every state has a face to sway` in StateCopyTest exists to catch.
 */
fun guideFace(state: WidgetState): String = when (state) {
    WidgetState.IDLE -> "(・ω・)"
    WidgetState.SENDING -> "0o0"
    WidgetState.SENT -> "(• ε •)"
    WidgetState.DELIVERED -> ":3"
    WidgetState.SEEN -> "(>^o^)>"
    WidgetState.FAILED -> "（◞‸◟）"
}

/**
 * The whole line, words then face.
 *
 * The guide renders this; the focal area renders the two halves separately so it
 * can animate the face. Both must say the same thing, which
 * `the line is its words and its face, in that order` pins.
 */
fun guideLine(state: WidgetState, partnerName: String): String =
    "${guideWords(state, partnerName)} ${guideFace(state)}"
```

- [x] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*StateCopyTest*'`

Expected: PASS, 8 tests. `GuideScreen.kt:104` still calls `guideLine` and needs no change.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/ui/StateCopy.kt \
        app/src/test/java/com/lovebutton/app/StateCopyTest.kt
git commit -m "feat(ui): part the state lines into words and a face, and give idle one"
```

---

### Task 3: The face that never sits still

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/ui/SwayingFace.kt`
- Create: `app/src/test/java/com/lovebutton/app/SwayingFaceTest.kt`
- Modify: `app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt:211-218`

**Interfaces:**
- Consumes: `guideWords(state, partnerName)` and `guideFace(state)` from Task 2.
- Produces: `fun swayOffsetPx(phase: Float, amplitudePx: Float, animationsOn: Boolean): Float` and `@Composable fun SwayingStateLine(state: WidgetState, partnerName: String, modifier: Modifier = Modifier)` in `com.lovebutton.app.ui`.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/lovebutton/app/SwayingFaceTest.kt`:

```kotlin
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
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SwayingFaceTest*'`

Expected: FAIL to compile — `Unresolved reference: swayOffsetPx`.

- [x] **Step 3: Write the sway**

Create `app/src/main/java/com/lovebutton/app/ui/SwayingFace.kt`:

```kotlin
package com.lovebutton.app.ui

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.unit.dp
import com.lovebutton.app.widget.WidgetState

/** How far the face travels either side of where it would otherwise sit. */
private val SwayAmplitude = 3.dp

/** One full there-and-back. Half of it is the tween; the reverse is the other half. */
private const val SwayPeriodMillis = 1800

private const val FaceTag = "face"

/**
 * Where the face sits this frame, in pixels from its resting place.
 *
 * Pulled out of the composable so the arithmetic can be tested: this module has
 * no Compose test runtime, and a sway that silently stopped swaying — or that
 * ignored a phone with animations turned off — would otherwise only be
 * catchable by eye.
 *
 * The clamp matters. The inline placeholder is sized for exactly one amplitude
 * either side, so a phase outside the range would push the face past the edge of
 * the box reserved for it.
 */
fun swayOffsetPx(phase: Float, amplitudePx: Float, animationsOn: Boolean): Float =
    if (!animationsOn) 0f else phase.coerceIn(-1f, 1f) * amplitudePx

/**
 * The focal area's state line, with its face drifting side to side forever.
 *
 * The face is inline content rather than a second Text in a Row. At the display
 * size these lines already wrap inside the focal card, and a sibling composable
 * would break the sentence at the seam between the words and the face, leaving
 * the face stranded beside a two-line block. As inline content it takes part in
 * the paragraph's own line-breaking, exactly as the character it replaces did.
 *
 * The motion is small and slow on purpose: it exists to prove the app is alive
 * while nothing is happening, and anything faster reads as a fault rather than a
 * breath. It respects the same animations-off setting as the ladder — a phone
 * that has asked for stillness gets a face that is simply centred.
 */
@Composable
fun SwayingStateLine(
    state: WidgetState,
    partnerName: String,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.titleMedium
    val face = guideFace(state)
    val context = LocalContext.current
    val density = LocalDensity.current

    // Read once, like the ladder does at PixelLadder.kt:200.
    val animationsOn = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }

    val transition = rememberInfiniteTransition(label = "sway")
    val phase by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SwayPeriodMillis / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )

    // The box reserved for the face is one amplitude wider on each side than the
    // face itself, and the face is centred in it. Sizing it to the glyphs alone
    // would put the face's own travel outside its placeholder.
    val measurer = rememberTextMeasurer()
    val measured = remember(face, style, density) {
        measurer.measure(AnnotatedString(face), style)
    }
    val slotWidth = with(density) { (measured.size.width.toDp() + SwayAmplitude * 2).toSp() }
    val slotHeight = with(density) { measured.size.height.toDp().toSp() }
    val amplitudePx = with(density) { SwayAmplitude.toPx() }

    val line = buildAnnotatedString {
        append(guideWords(state, partnerName))
        append(' ')
        appendInlineContent(FaceTag, face)
    }

    val inline = mapOf(
        FaceTag to InlineTextContent(
            Placeholder(
                width = slotWidth,
                height = slotHeight,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
            )
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = face,
                    style = style,
                    modifier = Modifier.graphicsLayer {
                        translationX = swayOffsetPx(phase, amplitudePx, animationsOn)
                    },
                )
            }
        }
    )

    Text(
        text = line,
        style = style,
        textAlign = TextAlign.Center,
        inlineContent = inline,
        modifier = modifier,
    )
}
```

- [x] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*SwayingFaceTest*'`

Expected: PASS, 5 tests.

- [x] **Step 5: Put it on the focal area**

In `app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt`, replace lines 211–218 — the `Text` that renders `guideLine` — with:

```kotlin
                    SwayingStateLine(
                        // The guide's words, deliberately: one voice for a state
                        // wherever it appears, so the guide reads as an explanation
                        // of this screen rather than a second vocabulary for it.
                        // Here the face at the end of the line drifts, which is the
                        // one thing that tells a reader the app is awake while
                        // nothing at all is happening.
                        state = state,
                        partnerName = partnerName,
                    )
```

The `MaterialTheme.typography.titleMedium` style moves inside `SwayingStateLine`, so this call site no longer sets one. Leave the `import androidx.compose.material3.MaterialTheme` in place — `HomeScreen.kt` still uses it at lines 236 and 317.

- [x] **Step 6: Verify the whole suite and the build**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS, all tests green.

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/ui/SwayingFace.kt \
        app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt \
        app/src/test/java/com/lovebutton/app/SwayingFaceTest.kt
git commit -m "feat(ui): the face on the focal line never sits perfectly still"
```

- [x] **Step 8: Check it on the phone**

Install and open the app. This step is the gate — the rest of this task's verification was arithmetic.

```bash
./gradlew :app:installDebug
```

Confirm, in order:

1. **The four labels** are visibly rounder and one point larger, and the enrolment screen's text field is untouched (it only shows on a fresh install — check it by eye against a screenshot if the phone is already enrolled).
2. **The idle face drifts** left and right, slowly, with the app just sitting there. This is the whole feature.
3. **Nothing reflows as it drifts.** The words either side of the face must not shuffle. If they do, the placeholder is being remeasured every frame and the sway needs to move to a `graphicsLayer` on a fixed-size parent.
4. **The focal card still fits.** ✅ **Answered ahead of the phone, by measurement.** Line widths were computed from the real font files — `fredoka_semibold.ttf` for the words, Noto Sans and Noto Sans CJK for the glyphs Fredoka lacks (`ε ・ ω （ ） ◞ ‸ ◟`), at `titleMedium`'s 19sp, including the 6dp the sway's placeholder adds. Longest line is SENT at **275.2dp**. Available is 284dp on a 360dp phone, 317dp at 393dp, 335dp at 411dp — so the worst line has **8.8dp of headroom on the narrowest phone** and 42–60dp on the two Xiaomis this actually runs on. Nothing wraps; the permanent two-line split of decision 6 is **not triggered**. The remaining exposure is system font scale: at roughly 1.03× and above the 360dp case wraps, which is pre-existing behaviour the sway did not create. Still confirm by eye on the phone.

   Historical note, kept because it is the reasoning the ruling rests on:

   If it does wrap, the ruling is decision 6: make it two lines permanently, split as near the middle as the words allow, so the card is the same height in every state. **Do not implement that silently** — it costs height the card does not have. One line at Fredoka 19sp is about 23dp, and the card's content already spends 112 (icon) + 10 (gap) + 20 (settled slot) + 24 (padding) of its 196dp, leaving roughly 30dp for text. A permanent second line needs about 23dp more, and it has to come from one of three places: raising `FocalHeight` (which `fitHome` then takes back out of the pandas and their clearance on short phones), shrinking `AnimatedPixelIcon` from 112dp, or trimming the vertical padding. Measure it, bring the number and the three options, and let the user choose.
5. **Turn animations off** (Developer options → Animator duration scale → Off) and confirm the face holds still and stays centred.

Report what you saw for each of the five. Do not mark this task complete on tests alone.

**Result — all five pass**, on phone A (`923262ff`, 24115RA8EG, Android 16), 2026-08-30. Measured from screenshot bursts rather than judged by eye, by taking the dark-pixel column extents of the focal line:

1. **Labels** are visibly Fredoka and one point larger. The enrolment screen was not reachable (the phone is already enrolled) but is protected by `the label face does not leak into the rest of the app`.
2. **The face drifts.** Right edge of the line travelled 933 → 951px across three frames 0.45s apart: 18px total, which at this phone's density is exactly 6dp — the designed ±3dp.
3. **Nothing reflows.** Left edge of the words was 269px in every frame, and the ink count was identical (11219px) — the same glyphs, moved, not relaid out. The placeholder is measured once, as intended.
4. **No wrap.** `it buzzed Wifey's phone :3` renders on one line, matching the font-metric prediction.
5. **Animations off** (`animator_duration_scale 0`, app force-stopped and relaunched): travel 0px across three frames, resting at 942px — the centre of the 933..951 range, so it parks centred rather than stuck at an extreme. The setting was restored to 1 afterwards.

---

## Phase 2 — the 1.0 polish, pending a go-ahead

These three came out of the pre-1.0 review and are **not yet approved**. Do not start them without a yes. Each is independent of the others and of Phase 1.

### Task 4: Her status bar shows the app, not an envelope

**Files:**
- Create: `app/src/main/res/drawable/ic_notification_heart.xml`
- Modify: `app/src/main/java/com/lovebutton/app/push/Notifications.kt:113`
- Test: none — this is a resource swap with no logic. Verified by eye on hardware.

**Interfaces:**
- Consumes: nothing.
- Produces: `R.drawable.ic_notification_heart`.

Android renders a notification's small icon as a silhouette: it keeps the alpha channel and throws the colours away. So this is the existing pixel heart with both of its paths merged into one white path — the same shape the app draws, in the form the status bar can use.

- [ ] **Step 1: Build the silhouette from the art the app already has**

Run this to produce the file from the two paths in `ic_heart_filled.xml`, so the silhouette cannot drift from the icon:

```bash
python3 - <<'PY'
import re, pathlib
src = pathlib.Path("app/src/main/res/drawable/ic_heart_filled.xml").read_text()
paths = re.findall(r'android:pathData="([^"]+)"', src)
merged = "".join(paths)
out = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!--
      The status bar keeps only the alpha of a small icon, so this is the whole
      heart as one opaque shape: the fill and the border of ic_heart_filled.xml
      merged. Generated, not drawn — a hand-copy would drift from the icon the
      app itself shows.
    -->
    <group android:translateX="1" android:translateY="1">
        <path android:fillColor="#FFFFFFFF" android:pathData="{merged}" />
    </group>
</vector>
'''
pathlib.Path("app/src/main/res/drawable/ic_notification_heart.xml").write_text(out)
print("paths merged:", len(paths))
PY
```

Expected: `paths merged: 2`.

- [ ] **Step 2: Point the notification at it**

In `app/src/main/java/com/lovebutton/app/push/Notifications.kt`, line 113, replace:

```kotlin
        .setSmallIcon(android.R.drawable.ic_dialog_email)
```

with:

```kotlin
        .setSmallIcon(R.drawable.ic_notification_heart)
```

and add the import beside the existing ones at the top of the file:

```kotlin
import com.lovebutton.app.R
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Check it on the phone**

Install, send a message from the other phone, and look at the status bar. The icon must be a recognisable heart at status-bar size, not a filled blob — the pixel grid is 16 cells across and the bar is about 24dp, so this is the real risk. Also check the notification shade, where it appears again beside the app name.

If it reads as a blob, report that rather than redrawing: the fix is a simplified heart outline, which is a decision about the art, not about this task.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/drawable/ic_notification_heart.xml \
        app/src/main/java/com/lovebutton/app/push/Notifications.kt
git commit -m "feat(app): her status bar wears the app's own heart"
```

---

### Task 5: Say so when there is no phone to buzz

**Files:**
- Modify: `app/src/main/java/com/lovebutton/app/data/CurrentSend.kt`
- Modify: `app/src/main/java/com/lovebutton/app/work/SendWorker.kt:68-72`
- Modify: `app/src/main/java/com/lovebutton/app/ui/StateCopy.kt`
- Modify: `app/src/main/java/com/lovebutton/app/ui/SwayingFace.kt`
- Modify: `app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt`
- Test: `app/src/test/java/com/lovebutton/app/CurrentSendTest.kt`, `app/src/test/java/com/lovebutton/app/StateCopyTest.kt`

`/v1/send` returns `{send_id, delivered: n}` and returns 200 even when `n == 0`, because the send was recorded and her phone simply has no registered device (spec §5.2). `SendWorker.kt:70` already receives that count and deliberately treats zero as success — correct for the widget, but it means the one case where the app knows the message will not arrive is the one case it says nothing about.

This adds a flag to the app's own store only. `WidgetState` is untouched: adding a seventh state would ripple through the widget art, the guide, `holdMillis` and `advancesTo` for something no widget can show. The widget forgets; the app remembers. That is the existing division (spec §6.1).

**Interfaces:**
- Consumes: `guideWords` / `guideFace` from Task 2; `SwayingStateLine` from Task 3.
- Produces: `SendSnapshot.noDevice: Boolean`; `CurrentSend.markNoDevice(sendId: String)`; `guideWords`/`guideFace` gain a `noDevice: Boolean = false` parameter; `SwayingStateLine` gains `noDevice: Boolean = false`.

- [ ] **Step 1: Write the failing store test**

Add to `app/src/test/java/com/lovebutton/app/CurrentSendTest.kt`, following the existing tests' setup in that file:

```kotlin
    @Test
    fun `a send with nowhere to land is remembered as such`() = runTest {
        val store = CurrentSend(context)
        store.start("s1", 1)
        store.markNoDevice("s1")
        assertEquals(true, store.current()?.noDevice)
    }

    @Test
    fun `a send that landed is not marked`() = runTest {
        val store = CurrentSend(context)
        store.start("s1", 1)
        assertEquals(false, store.current()?.noDevice)
    }

    @Test
    fun `a stale no-device report cannot touch a newer send`() {
        // Same race the state update guards against: the worker for an earlier
        // send can finish after a later send has replaced the record.
        runTest {
            val store = CurrentSend(context)
            store.start("s1", 1)
            store.start("s2", 2)
            store.markNoDevice("s1")
            assertEquals(false, store.current()?.noDevice)
        }
    }

    @Test
    fun `a new send clears the mark`() = runTest {
        val store = CurrentSend(context)
        store.start("s1", 1)
        store.markNoDevice("s1")
        store.start("s2", 2)
        assertEquals(false, store.current()?.noDevice)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*CurrentSendTest*'`

Expected: FAIL to compile — `Unresolved reference: markNoDevice`.

- [ ] **Step 3: Add the flag to the store**

In `app/src/main/java/com/lovebutton/app/data/CurrentSend.kt`:

Add to the imports:

```kotlin
import androidx.datastore.preferences.core.booleanPreferencesKey
```

Add the field to `SendSnapshot`:

```kotlin
data class SendSnapshot(
    val sendId: String,
    val msgId: Int,
    val state: WidgetState,
    val at: Long,
    /** The server accepted it, but she had no device registered to receive it. */
    val noDevice: Boolean = false,
)
```

Add the key inside `object Keys`:

```kotlin
        val NO_DEVICE = booleanPreferencesKey("no_device")
```

Read it in `flow` — replace the `SendSnapshot(...)` construction with:

```kotlin
        SendSnapshot(
            sendId,
            msgId,
            fromName(prefs[Keys.STATE]),
            at,
            prefs[Keys.NO_DEVICE] ?: false,
        )
```

Clear it in `start`, beside the other writes:

```kotlin
            prefs[Keys.NO_DEVICE] = false
```

And add this method after `update`:

```kotlin
    /**
     * Records that the send was accepted with nowhere to deliver it.
     *
     * Guarded on the id for the same reason [update] is: the worker for an
     * earlier send can finish after a later one has replaced the record, and a
     * stale report would put a warning under a message that is fine.
     */
    suspend fun markNoDevice(sendId: String) {
        context.currentSendStore.edit { prefs ->
            if (prefs[Keys.SEND_ID] != sendId) return@edit
            prefs[Keys.NO_DEVICE] = true
        }
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*CurrentSendTest*'`

Expected: PASS.

- [ ] **Step 5: Report it from the worker**

In `app/src/main/java/com/lovebutton/app/work/SendWorker.kt`, replace lines 68–72 — the `try` block's success path — with:

```kotlin
        return try {
            val result = LoveButtonApi(BuildConfig.API_BASE_URL).send(enrolment.authToken, msgId, sendId)
            currentSend.update(sendId, WidgetState.SENT)
            // A delivered count of zero still counts as success: the send was
            // recorded, her phone just has no active device right now. The tile
            // has no way to show that, but the app screen does — this is the one
            // case where the app knows the message will not arrive.
            if (result.delivered == 0) currentSend.markNoDevice(sendId)
            settle(appWidgetId, WidgetState.SENT, Result.success())
```

- [ ] **Step 6: Write the failing copy test**

Add to `app/src/test/java/com/lovebutton/app/StateCopyTest.kt`:

```kotlin
    @Test
    fun `a send with nowhere to land says so instead of claiming success`() {
        val landed = guideWords(WidgetState.SENT, "Wifey", noDevice = false)
        val stranded = guideWords(WidgetState.SENT, "Wifey", noDevice = true)
        assertNotEquals(landed, stranded)
        assertTrue(stranded, stranded.contains("Wifey"))
        assertTrue(stranded, guideFace(WidgetState.SENT, noDevice = true).isNotBlank())
    }

    @Test
    fun `only sent changes when there is no device`() {
        // The flag is only ever set alongside SENT. If some other state started
        // reading it, the line would contradict a receipt that had already
        // arrived.
        WidgetState.entries.filter { it != WidgetState.SENT }.forEach { state ->
            assertEquals(
                guideWords(state, "Wifey", noDevice = false),
                guideWords(state, "Wifey", noDevice = true),
            )
        }
    }
```

Add `import org.junit.Assert.assertNotEquals` to the file's imports.

- [ ] **Step 7: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*StateCopyTest*'`

Expected: FAIL to compile — too many arguments for `guideWords`.

- [ ] **Step 8: Add the line**

In `app/src/main/java/com/lovebutton/app/ui/StateCopy.kt`, change the two functions to take the flag:

```kotlin
fun guideWords(state: WidgetState, partnerName: String, noDevice: Boolean = false): String = when {
    state == WidgetState.SENT && noDevice -> "sent, but $partnerName has no phone signed in"
    else -> when (state) {
        WidgetState.IDLE -> "click the button!"
        WidgetState.SENDING -> "on its way to $partnerName"
        WidgetState.SENT -> "traveling in the interwebs"
        WidgetState.DELIVERED -> "it buzzed $partnerName's phone"
        WidgetState.SEEN -> "$partnerName looked at it"
        WidgetState.FAILED -> "didn't get through"
    }
}

fun guideFace(state: WidgetState, noDevice: Boolean = false): String = when {
    state == WidgetState.SENT && noDevice -> "(・_・;)"
    else -> when (state) {
        WidgetState.IDLE -> "(・ω・)"
        WidgetState.SENDING -> "0o0"
        WidgetState.SENT -> "(• ε •)"
        WidgetState.DELIVERED -> ":3"
        WidgetState.SEEN -> "(>^o^)>"
        WidgetState.FAILED -> "（◞‸◟）"
    }
}
```

`guideLine` keeps its two-argument signature and its defaults, so `GuideScreen.kt:104` and every existing test still compile — the guide describes the six states, not this one send's outcome.

- [ ] **Step 9: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*StateCopyTest*'`

Expected: PASS.

- [ ] **Step 10: Pass the flag through the focal line**

In `app/src/main/java/com/lovebutton/app/ui/SwayingFace.kt`, add the parameter to `SwayingStateLine` and forward it:

```kotlin
@Composable
fun SwayingStateLine(
    state: WidgetState,
    partnerName: String,
    noDevice: Boolean = false,
    modifier: Modifier = Modifier,
) {
```

and inside it change the two copy calls:

```kotlin
    val face = guideFace(state, noDevice)
```

```kotlin
        append(guideWords(state, partnerName, noDevice))
```

In `app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt`, pass it at the call site added in Task 3:

```kotlin
                        state = state,
                        partnerName = partnerName,
                        noDevice = snapshot?.noDevice == true,
```

- [ ] **Step 11: Run the whole suite and build**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`

Expected: all green, BUILD SUCCESSFUL.

- [ ] **Step 12: Check it on the phone**

The natural way to produce the condition: on the receiving phone, open the app and sign out (`DELETE /v1/devices`), or delete its device row:

```bash
wrangler d1 execute love-button --remote \
  --command "SELECT id, person, label FROM devices"
```

Then send from the other phone and confirm the focal line says the message went nowhere, and that the widget tile still shows plain SENT — the widget has no way to say this and must not pretend otherwise. Re-enrol the receiving phone afterwards.

- [ ] **Step 13: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/data/CurrentSend.kt \
        app/src/main/java/com/lovebutton/app/work/SendWorker.kt \
        app/src/main/java/com/lovebutton/app/ui/StateCopy.kt \
        app/src/main/java/com/lovebutton/app/ui/SwayingFace.kt \
        app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt \
        app/src/test/java/com/lovebutton/app/CurrentSendTest.kt \
        app/src/test/java/com/lovebutton/app/StateCopyTest.kt
git commit -m "feat(app): say so when there is no phone on the other end"
```

---

### Task 6: Call it 1.0

**Files:**
- Modify: `app/build.gradle.kts:41-42`

Do this **last**, after Tasks 1–5 are on the phone and confirmed. A version number is a claim about what is in the build.

- [ ] **Step 1: Bump it**

In `app/build.gradle.kts`, replace:

```kotlin
        versionCode = 1
        versionName = "0.1"
```

with:

```kotlin
        versionCode = 2
        versionName = "1.0"
```

`versionCode` goes to 2 rather than staying at 1 because builds carrying code 1 are already installed on both phones, and Android refuses to install over an equal code with different content.

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore(app): 1.0"
```

---

## Explicitly out of scope

- **Milestone 8** — root README, release keystore and signing config, the gitleaks pre-commit hook, narrowing the `INVALID_ARGUMENT` device deletion (`server/src/fcm.ts:45`), and refreshing the two stale docs (`docs/MANUAL-SETUP.md`, `server/README.md`). Deferred by the user until the app is finished, because switching to a release keystore forces an uninstall and a re-enrol on both phones.
- **Message wording.** Unchanged, by decision — the pandas already carry that job.
- **`bodySmall` and `titleSmall`** are used in `SetupScreen.kt:231,306,311` but are not defined in `StickerType`, so those three lines currently render in Material's default Roboto rather than Quicksand. Noticed while checking that the label change could not leak; it is a pre-existing inconsistency on a screen this plan does not touch, and fixing it would be a change to the rest of the app's type, which is exactly what the user ruled out.

---

## Self-Review

**1. Coverage of what was agreed**

| Agreed item | Task |
|---|---|
| Four labels in Fredoka | 1 |
| At 17sp | 1 |
| Nothing else changes typeface | 1 (Step 3's comment, and the `does not leak` test) |
| Faces move left and right, always | 3 |
| So the user knows it is not stuck | 2 (idle gains a face) + 3 |
| Wording unchanged | Global Constraints; no task touches `MESSAGES` |
| Pre-1.0 ideas, pending approval | 4, 5, 6 |

Idea 5 from the review — widget picker previews showing bare glyphs — has no task. It is art direction rather than a code change, and it was raised as minor. Named here so its absence is a decision, not an oversight.

**2. Placeholder scan**

No TBDs. Every code step carries the literal text to write. The one generated file (Task 4's silhouette) ships as the script that generates it plus its expected output line.

**3. Type consistency**

- `guideWords(state, partnerName)` (Task 2) → gains `noDevice: Boolean = false` (Task 5). The default keeps Task 3's two-argument call valid, so Task 5 does not have to revisit Task 3's code beyond the one line it explicitly changes.
- `guideFace(state)` (Task 2) → same, gains `noDevice: Boolean = false` (Task 5).
- `guideLine(state, partnerName)` never changes signature. `GuideScreen.kt:104` is never touched by any task.
- `swayOffsetPx(phase, amplitudePx, animationsOn)` (Task 3) → used by `SwayingStateLine` in the same file and by `SwayingFaceTest`. Same three parameters in both.
- `SwayingStateLine(state, partnerName, modifier)` (Task 3) → `SwayingStateLine(state, partnerName, noDevice, modifier)` (Task 5). `modifier` stays last; `HomeScreen` passes arguments by name at both points, so the insertion is safe.
- `SendSnapshot` gains `noDevice` with a default (Task 5), so the existing four-argument constructions in `CurrentSendTest` keep compiling.
- `StickerLabel` (Task 1) is used at `HomeScreen.kt:285` and in `ThemeTest`. Same name.

**4. Known risks**

- **Task 3's placeholder may be remeasured per frame.** `rememberTextMeasurer` is keyed on the face and the style, not the animation, so it should measure once per state. If the words visibly shuffle as the face drifts, that assumption is wrong — Step 8's check 3 exists to catch it, and the fallback is to move the `graphicsLayer` onto a fixed-size wrapper.
- **The focal card may already be overflowing.** At Fredoka 19sp in a 288dp-wide card, `traveling in the interwebs (• ε •)` is close to a second line, and the card's content budget (112dp icon + 10 gap + line + 20 settled + 24 padding) leaves room for one. This plan does not make the line longer — the face was always in it — but Step 8's check 4 is the first time anyone has looked for it deliberately. The ruling if it wraps is decision 6: two lines permanently, split near the middle. The open question is not whether to do that but where the ~23dp comes from, which is why check 4 asks for the measurement and the three options rather than a fix.
- **Task 4's icon may read as a blob.** A 16-cell pixel grid at status-bar size is the real risk, and the step says to report rather than redraw, because the fix is a decision about art.
- **`PlaceholderVerticalAlign.Center`** aligns the face to the centre of the line box rather than its baseline. For these kaomoji, which are mostly symmetric about their own middle, that reads better than a baseline alignment — but it is the parameter to change first if the face sits visibly high or low against the words.
