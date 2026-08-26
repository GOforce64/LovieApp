# In-App Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the app's scaffold home screen with a designed one that shows the receipt ladder live, and add a guide explaining what the widget colours mean.

**Architecture:** A `CurrentSend` DataStore carries one send's state from `SendWorker` and `PushService` to the UI as a `Flow`. The focal heart is drawn in Compose Canvas from the same ASCII grids that generate the widget's VectorDrawables, so it can animate per pixel while remaining the same artwork. The guide renders the widget's own drawables through a mapping function shared with `MessageWidget`.

**Tech Stack:** Kotlin, Jetpack Compose, Glance (widgets), DataStore Preferences, WorkManager, Robolectric + JUnit4, Python 3 (icon generator).

**Spec:** `docs/superpowers/specs/2026-08-26-in-app-redesign-design.md`

## Global Constraints

- **The three state colours are defined once and shared**: `sent #D81B60`, `delivered #FF6FA5`, `seen #FFC64B`. Also `border #D1447E`, `shine #FFD9E8`, `idle #C98BA8`, `failed grey #A9A2AD`. Never re-declare these as literals in a new file — import them.
- **The guide shows the widget's own drawables, unmodified.** No re-drawn approximations. Spec §5.
- **The focal area keeps its final state; the widget returns to idle.** Deliberate divergence. Spec §4.3.
- **Pandas are static art and never express state.** Spec §7.
- **Guide copy is playful, focal copy is warm and plain.** Deliberate. Do not harmonise them. Spec §5.
- `{partner}` is substituted from `Prefs.enrolment.partnerName`.
- minSdk 26, targetSdk 36, JDK 21+ for tests (Robolectric 4.16 / SDK 36).
- Never run `./gradlew` with `--rerun-tasks` omitted when you need proof tests actually ran — Gradle reports `BUILD SUCCESSFUL` for an up-to-date task without executing a single test.

---

## File Structure

**Created:**
- `app/src/main/java/com/lovebutton/app/widget/PixelGrids.kt` — GENERATED. The ASCII grids as Kotlin data.
- `app/src/main/java/com/lovebutton/app/widget/WidgetArt.kt` — state→drawable mapping and the colour palette. Shared by widget and guide.
- `app/src/main/java/com/lovebutton/app/data/CurrentSend.kt` — the one-record store the UI observes.
- `app/src/main/java/com/lovebutton/app/ui/PixelArt.kt` — Canvas renderer for a grid.
- `app/src/main/java/com/lovebutton/app/ui/PixelLadder.kt` — the six animations.
- `app/src/main/java/com/lovebutton/app/ui/StateCopy.kt` — state→words.
- `app/src/main/java/com/lovebutton/app/ui/GuideScreen.kt` — the six-row guide.

**Modified:**
- `scripts/pixel_icons.py` — stale colours; add Kotlin emitter.
- `app/src/main/java/com/lovebutton/app/widget/MessageWidget.kt` — use the shared mapping.
- `app/src/main/java/com/lovebutton/app/work/SendWorker.kt` — write `CurrentSend`.
- `app/src/main/java/com/lovebutton/app/push/PushService.kt` — update `CurrentSend`.
- `app/src/main/java/com/lovebutton/app/ui/Theme.kt` — Sticker Book tokens and fonts.
- `app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt` — full rewrite.
- `app/src/main/java/com/lovebutton/app/MainActivity.kt` — route to the guide.
- `love-button-spec.md` — §6.1 and §7.1 amendments.

---

### Task 1: Fix the generator and have it emit Kotlin grids

The generator currently holds pre-swap colours. Re-running it today would silently revert a colour decision already made on hardware. Nothing else in this plan is safe until that gun is unloaded.

**Files:**
- Modify: `scripts/pixel_icons.py:24-28`
- Create: `app/src/main/java/com/lovebutton/app/widget/PixelGrids.kt` (generated output)
- Test: `app/src/test/java/com/lovebutton/app/PixelGridsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object PixelGrids { val GRIDS: Map<String, List<String>> }` with keys `"heart"`, `"bubble"`, `"paw"`, `"call"`. Each value is a list of equal-length strings using `X` solid, `s` shine, `o` hole, `.` empty.

- [ ] **Step 1: Record the current drawables so drift is detectable**

```bash
cd /home/killua/Projects/LovieApp
mkdir -p /tmp/icon-baseline
cp app/src/main/res/drawable/ic_*.xml /tmp/icon-baseline/
ls /tmp/icon-baseline | wc -l   # expect 20
```

- [ ] **Step 2: Fix the two stale colour constants**

In `scripts/pixel_icons.py`, replace lines 24-28's constants so they match what actually ships. The names describe roles, so the *names* move, not just the values:

```python
BORDER = "#D1447E"
FILL   = "#FF6FA5"   # delivered: it landed on her phone
SHINE  = "#FFD9E8"
IDLE   = "#C98BA8"
DEEP   = "#D81B60"   # sent: the server has it, not yet her phone
GOLD   = "#FFC64B"   # seen: a ring outside the shape, she actually looked
```

Then swap their use so `filled (sent)` paints `DEEP` and `delivered` paints `FILL`. In `main()`'s variant loop (around line 340):

```python
            if variant == "filled (sent)":
                for cells, colour in ((i, DEEP), (b, BORDER), (sh, SHINE)):
                    ...
            elif variant == "delivered":
                for cells, colour in ((i, FILL), (b, BORDER), (sh, SHINE)):
                    ...
```

And in `seen_layers`, the deepened fill becomes `FILL` (pink), since seen is delivered-plus-gold:

```python
    return [(i, FILL), (b, GOLD), (sh, SHINE)]
```

- [ ] **Step 3: Re-run and prove the output is byte-identical**

```bash
python3 scripts/pixel_icons.py
diff -r /tmp/icon-baseline app/src/main/res/drawable/ --include='ic_*.xml' && echo "IDENTICAL"
```

Expected: `IDENTICAL`. **If any file differs, stop and report the diff.** A difference means the generator drifted from the drawables in some way beyond colour, and that difference is a finding that must be understood before continuing — do not "fix" it by overwriting the drawables.

- [ ] **Step 4: Add the Kotlin emitter**

Append to `scripts/pixel_icons.py`, and call it from `main()`:

```python
KOTLIN_OUT = "app/src/main/java/com/lovebutton/app/widget/PixelGrids.kt"

def emit_kotlin(all_icons):
    """The grids as Kotlin, so the app can animate individual pixels.

    The app draws the focal heart on a Canvas rather than from the finished
    VectorDrawable, because a VectorDrawable cannot be lit one cell at a time.
    Emitting from the same run as the XML is what stops the two from drifting;
    hand-copying these grids into Kotlin would guarantee that they eventually did.
    """
    lines = [
        "package com.lovebutton.app.widget",
        "",
        "// GENERATED by scripts/pixel_icons.py — do not edit by hand.",
        "// Regenerate with: python3 scripts/pixel_icons.py",
        "object PixelGrids {",
        "    val GRIDS: Map<String, List<String>> = mapOf(",
    ]
    for name, art in all_icons.items():
        rows = [r for r in art.strip("\n").split("\n")]
        lines.append(f'        "{name}" to listOf(')
        for r in rows:
            lines.append(f'            "{r}",')
        lines.append("        ),")
    lines += ["    )", "}", ""]
    with open(KOTLIN_OUT, "w") as f:
        f.write("\n".join(lines))
    print(f"wrote {KOTLIN_OUT}")
```

Call it once, with the merged icon dict, at the end of `main()`:

```python
    emit_kotlin({**ICONS_11, **ICONS_13})
```

- [ ] **Step 5: Regenerate and confirm the drawables STILL match**

```bash
python3 scripts/pixel_icons.py
diff -r /tmp/icon-baseline app/src/main/res/drawable/ --include='ic_*.xml' && echo "STILL IDENTICAL"
head -12 app/src/main/java/com/lovebutton/app/widget/PixelGrids.kt
```

Expected: `STILL IDENTICAL`, and the Kotlin file starts with the package line and the generated-file warning.

- [ ] **Step 6: Write the test that ties the grids to the drawables**

`app/src/test/java/com/lovebutton/app/PixelGridsTest.kt`:

```kotlin
package com.lovebutton.app

import com.lovebutton.app.widget.PixelGrids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelGridsTest {

    @Test
    fun `every message icon has a grid`() {
        listOf("heart", "bubble", "paw", "call").forEach { name ->
            assertTrue("missing grid: $name", PixelGrids.GRIDS.containsKey(name))
        }
    }

    @Test
    fun `every grid is rectangular`() {
        // A ragged grid would silently shift cells when rendered, and the bug
        // would look like bad art rather than bad data.
        PixelGrids.GRIDS.forEach { (name, rows) ->
            val width = rows.first().length
            rows.forEach { row ->
                assertEquals("$name has a ragged row", width, row.length)
            }
        }
    }

    @Test
    fun `every grid is square and uses only known cell characters`() {
        PixelGrids.GRIDS.forEach { (name, rows) ->
            assertEquals("$name is not square", rows.size, rows.first().length)
            rows.forEach { row ->
                row.forEach { ch ->
                    assertTrue("$name has unknown cell '$ch'", ch in "Xso.")
                }
            }
        }
    }

    @Test
    fun `the heart grid has solid cells`() {
        val heart = PixelGrids.GRIDS.getValue("heart")
        assertTrue(heart.sumOf { row -> row.count { it == 'X' || it == 's' } } > 40)
    }
}
```

- [ ] **Step 7: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*PixelGridsTest*" --rerun-tasks
```

Expected: 4 tests, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add scripts/pixel_icons.py app/src/main/java/com/lovebutton/app/widget/PixelGrids.kt app/src/test/java/com/lovebutton/app/PixelGridsTest.kt
git commit -m "fix(icons): correct the generator's stale colours and emit Kotlin grids"
```

---

### Task 2: Extract the state→art mapping so the guide cannot drift

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/widget/WidgetArt.kt`
- Modify: `app/src/main/java/com/lovebutton/app/widget/MessageWidget.kt:50-58, 94-96, 98-130`
- Test: `app/src/test/java/com/lovebutton/app/WidgetArtTest.kt`

**Interfaces:**
- Consumes: `WidgetState` (existing enum, `widget/WidgetState.kt`), `PixelGrids.GRIDS` from Task 1.
- Produces:
  - `fun gridNameFor(msgId: Int): String`
  - `fun iconFor(msgId: Int, state: WidgetState): Int` (a `@DrawableRes` id)
  - `fun tintColorFor(state: WidgetState): Int?` (ARGB int, null when untinted)
  - `object PixelPalette { val Border: Int; val Shine: Int; val Sent: Int; val Delivered: Int; val Gold: Int; val Idle: Int; val Failed: Int }` — all ARGB ints.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/lovebutton/app/WidgetArtTest.kt`:

```kotlin
package com.lovebutton.app

import com.lovebutton.app.R
import com.lovebutton.app.widget.PixelPalette
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.gridNameFor
import com.lovebutton.app.widget.iconFor
import com.lovebutton.app.widget.tintColorFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetArtTest {

    @Test
    fun `each message maps to its own grid`() {
        assertEquals("heart", gridNameFor(1))
        assertEquals("bubble", gridNameFor(2))
        assertEquals("paw", gridNameFor(3))
        assertEquals("call", gridNameFor(4))
    }

    @Test
    fun `an unknown message falls back to the heart`() {
        // Glance state outlives reinstalls, so an id from another build can
        // arrive. Falling back beats throwing inside a widget update.
        assertEquals("heart", gridNameFor(99))
    }

    @Test
    fun `the ladder maps to five distinct pictures`() {
        val icons = listOf(
            WidgetState.IDLE, WidgetState.SENDING, WidgetState.SENT,
            WidgetState.DELIVERED, WidgetState.SEEN,
        ).map { iconFor(1, it) }

        assertEquals("the five ladder states must not share art", 5, icons.toSet().size)
    }

    @Test
    fun `failed reuses the outline`() {
        // It is not a point on the fill scale — it is the sequence abandoned —
        // so it borrows idle's picture and is told apart by the tint.
        assertEquals(iconFor(1, WidgetState.IDLE), iconFor(1, WidgetState.FAILED))
    }

    @Test
    fun `only failed is tinted`() {
        assertNotNull(tintColorFor(WidgetState.FAILED))
        listOf(
            WidgetState.IDLE, WidgetState.SENDING, WidgetState.SENT,
            WidgetState.DELIVERED, WidgetState.SEEN,
        ).forEach { assertNull("$it must not be tinted", tintColorFor(it)) }
    }

    @Test
    fun `the palette carries the three state colours the spec pins`() {
        assertEquals(0xFFD81B60.toInt(), PixelPalette.Sent)
        assertEquals(0xFFFF6FA5.toInt(), PixelPalette.Delivered)
        assertEquals(0xFFFFC64B.toInt(), PixelPalette.Gold)
    }

    @Test
    fun `the heart drawables are the ones the guide will show`() {
        assertEquals(R.drawable.ic_heart_outline, iconFor(1, WidgetState.IDLE))
        assertEquals(R.drawable.ic_heart_half, iconFor(1, WidgetState.SENDING))
        assertEquals(R.drawable.ic_heart_filled, iconFor(1, WidgetState.SENT))
        assertEquals(R.drawable.ic_heart_delivered, iconFor(1, WidgetState.DELIVERED))
        assertEquals(R.drawable.ic_heart_seen, iconFor(1, WidgetState.SEEN))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*WidgetArtTest*" --rerun-tasks
```

Expected: FAIL — `Unresolved reference: gridNameFor`.

- [ ] **Step 3: Write `WidgetArt.kt`**

```kotlin
package com.lovebutton.app.widget

import androidx.annotation.DrawableRes
import com.lovebutton.app.R

/**
 * The one place the ladder's colours are written down.
 *
 * Spec §6.1: these are shared tokens. The pixel art, the app's focal heart and
 * the guide all read them from here, which is what lets the app be redesigned
 * freely without the guide starting to describe colours that no longer exist.
 * ARGB ints rather than Compose Color so Glance, Canvas and the guide can all
 * use them without one of them dragging in the others' dependencies.
 */
object PixelPalette {
    const val Border = 0xFFD1447E.toInt()
    const val Shine = 0xFFFFD9E8.toInt()
    const val Sent = 0xFFD81B60.toInt()
    const val Delivered = 0xFFFF6FA5.toInt()
    const val Gold = 0xFFFFC64B.toInt()
    const val Idle = 0xFFC98BA8.toInt()
    const val Failed = 0xFFA9A2AD.toInt()
}

/**
 * Which ASCII grid a message is drawn from.
 *
 * Falls back rather than throwing: Glance's per-widget store outlives
 * reinstalls, so an id written by a different build can come back, and throwing
 * inside a widget update leaves a blank tile no user action can fix.
 */
fun gridNameFor(msgId: Int): String = when (msgId) {
    2 -> "bubble"
    3 -> "paw"
    4 -> "call"
    else -> "heart"
}

/**
 * The drawable for one message in one state.
 *
 * Shared deliberately by `MessageWidget` and the guide. The guide's entire
 * purpose is explaining what the widget draws, so it must be structurally
 * incapable of showing something the widget does not.
 */
@DrawableRes
fun iconFor(msgId: Int, state: WidgetState): Int = when (state) {
    WidgetState.IDLE, WidgetState.FAILED -> when (msgId) {
        2 -> R.drawable.ic_bubble_outline
        3 -> R.drawable.ic_paw_outline
        4 -> R.drawable.ic_call_outline
        else -> R.drawable.ic_heart_outline
    }
    WidgetState.SENDING -> when (msgId) {
        2 -> R.drawable.ic_bubble_half
        3 -> R.drawable.ic_paw_half
        4 -> R.drawable.ic_call_half
        else -> R.drawable.ic_heart_half
    }
    WidgetState.SENT -> when (msgId) {
        2 -> R.drawable.ic_bubble_filled
        3 -> R.drawable.ic_paw_filled
        4 -> R.drawable.ic_call_filled
        else -> R.drawable.ic_heart_filled
    }
    WidgetState.DELIVERED -> when (msgId) {
        2 -> R.drawable.ic_bubble_delivered
        3 -> R.drawable.ic_paw_delivered
        4 -> R.drawable.ic_call_delivered
        else -> R.drawable.ic_heart_delivered
    }
    WidgetState.SEEN -> when (msgId) {
        2 -> R.drawable.ic_bubble_seen
        3 -> R.drawable.ic_paw_seen
        4 -> R.drawable.ic_call_seen
        else -> R.drawable.ic_heart_seen
    }
}

/**
 * The tint for the one state with no fill stage of its own, or null.
 *
 * Nothing else is tinted: a tint flattens every path to one colour and would
 * throw away the border and the shine that make the filled stage read as landed.
 */
fun tintColorFor(state: WidgetState): Int? =
    if (state == WidgetState.FAILED) PixelPalette.Failed else null
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*WidgetArtTest*" --rerun-tasks
```

Expected: 7 tests, 0 failures.

- [ ] **Step 5: Make `MessageWidget` use it**

In `app/src/main/java/com/lovebutton/app/widget/MessageWidget.kt`, replace the `icon` `when` block in `Tile` with:

```kotlin
        val icon = iconFor(msgId, state)
```

Replace `colorFilter = tintFor(state)` with:

```kotlin
                colorFilter = tintColorFor(state)?.let { ColorFilter.tint(ColorProvider(Color(it))) },
```

Then **delete** the now-unused private functions `tintFor`, `outlineIconFor`, `halfIconFor`, `filledIconFor`, `deliveredIconFor` and `seenIconFor` from that file, and drop the `com.lovebutton.app.R` import if nothing else in the file uses it.

- [ ] **Step 6: Verify the widget still builds and nothing else broke**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks
grep -c "outlineIconFor\|halfIconFor\|filledIconFor\|deliveredIconFor\|seenIconFor\|fun tintFor" app/src/main/java/com/lovebutton/app/widget/MessageWidget.kt
```

Expected: BUILD SUCCESSFUL, all tests pass, and the grep prints `0`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/widget/ app/src/test/java/com/lovebutton/app/WidgetArtTest.kt
git commit -m "refactor(widget): share the state-to-art mapping so the guide cannot drift"
```

---

### Task 3: The `CurrentSend` store

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/data/CurrentSend.kt`
- Test: `app/src/test/java/com/lovebutton/app/CurrentSendTest.kt`

**Interfaces:**
- Consumes: `WidgetState`.
- Produces:
  - `data class SendSnapshot(val sendId: String, val msgId: Int, val state: WidgetState, val at: Long)`
  - `class CurrentSend(context: Context)` with:
    - `val flow: Flow<SendSnapshot?>`
    - `suspend fun start(sendId: String, msgId: Int, now: Long = System.currentTimeMillis())`
    - `suspend fun update(sendId: String, state: WidgetState)` — **no-op unless `sendId` matches the stored one**
    - `suspend fun current(): SendSnapshot?`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/lovebutton/app/CurrentSendTest.kt`:

```kotlin
package com.lovebutton.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lovebutton.app.data.CurrentSend
import com.lovebutton.app.widget.WidgetState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CurrentSendTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

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
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*CurrentSendTest*" --rerun-tasks
```

Expected: FAIL — `Unresolved reference: CurrentSend`.

- [ ] **Step 3: Write `CurrentSend.kt`**

```kotlin
package com.lovebutton.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.fromName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.currentSendStore by preferencesDataStore(name = "current_send")

/** One send, as the app screen needs to see it. */
data class SendSnapshot(
    val sendId: String,
    val msgId: Int,
    val state: WidgetState,
    val at: Long,
)

/**
 * The single most recent send, whatever started it.
 *
 * The app screen has no widget id to be addressed by, so `setWidgetState` can
 * never reach it — an app-originated send passes INVALID_APPWIDGET_ID and that
 * function returns immediately. This store is the channel that replaces it.
 *
 * Persisted rather than held in memory because MIUI kills this process as a
 * matter of routine, and because the screen must show how the last send ended
 * on a cold open. One mechanism serves both; an in-memory StateFlow would need
 * a second one for the cold open anyway.
 *
 * Exactly one record. This is not a log, and the decision that the server keeps
 * no history (spec §5.1) is not reopened by it.
 */
class CurrentSend(private val context: Context) {

    private object Keys {
        val SEND_ID = stringPreferencesKey("send_id")
        val MSG_ID = intPreferencesKey("msg_id")
        val STATE = stringPreferencesKey("state")
        val AT = longPreferencesKey("at")
    }

    val flow: Flow<SendSnapshot?> = context.currentSendStore.data.map { prefs ->
        val sendId = prefs[Keys.SEND_ID] ?: return@map null
        val msgId = prefs[Keys.MSG_ID] ?: return@map null
        val at = prefs[Keys.AT] ?: return@map null
        SendSnapshot(sendId, msgId, fromName(prefs[Keys.STATE]), at)
    }

    suspend fun current(): SendSnapshot? = flow.first()

    /** Replaces whatever was there. A new send is a new subject, not an update. */
    suspend fun start(sendId: String, msgId: Int, now: Long = System.currentTimeMillis()) {
        context.currentSendStore.edit { prefs ->
            prefs[Keys.SEND_ID] = sendId
            prefs[Keys.MSG_ID] = msgId
            prefs[Keys.STATE] = WidgetState.SENDING.name
            prefs[Keys.AT] = now
        }
    }

    /**
     * Advances the stored send, and only if it IS the stored send.
     *
     * Receipts arrive by push and can land after the user has sent something
     * else. Without this guard an old `seen` would light the focal area for a
     * message that is no longer on screen — the read and the compare happen
     * inside one edit block so a concurrent writer cannot land between them.
     */
    suspend fun update(sendId: String, state: WidgetState) {
        context.currentSendStore.edit { prefs ->
            if (prefs[Keys.SEND_ID] == sendId) {
                prefs[Keys.STATE] = state.name
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*CurrentSendTest*" --rerun-tasks
```

Expected: 6 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/data/CurrentSend.kt app/src/test/java/com/lovebutton/app/CurrentSendTest.kt
git commit -m "feat(app): add the CurrentSend store the app screen observes"
```

---

### Task 4: Wire `CurrentSend` into the send and receipt paths

**Files:**
- Modify: `app/src/main/java/com/lovebutton/app/work/SendWorker.kt:40-72`
- Modify: `app/src/main/java/com/lovebutton/app/push/PushService.kt:49-72`

**Interfaces:**
- Consumes: `CurrentSend.start`, `CurrentSend.update` from Task 3.
- Produces: nothing new. Behaviour only.

- [ ] **Step 1: Record the send in `SendWorker`**

In `doWork()`, immediately after `mintedSendId = sendId` and the existing `pending.remember(...)` block, add:

```kotlin
        // Written for EVERY send, widget or app. Spec §4.2: tapping a widget and
        // then opening the app shows that send laddering, and this is what makes
        // it free rather than a second code path.
        val currentSend = CurrentSend(applicationContext)
        currentSend.start(sendId, msgId)
```

In the `try` block, after the successful `send(...)` call and *before* `settle(...)`:

```kotlin
            currentSend.update(sendId, WidgetState.SENT)
```

In the `catch` block, before `settle(...)`:

```kotlin
            currentSend.update(sendId, WidgetState.FAILED)
```

Add the import `com.lovebutton.app.data.CurrentSend`.

- [ ] **Step 2: Update it from `PushService`**

In the `"receipt"` branch's `CoroutineScope(...).launch { ... }` block, immediately after `val state = ...` is resolved and *before* the `pending.widgetFor(sendId) ?: return@launch` line, add:

```kotlin
                    // Before the widget lookup, deliberately: the widget mapping
                    // expires after 20 seconds, but the app screen keeps the last
                    // outcome (spec §4.3). Returning early on an expired widget
                    // must not also skip the app.
                    CurrentSend(applicationContext).update(sendId, state)
```

Add the import `com.lovebutton.app.data.CurrentSend`.

- [ ] **Step 3: Verify the ordering, which is the whole point of the step**

```bash
grep -n "CurrentSend(applicationContext).update\|pending.widgetFor" app/src/main/java/com/lovebutton/app/push/PushService.kt
```

Expected: the `CurrentSend` line's number is **lower** than the `widgetFor` line's. If it is not, the app screen silently stops updating 20 seconds after a send, and no test will catch it.

- [ ] **Step 4: Build and run everything**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks
```

Expected: BUILD SUCCESSFUL, all existing tests still pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/work/SendWorker.kt app/src/main/java/com/lovebutton/app/push/PushService.kt
git commit -m "feat(app): report send state to the app screen as well as the widget"
```

---

### Task 5: The Sticker Book theme

**Files:**
- Modify: `app/src/main/java/com/lovebutton/app/ui/Theme.kt`
- Create: `app/src/main/res/font/` (font files + family XML)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `object Sticker { val Ground: Color; val Ink: Color; val Surface: Color; val Mint: Color; val Blossom: Color; val Butter: Color }`
  - `val StickerShadow: Dp` (2.dp) and `val StickerKeyline: Dp` (2.dp)
  - `fun stickerColorFor(msgId: Int): Color`
  - `LoveButtonTheme` keeps its existing signature: `@Composable fun LoveButtonTheme(content: @Composable () -> Unit)`

- [ ] **Step 1: Fetch the two typefaces**

Download the **static** TTFs (not the variable ones — Compose's variable-font support on minSdk 26 is not worth the risk here) from the Google Fonts repository:

```bash
cd /home/killua/Projects/LovieApp
mkdir -p app/src/main/res/font
curl -fL -o app/src/main/res/font/fredoka_semibold.ttf \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/fredoka/static/Fredoka-SemiBold.ttf"
curl -fL -o app/src/main/res/font/quicksand_medium.ttf \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/quicksand/static/Quicksand-Medium.ttf"
curl -fL -o app/src/main/res/font/quicksand_bold.ttf \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/quicksand/static/Quicksand-Bold.ttf"
ls -la app/src/main/res/font/
```

Each file must be **more than 20 KB**. If any is a few hundred bytes it is an HTML error page, not a font — the path changed upstream. In that case find the correct path by browsing `https://github.com/google/fonts/tree/main/ofl/fredoka` and adjust; do not proceed with a broken file, because Android falls back to the system font **silently** and the app will simply look wrong with no error anywhere.

```bash
find app/src/main/res/font -name '*.ttf' -size -20k | grep . && echo "BROKEN FONT FILE — stop" || echo "fonts look real"
```

- [ ] **Step 2: Rewrite `Theme.kt`**

```kotlin
package com.lovebutton.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lovebutton.app.R

/**
 * "Sticker Book" — one theme, not two skins.
 *
 * Every element is a sticker: a hard ink keyline, flat colour, and a shallow
 * offset shadow. The shadow is deliberately small; a deeper one was tried on
 * hardware and read as too poppy.
 *
 * These are the app's chrome only. The ladder's three state colours live in
 * `PixelPalette` and are not repeated here — if they were, the guide could
 * eventually describe a colour the widget no longer draws.
 */
object Sticker {
    val Ground = Color(0xFFFFF0F5)
    val Ink = Color(0xFF2E2430)
    val Surface = Color(0xFFFFFFFF)
    val Mint = Color(0xFF7FD6C2)
    val Blossom = Color(0xFFFFB8CE)
    val Butter = Color(0xFFFFCF7A)
}

/** Shallow on purpose. See the note above. */
val StickerShadow = 2.dp
val StickerKeyline = 2.dp

/** Each message keeps one colour, so the four are told apart by hue before text. */
fun stickerColorFor(msgId: Int): Color = when (msgId) {
    2 -> Sticker.Mint
    3 -> Sticker.Blossom
    4 -> Sticker.Butter
    else -> Sticker.Surface
}

private val Fredoka = FontFamily(Font(R.font.fredoka_semibold, FontWeight.SemiBold))
private val Quicksand = FontFamily(
    Font(R.font.quicksand_medium, FontWeight.Medium),
    Font(R.font.quicksand_bold, FontWeight.Bold),
)

private val StickerType = Typography(
    headlineMedium = TextStyle(fontFamily = Fredoka, fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = Fredoka, fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Quicksand, fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontFamily = Quicksand, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Quicksand, fontSize = 12.sp, fontWeight = FontWeight.Bold),
)

private val Scheme = lightColorScheme(
    primary = Sticker.Ink,
    onPrimary = Sticker.Surface,
    background = Sticker.Ground,
    onBackground = Sticker.Ink,
    surface = Sticker.Surface,
    onSurface = Sticker.Ink,
)

@Composable
fun LoveButtonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = StickerType, content = content)
}
```

- [ ] **Step 3: Write the sticker-colour test**

`app/src/test/java/com/lovebutton/app/ThemeTest.kt`:

```kotlin
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
```

- [ ] **Step 4: Build and test**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks
```

Expected: BUILD SUCCESSFUL, 3 new tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/font app/src/main/java/com/lovebutton/app/ui/Theme.kt app/src/test/java/com/lovebutton/app/ThemeTest.kt
git commit -m "feat(ui): the Sticker Book theme, with bundled Fredoka and Quicksand"
```

---

### Task 6: Draw a pixel grid on a Canvas

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/ui/PixelArt.kt`
- Test: `app/src/test/java/com/lovebutton/app/PixelArtTest.kt`

**Interfaces:**
- Consumes: `PixelGrids.GRIDS` (Task 1), `PixelPalette`, `gridNameFor`, `WidgetState` (Task 2).
- Produces:
  - `fun isBorderCell(grid: List<String>, row: Int, col: Int): Boolean`
  - `fun cellColor(grid: List<String>, row: Int, col: Int, state: WidgetState): Int?` — ARGB, null = draw nothing
  - `@Composable fun PixelIcon(msgId: Int, state: WidgetState, modifier: Modifier = Modifier)`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/lovebutton/app/PixelArtTest.kt`:

```kotlin
package com.lovebutton.app

import com.lovebutton.app.ui.cellColor
import com.lovebutton.app.ui.isBorderCell
import com.lovebutton.app.widget.PixelPalette
import com.lovebutton.app.widget.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelArtTest {

    // A 5x5 block with a solid interior, small enough to reason about by hand.
    private val block = listOf(
        ".....",
        ".XXX.",
        ".XXX.",
        ".XXX.",
        ".....",
    )

    @Test
    fun `an empty cell draws nothing in every state`() {
        WidgetState.entries.forEach { state ->
            assertNull("$state painted an empty cell", cellColor(block, 0, 0, state))
        }
    }

    @Test
    fun `a solid cell touching empty space is border`() {
        assertTrue(isBorderCell(block, 1, 1))
        assertTrue(isBorderCell(block, 1, 2))
    }

    @Test
    fun `a solid cell surrounded by solid cells is interior`() {
        assertFalse(isBorderCell(block, 2, 2))
    }

    @Test
    fun `a solid cell on the grid edge is border`() {
        val full = listOf("XX", "XX")
        assertTrue(isBorderCell(full, 0, 0))
        assertTrue(isBorderCell(full, 1, 1))
    }

    @Test
    fun `idle draws only the outline`() {
        assertEquals(PixelPalette.Idle, cellColor(block, 1, 1, WidgetState.IDLE))
        assertNull("idle must leave the interior empty", cellColor(block, 2, 2, WidgetState.IDLE))
    }

    @Test
    fun `failed draws the outline in grey`() {
        assertEquals(PixelPalette.Failed, cellColor(block, 1, 1, WidgetState.FAILED))
        assertNull(cellColor(block, 2, 2, WidgetState.FAILED))
    }

    @Test
    fun `sent fills crimson inside a rose border`() {
        assertEquals(PixelPalette.Sent, cellColor(block, 2, 2, WidgetState.SENT))
        assertEquals(PixelPalette.Border, cellColor(block, 1, 1, WidgetState.SENT))
    }

    @Test
    fun `delivered fills pink inside a rose border`() {
        assertEquals(PixelPalette.Delivered, cellColor(block, 2, 2, WidgetState.DELIVERED))
        assertEquals(PixelPalette.Border, cellColor(block, 1, 1, WidgetState.DELIVERED))
    }

    @Test
    fun `seen keeps the pink fill and gilds the border`() {
        // Seen is delivered plus gold. If the fill also changed, the two states
        // would differ in two ways and the guide would have to explain both.
        assertEquals(PixelPalette.Delivered, cellColor(block, 2, 2, WidgetState.SEEN))
        assertEquals(PixelPalette.Gold, cellColor(block, 1, 1, WidgetState.SEEN))
    }

    @Test
    fun `a shine cell is painted in the shine colour whatever the state`() {
        val shiny = listOf(".....", ".XsX.", ".XXX.", ".XXX.", ".....")
        listOf(WidgetState.SENT, WidgetState.DELIVERED, WidgetState.SEEN).forEach {
            assertEquals("$it lost the shine", PixelPalette.Shine, cellColor(shiny, 1, 2, it))
        }
    }

    @Test
    fun `a hole draws nothing`() {
        // 'o' is cut out of the filled variant — it is how the bubble gets a face.
        val holed = listOf(".....", ".XXX.", ".XoX.", ".XXX.", ".....")
        assertNull(cellColor(holed, 2, 2, WidgetState.SENT))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*PixelArtTest*" --rerun-tasks
```

Expected: FAIL — `Unresolved reference: cellColor`.

- [ ] **Step 3: Write `PixelArt.kt`**

```kotlin
package com.lovebutton.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.lovebutton.app.widget.PixelGrids
import com.lovebutton.app.widget.PixelPalette
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.gridNameFor

private fun isSolid(ch: Char) = ch == 'X' || ch == 's'

/**
 * Whether a solid cell sits on the silhouette's edge.
 *
 * Derived exactly as the generator derives it — a solid cell touching an empty
 * cell, a hole, or the grid border. Deriving rather than authoring is what stops
 * the outline and the fill from disagreeing; the same rule has to hold here or
 * the Canvas heart and the VectorDrawable heart would have different outlines.
 */
fun isBorderCell(grid: List<String>, row: Int, col: Int): Boolean {
    if (row == 0 || col == 0 || row == grid.size - 1 || col == grid[row].length - 1) return true
    val neighbours = listOf(row - 1 to col, row + 1 to col, row to col - 1, row to col + 1)
    return neighbours.any { (r, c) ->
        val ch = grid[r][c]
        ch == '.' || ch == 'o'
    }
}

/**
 * The colour one cell is painted in one state, or null to leave it empty.
 *
 * This is the Canvas equivalent of what `pixel_icons.py` bakes into each
 * VectorDrawable, and it must agree with it cell for cell.
 */
fun cellColor(grid: List<String>, row: Int, col: Int, state: WidgetState): Int? {
    val ch = grid[row][col]
    if (!isSolid(ch)) return null

    val border = isBorderCell(grid, row, col)
    return when (state) {
        WidgetState.IDLE -> if (border) PixelPalette.Idle else null
        WidgetState.FAILED -> if (border) PixelPalette.Failed else null
        else -> when {
            ch == 's' -> PixelPalette.Shine
            state == WidgetState.SEEN -> if (border) PixelPalette.Gold else PixelPalette.Delivered
            state == WidgetState.DELIVERED -> if (border) PixelPalette.Border else PixelPalette.Delivered
            else -> if (border) PixelPalette.Border else PixelPalette.Sent
        }
    }
}

/**
 * One message's icon, drawn cell by cell at whatever size it is given.
 *
 * Canvas rather than the VectorDrawable because a VectorDrawable cannot be lit
 * one cell at a time, and lighting cells one at a time is the entire animation
 * (see PixelLadder.kt). The grid comes from the generator, so this is the same
 * artwork rather than a second drawing of it.
 */
@Composable
fun PixelIcon(msgId: Int, state: WidgetState, modifier: Modifier = Modifier) {
    val grid = PixelGrids.GRIDS[gridNameFor(msgId)] ?: return
    Canvas(modifier = modifier) {
        val cell = minOf(size.width / grid[0].length, size.height / grid.size)
        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, _ ->
                cellColor(grid, r, c, state)?.let { argb ->
                    drawRect(
                        color = Color(argb),
                        topLeft = Offset(c * cell, r * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*PixelArtTest*" --rerun-tasks
```

Expected: 11 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/ui/PixelArt.kt app/src/test/java/com/lovebutton/app/PixelArtTest.kt
git commit -m "feat(ui): draw the pixel icons on a Canvas from the generated grids"
```

---

### Task 7: The six animations

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/ui/PixelLadder.kt`
- Test: `app/src/test/java/com/lovebutton/app/PixelLadderTest.kt`

**Interfaces:**
- Consumes: `isBorderCell`, `cellColor` (Task 6), `PixelPalette`, `WidgetState`.
- Produces:
  - `fun fillRowsVisible(grid: List<String>, progress: Float): Int` — how many rows from the bottom are filled
  - `fun rippleReached(grid: List<String>, row: Int, col: Int, progress: Float): Boolean`
  - `fun borderRingOrder(grid: List<String>): List<Pair<Int, Int>>` — border cells ordered around the shape
  - `@Composable fun AnimatedPixelIcon(msgId: Int, state: WidgetState, modifier: Modifier = Modifier)`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/lovebutton/app/PixelLadderTest.kt`:

```kotlin
package com.lovebutton.app

import com.lovebutton.app.ui.borderRingOrder
import com.lovebutton.app.ui.fillRowsVisible
import com.lovebutton.app.ui.rippleReached
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelLadderTest {

    private val block = listOf(
        ".....",
        ".XXX.",
        ".XXX.",
        ".XXX.",
        ".....",
    )

    @Test
    fun `the fill starts empty and ends full`() {
        assertEquals(0, fillRowsVisible(block, 0f))
        assertEquals(block.size, fillRowsVisible(block, 1f))
    }

    @Test
    fun `the fill rises monotonically`() {
        // It must never go backwards mid-send: a fill that dips reads as the
        // send failing and recovering, which is not what happened.
        var previous = -1
        var p = 0f
        while (p <= 1f) {
            val rows = fillRowsVisible(block, p)
            assertTrue("fill went backwards at $p", rows >= previous)
            previous = rows
            p += 0.05f
        }
    }

    @Test
    fun `progress outside zero to one is clamped`() {
        assertEquals(0, fillRowsVisible(block, -3f))
        assertEquals(block.size, fillRowsVisible(block, 9f))
    }

    @Test
    fun `the ripple starts at the centre and finishes everywhere`() {
        assertTrue("the centre must go first", rippleReached(block, 2, 2, 0.05f))
        for (r in block.indices) for (c in block[r].indices) {
            assertTrue("everything must be reached by the end", rippleReached(block, r, c, 1f))
        }
    }

    @Test
    fun `the ripple has not reached the far corner immediately`() {
        assertFalse(rippleReached(block, 0, 0, 0.05f))
    }

    @Test
    fun `the gold ring visits every border cell exactly once`() {
        val ring = borderRingOrder(block)
        val expected = mutableSetOf<Pair<Int, Int>>()
        for (r in block.indices) for (c in block[r].indices) {
            val ch = block[r][c]
            if ((ch == 'X' || ch == 's') && com.lovebutton.app.ui.isBorderCell(block, r, c)) {
                expected.add(r to c)
            }
        }
        assertEquals("the ring must not repeat a cell", ring.size, ring.toSet().size)
        assertEquals(expected, ring.toSet())
    }

    @Test
    fun `the gold ring is a loop, each step adjacent to the last`() {
        // A ring that jumps across the shape reads as flickering, not as light
        // travelling around an outline.
        val ring = borderRingOrder(block)
        ring.zipWithNext().forEach { (a, b) ->
            val dr = kotlin.math.abs(a.first - b.first)
            val dc = kotlin.math.abs(a.second - b.second)
            assertTrue("jump from $a to $b", dr <= 1 && dc <= 1)
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*PixelLadderTest*" --rerun-tasks
```

Expected: FAIL — `Unresolved reference: fillRowsVisible`.

- [ ] **Step 3: Write `PixelLadder.kt`**

```kotlin
package com.lovebutton.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.lovebutton.app.widget.PixelGrids
import com.lovebutton.app.widget.PixelPalette
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.gridNameFor
import kotlin.math.abs
import kotlin.math.hypot

private fun isSolid(ch: Char) = ch == 'X' || ch == 's'

/** How many rows, counted from the bottom, the rising fill has covered. */
fun fillRowsVisible(grid: List<String>, progress: Float): Int =
    (progress.coerceIn(0f, 1f) * grid.size).toInt().coerceIn(0, grid.size)

/** Whether the delivered ripple, expanding from the centre, has reached a cell. */
fun rippleReached(grid: List<String>, row: Int, col: Int, progress: Float): Boolean {
    val cr = (grid.size - 1) / 2f
    val cc = (grid[0].length - 1) / 2f
    val maxRadius = hypot(grid.size.toFloat(), grid[0].length.toFloat())
    return hypot(row - cr, col - cc) <= progress.coerceIn(0f, 1f) * maxRadius
}

/**
 * The border cells ordered as a walk around the silhouette.
 *
 * Sorting by angle alone leaves jumps wherever the outline is more than one
 * cell thick, and a jump reads as flicker rather than as light running around
 * the edge. So this walks greedily to the nearest unvisited neighbour instead,
 * starting from the topmost-leftmost border cell.
 */
fun borderRingOrder(grid: List<String>): List<Pair<Int, Int>> {
    val cells = mutableListOf<Pair<Int, Int>>()
    grid.forEachIndexed { r, row ->
        row.forEachIndexed { c, ch ->
            if (isSolid(ch) && isBorderCell(grid, r, c)) cells.add(r to c)
        }
    }
    if (cells.isEmpty()) return emptyList()

    val remaining = cells.toMutableList()
    val ordered = mutableListOf(remaining.removeAt(0))
    while (remaining.isNotEmpty()) {
        val (lr, lc) = ordered.last()
        // Chebyshev distance: diagonal neighbours count as adjacent, which is
        // what the "is a loop" test asserts.
        val next = remaining.minByOrNull { (r, c) -> maxOf(abs(r - lr), abs(c - lc)) }!!
        ordered.add(next)
        remaining.remove(next)
    }
    return ordered
}

/**
 * The focal heart, animated per pixel.
 *
 * The widget swaps between five finished pictures because RemoteViews can do
 * little else. This is Compose, so each state gets motion that says what
 * happened: the fill rises while the request is in flight, the buzz ripples
 * outward when her phone goes off, and gold runs around the outline when she
 * looks. `seen` is the only state with a flourish, because it is the only one
 * that is the point of the app.
 */
@Composable
fun AnimatedPixelIcon(msgId: Int, state: WidgetState, modifier: Modifier = Modifier) {
    val grid = PixelGrids.GRIDS[gridNameFor(msgId)] ?: return
    val context = LocalContext.current
    val animationsOn = remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }

    val transition = rememberInfiniteTransition(label = "ladder")
    val loop by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "loop",
    )

    // One-shot progress for the states that play once and settle.
    var shot by remember(state) { mutableFloatStateOf(0f) }
    LaunchedEffect(state) {
        shot = 0f
        val steps = 24
        repeat(steps) {
            kotlinx.coroutines.delay(28)
            shot = (it + 1) / steps.toFloat()
        }
        shot = 1f
    }

    // With animations off, every state renders as its plain finished picture.
    val progress = if (!animationsOn) 1f else when (state) {
        WidgetState.SENDING -> loop
        else -> shot
    }
    val ring = remember(grid) { borderRingOrder(grid) }

    Canvas(modifier = modifier) {
        val cell = minOf(size.width / grid[0].length, size.height / grid.size)
        val goldCount = (ring.size * progress).toInt()
        val gilded = if (state == WidgetState.SEEN) ring.take(goldCount).toHashSet() else emptySet()

        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                val argb: Int? = when {
                    !isSolid(ch) -> null

                    state == WidgetState.SENDING -> {
                        val filledFrom = grid.size - fillRowsVisible(grid, progress)
                        if (r >= filledFrom) cellColor(grid, r, c, WidgetState.SENT)
                        else cellColor(grid, r, c, WidgetState.IDLE)
                    }

                    state == WidgetState.DELIVERED ->
                        if (rippleReached(grid, r, c, progress)) cellColor(grid, r, c, WidgetState.DELIVERED)
                        else cellColor(grid, r, c, WidgetState.SENT)

                    state == WidgetState.SEEN -> when {
                        ch == 's' -> PixelPalette.Shine
                        (r to c) in gilded -> PixelPalette.Gold
                        else -> cellColor(grid, r, c, WidgetState.DELIVERED)
                    }

                    else -> cellColor(grid, r, c, state)
                }

                argb?.let {
                    drawRect(
                        color = Color(it),
                        topLeft = Offset(c * cell, r * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*PixelLadderTest*" --rerun-tasks
```

Expected: 7 tests, 0 failures. If `the gold ring is a loop` fails, the greedy walk is jumping — fix `borderRingOrder`, not the test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/ui/PixelLadder.kt app/src/test/java/com/lovebutton/app/PixelLadderTest.kt
git commit -m "feat(ui): animate the focal heart per pixel through the ladder"
```

---

### Task 8: The words

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/ui/StateCopy.kt`
- Test: `app/src/test/java/com/lovebutton/app/StateCopyTest.kt`

**Interfaces:**
- Consumes: `WidgetState`.
- Produces:
  - `fun focalLine(state: WidgetState, partnerName: String): String`
  - `fun guideLine(state: WidgetState, partnerName: String): String`
  - `fun coldOpenLine(partnerName: String, messageText: String, ageMillis: Long): String`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/lovebutton/app/StateCopyTest.kt`:

```kotlin
package com.lovebutton.app

import com.lovebutton.app.ui.coldOpenLine
import com.lovebutton.app.ui.focalLine
import com.lovebutton.app.ui.guideLine
import com.lovebutton.app.widget.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateCopyTest {

    @Test
    fun `every state has a focal line and a guide line`() {
        WidgetState.entries.forEach { state ->
            assertTrue("$state has no focal line", focalLine(state, "Wifey").isNotBlank())
            assertTrue("$state has no guide line", guideLine(state, "Wifey").isNotBlank())
        }
    }

    @Test
    fun `no line leaves a placeholder unsubstituted`() {
        // A stray {partner} would ship as literal braces on screen.
        WidgetState.entries.forEach { state ->
            listOf(focalLine(state, "Wifey"), guideLine(state, "Wifey")).forEach { line ->
                assertFalse("unsubstituted placeholder in: $line", line.contains("{"))
                assertFalse("unsubstituted placeholder in: $line", line.contains("}"))
            }
        }
        assertFalse(coldOpenLine("Wifey", "Miss you", 0L).contains("{"))
    }

    @Test
    fun `the focal lines are the warm plain ones`() {
        assertEquals("sending…", focalLine(WidgetState.SENDING, "Wifey"))
        assertEquals("on its way to Wifey", focalLine(WidgetState.SENT, "Wifey"))
        assertEquals("it buzzed her phone", focalLine(WidgetState.DELIVERED, "Wifey"))
        assertEquals("Wifey saw it ♡", focalLine(WidgetState.SEEN, "Wifey"))
        assertEquals("didn't get through :(", focalLine(WidgetState.FAILED, "Wifey"))
    }

    @Test
    fun `the guide lines are the playful ones`() {
        assertEquals("click the button!", guideLine(WidgetState.IDLE, "Wifey"))
        assertEquals("on its way to Wifey 0o0", guideLine(WidgetState.SENDING, "Wifey"))
        assertEquals("traveling in the interwebs (• ε •)", guideLine(WidgetState.SENT, "Wifey"))
        assertEquals("it buzzed Wifey's phone :3", guideLine(WidgetState.DELIVERED, "Wifey"))
        assertEquals("Wifey looked at it (>^o^)>", guideLine(WidgetState.SEEN, "Wifey"))
        assertEquals("didn't get through （◞‸◟）", guideLine(WidgetState.FAILED, "Wifey"))
    }

    @Test
    fun `the guide names the partner and the focal lines mostly do not`() {
        // Deliberate: the guide is read a handful of times ever, the focal lines
        // hundreds, and the loud ones wear out.
        assertTrue(guideLine(WidgetState.DELIVERED, "Hubby").contains("Hubby"))
        assertFalse(focalLine(WidgetState.DELIVERED, "Hubby").contains("Hubby"))
    }

    @Test
    fun `the cold open line names the message and its age`() {
        val line = coldOpenLine("Wifey", "Miss you", 2 * 60 * 60 * 1000L)
        assertTrue(line, line.contains("Wifey"))
        assertTrue(line, line.contains("Miss you"))
        assertTrue(line, line.contains("2h"))
    }

    @Test
    fun `ages read sensibly across the ranges`() {
        assertTrue(coldOpenLine("W", "m", 5_000L).contains("just now"))
        assertTrue(coldOpenLine("W", "m", 7 * 60_000L).contains("7m"))
        assertTrue(coldOpenLine("W", "m", 3 * 3_600_000L).contains("3h"))
        assertTrue(coldOpenLine("W", "m", 2 * 86_400_000L).contains("2d"))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*StateCopyTest*" --rerun-tasks
```

Expected: FAIL — `Unresolved reference: focalLine`.

- [ ] **Step 3: Write `StateCopy.kt`**

```kotlin
package com.lovebutton.app.ui

import com.lovebutton.app.widget.WidgetState

/**
 * The words for each state, in two registers.
 *
 * The guide is read a handful of times ever, so it is playful. The focal lines
 * are read hundreds of times, so they are warm and plain — loud copy wears out,
 * and this is the copy you will still be reading in a year. The split is
 * deliberate (spec §5); do not harmonise them.
 */
fun focalLine(state: WidgetState, partnerName: String): String = when (state) {
    WidgetState.IDLE -> "tap one below"
    WidgetState.SENDING -> "sending…"
    WidgetState.SENT -> "on its way to $partnerName"
    WidgetState.DELIVERED -> "it buzzed her phone"
    WidgetState.SEEN -> "$partnerName saw it ♡"
    WidgetState.FAILED -> "didn't get through :("
}

fun guideLine(state: WidgetState, partnerName: String): String = when (state) {
    WidgetState.IDLE -> "click the button!"
    WidgetState.SENDING -> "on its way to $partnerName 0o0"
    WidgetState.SENT -> "traveling in the interwebs (• ε •)"
    WidgetState.DELIVERED -> "it buzzed $partnerName's phone :3"
    WidgetState.SEEN -> "$partnerName looked at it (>^o^)>"
    WidgetState.FAILED -> "didn't get through （◞‸◟）"
}

/**
 * What the focal area says on a cold open, when nothing was just sent.
 *
 * The app remembers the last send where the widget forgets it (spec §4.3),
 * because remembering is the app screen's job and a permanently lit button on
 * the home screen would only be noise.
 */
fun coldOpenLine(partnerName: String, messageText: String, ageMillis: Long): String {
    val age = when {
        ageMillis < 60_000L -> "just now"
        ageMillis < 3_600_000L -> "${ageMillis / 60_000L}m ago"
        ageMillis < 86_400_000L -> "${ageMillis / 3_600_000L}h ago"
        else -> "${ageMillis / 86_400_000L}d ago"
    }
    return "$partnerName saw your \"$messageText\" · $age"
}
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*StateCopyTest*" --rerun-tasks
```

Expected: 7 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/ui/StateCopy.kt app/src/test/java/com/lovebutton/app/StateCopyTest.kt
git commit -m "feat(ui): the state copy, in its two deliberate registers"
```

---

### Task 9: The Home screen

**Files:**
- Modify: `app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt` (full rewrite)
- Modify: `app/src/main/java/com/lovebutton/app/MainActivity.kt:86-96`
- Create: `app/src/main/res/drawable/panda_love.xml`, `panda_thinking.xml`, `panda_miss.xml`, `panda_call.xml` (placeholders)

**Interfaces:**
- Consumes: `Sticker`, `stickerColorFor`, `StickerShadow`, `StickerKeyline` (Task 5); `AnimatedPixelIcon` (Task 7); `focalLine`, `coldOpenLine` (Task 8); `CurrentSend`, `SendSnapshot` (Task 3); `MESSAGES`, `messageForId` (existing).
- Produces: `@Composable fun HomeScreen(partnerName: String, onOpenSetup: () -> Unit, onOpenGuide: () -> Unit)`

- [ ] **Step 1: Create the four panda placeholders**

These exist so the screen can be built and reviewed before the real art arrives. Create four files with this content, changing only `android:fillColor` so they are visibly distinct: `panda_love.xml` `#FFB8CE`, `panda_thinking.xml` `#7FD6C2`, `panda_miss.xml` `#FFCF7A`, `panda_call.xml` `#C98BA8`.

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="40dp"
    android:height="40dp"
    android:viewportWidth="40"
    android:viewportHeight="40">
    <!-- PLACEHOLDER. Replaced by the supplied panda art (spec §7.1).
         When the real file arrives: DELETE this .xml and drop in
         panda_love.webp. The resource name is unchanged, so no code moves. -->
    <path android:fillColor="#FFB8CE"
        android:pathData="M20,4 A16,16 0 1,1 19.9,4 Z" />
    <path android:fillColor="#2E2430"
        android:pathData="M14,17 h3 v3 h-3 z M23,17 h3 v3 h-3 z M17,25 h6 v2 h-6 z" />
</vector>
```

- [ ] **Step 2: Rewrite `HomeScreen.kt`**

```kotlin
package com.lovebutton.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lovebutton.app.R
import com.lovebutton.app.data.CurrentSend
import com.lovebutton.app.data.MESSAGES
import com.lovebutton.app.data.messageForId
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.work.SendWorker

private fun pandaFor(msgId: Int): Int = when (msgId) {
    2 -> R.drawable.panda_thinking
    3 -> R.drawable.panda_miss
    4 -> R.drawable.panda_call
    else -> R.drawable.panda_love
}

/**
 * A sticker: flat colour, hard keyline, and a shallow offset shadow drawn as a
 * second box behind the first. Compose's elevation shadow is soft and would not
 * read as a sticker at all.
 */
@Composable
private fun Sticker(
    fill: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    radius: Int = 16,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius.dp)
    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = StickerShadow, y = StickerShadow)
                .clip(shape)
                .background(com.lovebutton.app.ui.Sticker.Ink)
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .border(StickerKeyline, com.lovebutton.app.ui.Sticker.Ink, shape)
        ) { content() }
    }
}

@Composable
fun HomeScreen(
    partnerName: String,
    onOpenSetup: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val store = remember { CurrentSend(context) }
    val snapshot by store.flow.collectAsState(initial = null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.lovebutton.app.ui.Sticker.Ground)
            .padding(20.dp),
    ) {
        // ---- focal area ----
        Sticker(fill = com.lovebutton.app.ui.Sticker.Surface, radius = 22, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val state = snapshot?.state ?: WidgetState.IDLE
                AnimatedPixelIcon(
                    msgId = snapshot?.msgId ?: 1,
                    state = state,
                    modifier = Modifier.size(132.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = focalLine(state, partnerName),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                // Only once the send has settled: while it is in flight the
                // state line already says everything, and two lines competing
                // makes the moment busy rather than warm.
                val settled = snapshot?.takeIf { it.state == WidgetState.SEEN }
                if (settled != null) {
                    Text(
                        text = coldOpenLine(
                            partnerName,
                            messageForId(settled.msgId)?.text ?: "",
                            System.currentTimeMillis() - settled.at,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.size(18.dp))

        // ---- the four messages ----
        MESSAGES.forEach { message ->
            Sticker(
                fill = stickerColorFor(message.id),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clickable {
                        // Haptic first, before the network call even starts. It
                        // lands immediately, which is what makes the tap feel
                        // responsive regardless of how long the request takes.
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        SendWorker.enqueue(context, message.id)
                    },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(pandaFor(message.id)),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(Modifier.size(11.dp))
                    Text(message.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            listOf("What the colours mean" to onOpenGuide, "Delivery setup" to onOpenSetup)
                .forEach { (label, action) ->
                    Sticker(
                        fill = com.lovebutton.app.ui.Sticker.Surface,
                        radius = 13,
                        modifier = Modifier.weight(1f).clickable { action() },
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
                        )
                    }
                }
        }
    }
}
```

- [ ] **Step 3: Route the guide from `MainActivity`**

In `MainActivity.kt`'s `Root()`, add a second screen flag beside `showSetup`:

```kotlin
    var showGuide by remember { mutableStateOf(false) }
```

and replace the `else ->` branch with:

```kotlin
        showGuide -> GuideScreen(partnerName = enrolment!!.partnerName, onDone = { showGuide = false })
        else -> HomeScreen(
            partnerName = enrolment!!.partnerName,
            onOpenSetup = { showSetup = true },
            onOpenGuide = { showGuide = true },
        )
```

Add the import `com.lovebutton.app.ui.GuideScreen`. **This will not compile until Task 10 exists** — do Task 10 before building, or stub `GuideScreen` and complete it there.

- [ ] **Step 4: Build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL once Task 10's `GuideScreen` exists.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt app/src/main/java/com/lovebutton/app/MainActivity.kt app/src/main/res/drawable/panda_*.xml
git commit -m "feat(ui): the Sticker Book home screen with the live ladder"
```

---

### Task 10: The guide

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/ui/GuideScreen.kt`

**Interfaces:**
- Consumes: `iconFor`, `tintColorFor` (Task 2); `guideLine` (Task 8); `Sticker`, `StickerKeyline`, `StickerShadow` (Task 5).
- Produces: `@Composable fun GuideScreen(partnerName: String, onDone: () -> Unit)`

- [ ] **Step 1: Write `GuideScreen.kt`**

```kotlin
package com.lovebutton.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.iconFor
import com.lovebutton.app.widget.tintColorFor

/** The ladder in the order it actually happens. */
private val LADDER = listOf(
    WidgetState.IDLE to "Waiting",
    WidgetState.SENDING to "Sending",
    WidgetState.SENT to "Sent",
    WidgetState.DELIVERED to "Delivered",
    WidgetState.SEEN to "Seen",
    WidgetState.FAILED to "Didn't send",
)

/**
 * What the colours on your home screen mean.
 *
 * Every picture here is `iconFor(...)` — the widget's own drawable, unmodified.
 * That is the entire point: a guide that drew its own approximation would be
 * teaching you about a picture that does not exist on your phone. Because both
 * this screen and `MessageWidget` call the same function, it cannot drift.
 */
@Composable
fun GuideScreen(partnerName: String, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Sticker.Ground)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("What the colours mean", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.size(4.dp))
        Text(
            "The heart on your home screen changes as your message travels.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(18.dp))

        LADDER.forEach { (state, title) ->
            val shape = RoundedCornerShape(16.dp)
            Box(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Box(
                    Modifier
                        .matchParentSize()
                        .offset(x = StickerShadow, y = StickerShadow)
                        .clip(shape)
                        .background(Sticker.Ink)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Sticker.Surface)
                        .border(StickerKeyline, Sticker.Ink, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(iconFor(msgId = 1, state = state)),
                        contentDescription = title,
                        colorFilter = tintColorFor(state)?.let { ColorFilter.tint(Color(it)) },
                        modifier = Modifier.size(46.dp),
                    )
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            guideLine(state, partnerName),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.size(8.dp))
        val shape = RoundedCornerShape(13.dp)
        Box(Modifier.fillMaxWidth().clickable { onDone() }) {
            Box(
                Modifier
                    .matchParentSize()
                    .offset(x = StickerShadow, y = StickerShadow)
                    .clip(shape)
                    .background(Sticker.Ink)
            )
            Text(
                "Got it",
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Sticker.Blossom)
                    .border(StickerKeyline, Sticker.Ink, shape)
                    .padding(vertical = 12.dp),
            )
        }
    }
}
```

- [ ] **Step 2: Build and run the whole suite**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks
```

Expected: BUILD SUCCESSFUL, every test passes.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/ui/GuideScreen.kt
git commit -m "feat(ui): the states guide, showing the widget's own drawables"
```

---

### Task 11: Splash, spec amendments, and the hardware pass

**Files:**
- Create: `app/src/main/res/values/themes.xml` (or modify if present)
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/lovebutton/app/MainActivity.kt`
- Modify: `love-button-spec.md` §6.1 and §7.1

**Interfaces:**
- Consumes: everything above.
- Produces: nothing further.

- [ ] **Step 1: Add the splash theme**

Create `app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- The platform splash API rather than a splash Activity: an Activity
         flashes the system splash first, so you get two. -->
    <style name="Theme.Lovie.Splash" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">#FFF0F5</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/ic_heart_filled</item>
        <item name="windowSplashScreenAnimationDuration">700</item>
        <item name="postSplashScreenTheme">@style/Theme.Lovie</item>
    </style>
    <style name="Theme.Lovie" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

Add the dependency to `app/build.gradle.kts`:

```kotlin
    implementation("androidx.core:core-splashscreen:1.0.1")
```

In `AndroidManifest.xml`, set the application (or MainActivity) theme to `@style/Theme.Lovie.Splash`.

In `MainActivity.onCreate`, **before** `super.onCreate(...)`:

```kotlin
        androidx.core.splashscreen.SplashScreen.installSplashScreen(this)
```

- [ ] **Step 2: Build and confirm it runs**

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.lovebutton.app
adb shell am start -n com.lovebutton.app/.MainActivity
```

Expected: the app cold-starts showing a pink splash with the heart, then Home.

- [ ] **Step 3: Amend the spec**

In `love-button-spec.md` §6.1, replace the three-screen list's item 3 and add a fourth:

```markdown
3. **Home** — her name, the four messages as tap-to-send rows with the mascot
   beside each, and a focal area showing the most recent send's state (§7.1).
   Unlike the widget, it keeps the final state rather than returning to idle:
   remembering is this screen's job, and a permanently lit home-screen button
   would only be noise.
4. **States guide** — the six ladder states, each shown with the widget's own
   drawable and a line explaining it.
```

In §7.1, after the state table, add:

```markdown
The app's focal area shows the same six states from the same artwork, but
animated per pixel rather than swapped between finished pictures, and it keeps
the final state where the widget returns to idle. See the in-app redesign design
document, §4.3 and §4.5.
```

- [ ] **Step 4: Full verification**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks
cd server && npm test && cd ..
```

Expected: BUILD SUCCESSFUL; all Android tests pass; server still 67 passed (this plan changes no server code — if that number moved, something is wrong).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(app): splash animation, and amend the spec for the fourth screen"
```

- [ ] **Step 6: The hardware pass**

No unit test covers the ladder end to end — it spans a worker, a push handler and a UI, exactly as the widget's ladder did. On two phones:

1. Open the app on A. The focal heart rests, breathing, saying `tap one below`.
2. Tap "I love you". The heart **fills** while the request is in flight, settles crimson, then **ripples to pink** when B's phone buzzes.
3. Unlock B without touching the notification. A's heart **runs gold around its outline** and says `Wifey saw it ♡`.
4. Close A and reopen it. The heart is **still gold**, with `Wifey saw your "I love you" · 1m ago`. (The widget, meanwhile, is back to outline. That difference is the design.)
5. Tap a **widget** instead, then open the app. The focal area shows that send laddering.
6. Open the guide. All six rows show, and each picture matches what the widget draws.
7. Turn B's network off and send. The heart shakes and greys, and says `didn't get through :(`.
8. Settings → Developer options → Animator duration scale → **off**. Repeat step 2: every state still renders, just without motion.

- [ ] **Step 7: Swap in the real pandas when they arrive**

```bash
rm app/src/main/res/drawable/panda_love.xml app/src/main/res/drawable/panda_thinking.xml \
   app/src/main/res/drawable/panda_miss.xml app/src/main/res/drawable/panda_call.xml
cp ~/panda_love.webp ~/panda_thinking.webp ~/panda_miss.webp ~/panda_call.webp \
   app/src/main/res/drawable/
./gradlew :app:assembleDebug
git add app/src/main/res/drawable/panda_*
git commit -m "feat(ui): the real panda art"
```

The resource names are unchanged, so **no code moves**. If the build fails with a duplicate-resource error, a placeholder `.xml` was not deleted.

---

## Self-Review

**1. Spec coverage**

| Spec section | Task |
|---|---|
| §4.1 `CurrentSend` store | 3 |
| §4.2 widget sends appear in the app | 4 |
| §4.3 focal keeps the outcome | 3 (store), 9 (render) |
| §4.4 copy | 8 |
| §4.5 the six animations | 7 |
| §5 the guide, sharing the widget's art | 2 (mapping), 10 (screen) |
| §6 Sticker Book theme, shallow shadows | 5, 9 |
| §6.1 shared colour tokens | 2 (`PixelPalette`), asserted in 5's test |
| §7 static pandas on the buttons | 9 (placeholders), 11 Step 7 (real art) |
| §8 splash | 11 |
| §9 generator colours + Kotlin emitter | 1 |
| §10 testing | every task; hardware in 11 |
| §11 spec amendments | 11 Step 3 |

No gaps.

**2. Placeholder scan:** No TBDs. Every code step carries complete code. The panda `.xml` files are labelled placeholders in the plan *and* in a comment inside the file itself, with an explicit removal step.

**3. Type consistency**
- `PixelGrids.GRIDS` (Task 1) → consumed by `PixelIcon` (6) and `AnimatedPixelIcon` (7). Same name.
- `PixelPalette` fields are `Int` (ARGB) throughout — Tasks 2, 6, 7 all treat them as `Int` and wrap in `Color(...)` at the draw site only.
- `iconFor(msgId, state)` (2) → used by `MessageWidget` (2) and `GuideScreen` (10). Same signature.
- `cellColor(grid, row, col, state)` (6) → used by Task 7. Same signature.
- `isBorderCell(grid, row, col)` (6) → used by Task 7's `borderRingOrder` and by Task 7's test. Same signature.
- `CurrentSend.start(sendId, msgId, now)` / `.update(sendId, state)` (3) → called by Task 4. Same signatures.
- `HomeScreen(partnerName, onOpenSetup, onOpenGuide)` (9) → called by `MainActivity` (9). Same.
- `GuideScreen(partnerName, onDone)` (10) → called by `MainActivity` (9). Same. **Task 9 Step 3 will not compile until Task 10 lands** — flagged in the step itself.

**4. Known risks**

- **Task 1 Step 3 may fail.** If the regenerated XML is not byte-identical, the generator has drifted beyond colour. The step says stop and report rather than overwrite, because overwriting would destroy the evidence of what drifted.
- **Task 5's fonts fail silently.** A wrong URL yields an HTML file, Android falls back to the system font, and nothing errors — the app just looks wrong. The size check exists for exactly that.
- **Task 7's `borderRingOrder`** uses a greedy nearest-neighbour walk. On a shape with a detached border region it could produce one long jump; the "is a loop" test catches it, and the heart, bubble, paw and call silhouettes are all single connected regions.
