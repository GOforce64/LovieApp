# Sounds and Widgets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each of the four messages its own notification sound and its own home-screen widget, so the buzz can be sent without opening the app and heard without looking at the phone.

**Architecture:** Four notification channels replace the throwaway `dev_buzz_v1`, each created once with its own `.ogg` and never altered afterwards. Four Glance widgets share one implementation parameterised by message id, but register four separate receivers so the launcher lists them individually. A tap writes state into Glance's per-widget store, enqueues the existing `SendWorker`, and the worker writes the outcome back — so the widget survives its host process being killed, which on MIUI it will be.

**Tech Stack:** Kotlin, Jetpack Glance 1.1.1, Compose BOM 2026.06.01, WorkManager 2.11.0, AGP 8.13.0, JDK 21 toolchain, compileSdk 36.

**Spec:** `love-button-spec.md` (binding authority) via the approved design at `docs/superpowers/specs/2026-08-24-sounds-and-widgets-design.md`.

## Global Constraints

- **Pin `androidx.glance:glance-appwidget` to exactly `1.1.1`.** Not `1.+`, not `1.2.0-rc01`, never `1.3.0-alpha02` — that version requires `compileSdk 37` and fails as `checkDebugAarMetadata` reporting "compileSdk of at least 37", naming neither Glance nor a version.
- **Do not change `composeBom`, `agp`, `compileSdk`, `kotlin`, or the `java { toolchain }` block.** That set took three fix rounds to stabilise. Glance 1.1.1 resolves cleanly against it; every transitive Compose request lands back on 1.11.4.
- **A notification channel's sound is frozen at creation and cannot be changed** (spec §6.3). Creating `msg_1`..`msg_4` is a one-way door. The four `.ogg` files must be final and in `res/raw` before any channel code runs.
- **Never add a `notification` block to a push** — the Worker sends data-only so the app picks the channel, and therefore the sound (spec §6.4).
- **Every outbound network call goes through WorkManager, never inline** (spec §6.5).
- **A unique notification id per send**, so rapid sends stack rather than replacing one another (spec §6.3).
- Widget geometry is fixed by spec §7: 2x2 cells, `minWidth`/`minHeight` 110dp, `targetCellWidth`/`targetCellHeight` 2, resizing disabled, no configuration activity.
- Receipts, the Delivered and Seen states, and the read-receipt toggle are **out of scope** — they belong to Plan 4 and depend on a `/v1/receipts` endpoint that does not exist.

---

## Task 1: Real channels with their real sounds

Replaces the temporary channel with the four permanent ones. This task is the irreversible one; everything it touches is a one-way door.

**Files:**
- Create: `app/src/main/res/raw/love.ogg`, `thinking.ogg`, `miss.ogg`, `call.ogg` (copied, not authored)
- Modify: `app/src/main/java/com/lovebutton/app/data/Messages.kt`
- Modify: `app/src/main/java/com/lovebutton/app/push/Notifications.kt`
- Test: `app/src/test/java/com/lovebutton/app/MessagesTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `LoveMessage(id: Int, text: String, channelId: String, soundRes: Int)`; `MESSAGES: List<LoveMessage>`; `messageForId(id: Int): LoveMessage?`; `ensureChannels(context: Context)` (note the plural — it replaces `ensureChannel`)

- [ ] **Step 1: Copy the four sounds into `res/raw`**

The files are final. Do not re-encode, re-trim or rename them.

```bash
mkdir -p app/src/main/res/raw
cp ~/Downloads/love-button-assets/sounds/love.ogg     app/src/main/res/raw/love.ogg
cp ~/Downloads/love-button-assets/sounds/thinking.ogg app/src/main/res/raw/thinking.ogg
cp ~/Downloads/love-button-assets/sounds/miss.ogg     app/src/main/res/raw/miss.ogg
cp ~/Downloads/love-button-assets/sounds/call.ogg     app/src/main/res/raw/call.ogg
ls -l app/src/main/res/raw/
```

Expected: four `.ogg` files, roughly 12K, 16K, 24K, 16K.

- [ ] **Step 2: Write the failing test**

Replace the whole of `app/src/test/java/com/lovebutton/app/MessagesTest.kt` with this. The final test is the one that changes — the old version asserted every message used `DEV_CHANNEL_ID`, which is exactly what this task undoes.

```kotlin
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
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*MessagesTest*'`
Expected: FAIL — `Unresolved reference: soundRes`, because `LoveMessage` has no such field yet.

- [ ] **Step 4: Rewrite `data/Messages.kt`**

```kotlin
package com.lovebutton.app.data

import androidx.annotation.RawRes
import com.lovebutton.app.R

/**
 * The message catalogue lives in the app, not on the server.
 *
 * A push carries `msg_id: 3`, never the words. Two consequences: the text never
 * transits Google's servers, and adding a fifth message is an app-only change.
 * The server keeps its own allowlist of valid ids and nothing else.
 */
data class LoveMessage(
    val id: Int,
    val text: String,
    val channelId: String,
    @RawRes val soundRes: Int,
)

/**
 * The retired development channel.
 *
 * Kept only so startup can delete it. It existed because a channel's sound is
 * frozen at creation (spec 6.3), so the real `msg_N` ids had to stay unused
 * until the four sounds were final — which they now are.
 */
const val DEV_CHANNEL_ID = "dev_buzz_v1"

val MESSAGES: List<LoveMessage> = listOf(
    LoveMessage(1, "I love you", "msg_1", R.raw.love),
    LoveMessage(2, "Thinking of you", "msg_2", R.raw.thinking),
    LoveMessage(3, "Miss you", "msg_3", R.raw.miss),
    LoveMessage(4, "Call me when you can", "msg_4", R.raw.call),
)

/** Null when this build does not know the id — an older app, a newer message. */
fun messageForId(id: Int): LoveMessage? = MESSAGES.firstOrNull { it.id == id }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*MessagesTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 6: Replace `ensureChannel` with `ensureChannels` in `push/Notifications.kt`**

Replace the existing `ensureChannel` function (keep the rest of the file, including `postMessageNotification`, exactly as it is) with:

```kotlin
/**
 * Creates the four real channels and deletes the retired development one.
 *
 * Creating a channel that already exists is a no-op, so this is safe on every
 * launch. Deleting `dev_buzz_v1` is safe for the opposite reason: it was created
 * as a throwaway precisely so that the `msg_N` ids would still be unburnt when
 * the sounds were finalised.
 *
 * A channel's sound is frozen at creation and cannot be changed afterwards
 * (spec 6.3). Changing one later means deleting the channel, which resets her
 * notification settings visibly. These four are permanent.
 */
fun ensureChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)

    manager.deleteNotificationChannel(DEV_CHANNEL_ID)

    val attributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .build()

    MESSAGES.forEach { message ->
        val channel = NotificationChannel(
            message.channelId,
            message.text,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Sound for \"${message.text}\""
            enableVibration(true)
            setSound(
                Uri.parse("android.resource://${context.packageName}/${message.soundRes}"),
                attributes,
            )
        }
        manager.createNotificationChannel(channel)
    }
}
```

Add these imports to the top of the file, keeping the existing ones:

```kotlin
import android.media.AudioAttributes
import android.net.Uri
import com.lovebutton.app.data.MESSAGES
```

- [ ] **Step 7: Update the caller in `LoveButtonApp.kt`**

```kotlin
package com.lovebutton.app

import android.app.Application
import com.lovebutton.app.push.ensureChannels

class LoveButtonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Creating a channel that already exists is a no-op, so this is safe on
        // every launch and guarantees the channels exist before the first push.
        ensureChannels(this)
    }
}
```

- [ ] **Step 8: Build and run the whole suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 15 tests passing (13 previous, minus the retired dev-channel test, plus 3 new).

- [ ] **Step 9: Hardware check — the sounds are real and distinct**

```bash
adb -s 923262ff install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.10.30:39751 install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app on both phones once, so `onCreate` creates the channels. Then on phone A:

Settings → Apps → Love Button → Notifications. Expected: **four** channels named "I love you", "Thinking of you", "Miss you", "Call me when you can", and **no** "Messages (temporary)". Tap into each and confirm the sound is set and plays.

**If any channel shows the default sound, stop.** That channel id is burnt. Fixing it means changing the id (`msg_1b`), not fixing the sound.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/res/raw app/src/main/java/com/lovebutton/app/data/Messages.kt \
        app/src/main/java/com/lovebutton/app/push/Notifications.kt \
        app/src/main/java/com/lovebutton/app/LoveButtonApp.kt \
        app/src/test/java/com/lovebutton/app/MessagesTest.kt
git commit -m "feat(app): give each message its own channel and sound"
```

---

## Task 2: The widget state model

Pure Kotlin, no Android, no Glance. Written first because it is the only part of the widget that can be unit-tested, and getting it wrong is what makes a widget stick on "Sending" forever.

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/widget/WidgetState.kt`
- Test: `app/src/test/java/com/lovebutton/app/WidgetStateTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `enum class WidgetState { IDLE, SENDING, SENT, FAILED }`; `WidgetState.holdMillis: Long?`; `WidgetState.fromName(name: String?): WidgetState`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.lovebutton.app

import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.fromName
import com.lovebutton.app.widget.holdMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetStateTest {

    @Test
    fun `unknown or missing stored state reads as idle`() {
        // Glance's store survives reinstalls and version changes, so a value written
        // by an older build can come back. Anything unrecognised must land on IDLE
        // rather than throw inside a widget update, which the host would surface as
        // a blank tile with no way to recover.
        assertEquals(WidgetState.IDLE, fromName(null))
        assertEquals(WidgetState.IDLE, fromName(""))
        assertEquals(WidgetState.IDLE, fromName("DELIVERED"))
        assertEquals(WidgetState.IDLE, fromName("nonsense"))
    }

    @Test
    fun `known state names round-trip`() {
        WidgetState.entries.forEach { state ->
            assertEquals(state, fromName(state.name))
        }
    }

    @Test
    fun `only the terminal states are held then cleared`() {
        // IDLE is the resting state so it is never "held". SENDING lasts as long as
        // the request does, which is not a fixed duration and must not be timed out
        // by the UI — the worker is the only thing that knows when it ended.
        assertNull(WidgetState.IDLE.holdMillis)
        assertNull(WidgetState.SENDING.holdMillis)
        assertEquals(4_000L, WidgetState.SENT.holdMillis)
        assertEquals(3_000L, WidgetState.FAILED.holdMillis)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*WidgetStateTest*'`
Expected: FAIL — `Unresolved reference: widget`.

- [ ] **Step 3: Write `widget/WidgetState.kt`**

```kotlin
package com.lovebutton.app.widget

/**
 * What a widget is currently showing.
 *
 * Spec 7.1 defines six states; DELIVERED and SEEN need receipts from the server,
 * which do not exist yet, so they arrive with Plan 4. The four here are the ones
 * this app can prove. A widget that claims "delivered" without a receipt is
 * lying, and knowing she got it is the entire product.
 */
enum class WidgetState { IDLE, SENDING, SENT, FAILED }

/**
 * How long a state is displayed before falling back to IDLE, or null if it is
 * not time-limited.
 *
 * SENDING deliberately has no duration: it ends when the request ends. A timeout
 * here would race the worker and could clear a tile that is still mid-send.
 */
val WidgetState.holdMillis: Long?
    get() = when (this) {
        WidgetState.IDLE, WidgetState.SENDING -> null
        WidgetState.SENT -> 4_000L
        WidgetState.FAILED -> 3_000L
    }

/**
 * Reads a stored state name, tolerating anything unrecognised.
 *
 * Glance's per-widget store outlives reinstalls, so a name written by a different
 * build can come back. Throwing inside a widget update leaves the host showing a
 * blank tile that no user action can fix.
 */
fun fromName(name: String?): WidgetState =
    WidgetState.entries.firstOrNull { it.name == name } ?: WidgetState.IDLE
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*WidgetStateTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/widget/WidgetState.kt \
        app/src/test/java/com/lovebutton/app/WidgetStateTest.kt
git commit -m "feat(app): add the widget state model"
```

---

## Task 3: One widget, end to end

Builds the heart widget only. Proving one widget completely is worth more than scaffolding four half-widgets, and Task 5 then repeats a known-good shape.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/res/drawable/ic_heart_filled.xml`, `ic_heart_outline.xml`
- Create: `app/src/main/res/xml/widget_love.xml`
- Create: `app/src/main/java/com/lovebutton/app/widget/MessageWidget.kt`
- Create: `app/src/main/java/com/lovebutton/app/widget/LoveWidget.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `WidgetState`, `fromName`, `holdMillis` (Task 2); `MESSAGES`, `messageForId` (Task 1); `SendWorker.enqueue(context, msgId)` (Plan 2)
- Produces: `abstract class MessageWidget(msgId: Int) : GlanceAppWidget`; `KEY_STATE: androidx.datastore.preferences.core.Preferences.Key<String>`; `KEY_MSG_ID: ActionParameters.Key<Int>`; `class LoveWidget : MessageWidget(1)`; `class LoveWidgetReceiver : GlanceAppWidgetReceiver`

- [ ] **Step 1: Add the Glance dependency**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
# 1.1.1 is the newest STABLE Glance (1.2.0 never left rc). Do NOT move to
# 1.3.0-alpha02: it requires compileSdk 37 and fails as checkDebugAarMetadata
# reporting "compileSdk of at least 37", naming neither Glance nor a version —
# the same shape of trap as the composeBom note above.
glance = "1.1.1"
```

and to `[libraries]`:

```toml
androidx-glance-appwidget = { group = "androidx.glance", name = "glance-appwidget", version.ref = "glance" }
```

In `app/build.gradle.kts`, add one line to `dependencies`, directly after the WorkManager line:

```kotlin
    implementation(libs.androidx.glance.appwidget)
```

- [ ] **Step 2: Verify the dependency builds before writing code against it**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

If this reports AAR metadata errors mentioning compileSdk 37, the version is wrong — check it reads exactly `1.1.1`. Do not raise `compileSdk` to make it pass.

- [ ] **Step 3: Copy the heart drawables**

`res/drawable/` does not exist yet — every icon in the app so far has been an
`@android:drawable` system one — so create it first or the copy silently writes a
*file* named `drawable` and resource linking fails.

```bash
mkdir -p app/src/main/res/drawable
cp ~/Downloads/love-button-assets/icons/grid-11/ic_heart_filled.xml  app/src/main/res/drawable/
cp ~/Downloads/love-button-assets/icons/grid-11/ic_heart_outline.xml app/src/main/res/drawable/
```

- [ ] **Step 4: Create `app/src/main/res/xml/widget_love.xml`**

Geometry is fixed by spec §7. `resizeMode="none"` is deliberate: the tile is one icon and a label, and letting it stretch produces a large blurry heart.

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:resizeMode="none"
    android:widgetCategory="home_screen"
    android:description="@string/widget_love_description"
    android:previewImage="@drawable/ic_heart_filled"
    android:initialLayout="@layout/glance_default_loading_layout" />
```

`@layout/glance_default_loading_layout` ships inside Glance; it is what the host shows before the first render.

- [ ] **Step 5: Add the strings**

Append inside `<resources>` in `app/src/main/res/values/strings.xml`:

```xml
    <string name="widget_love_label">I love you</string>
    <string name="widget_love_description">Send "I love you"</string>
```

- [ ] **Step 6: Write `widget/MessageWidget.kt`**

```kotlin
package com.lovebutton.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lovebutton.app.R
import com.lovebutton.app.data.messageForId

/** Where a widget's current state is stored, per widget instance. */
val KEY_STATE = stringPreferencesKey("state")

/** Which message a tap should send, passed to the action callback. */
val KEY_MSG_ID = ActionParameters.Key<Int>("msg_id")

/**
 * One widget, parameterised by message id.
 *
 * Spec 7 requires four separate *registrations* so the launcher lists four
 * entries with their own preview and label. It says nothing about four
 * implementations, and four copies of the state ladder would be four places to
 * fix every future change. The subclasses in the other files carry only an id.
 */
abstract class MessageWidget(private val msgId: Int) : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = fromName(currentState(KEY_STATE))
            Tile(msgId, state)
        }
    }

    @Composable
    private fun Tile(msgId: Int, state: WidgetState) {
        val message = messageForId(msgId)
        val icon = when (state) {
            WidgetState.IDLE, WidgetState.SENDING -> outlineIconFor(msgId)
            WidgetState.SENT, WidgetState.FAILED -> filledIconFor(msgId)
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(backgroundFor(state))
                .cornerRadius(16.dp)
                .padding(8.dp)
                .clickable(
                    onClick = actionRunCallback<SendAction>(
                        actionParametersOf(KEY_MSG_ID to msgId)
                    )
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = message?.text,
                modifier = GlanceModifier.size(64.dp),
            )
            Text(
                text = if (state == WidgetState.FAILED) "Not sent" else (message?.text ?: ""),
                style = TextStyle(
                    color = ColorProvider(Color(0xFF6B4453)),
                    fontSize = 11.sp,
                ),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        }
    }
}

/** Spec 7.1's colour column, minus the states that need receipts. */
private fun backgroundFor(state: WidgetState) = ColorProvider(
    when (state) {
        WidgetState.IDLE -> Color(0xFFF6E7EC)     // pale grey-pink
        WidgetState.SENDING -> Color(0xFFF7D6E2)  // pale pink
        WidgetState.SENT -> Color(0xFFFF6FA5)     // pink
        WidgetState.FAILED -> Color(0xFFDEDCE0)   // grey
    }
)

private fun outlineIconFor(msgId: Int): Int = when (msgId) {
    1 -> R.drawable.ic_heart_outline
    else -> R.drawable.ic_heart_outline
}

private fun filledIconFor(msgId: Int): Int = when (msgId) {
    1 -> R.drawable.ic_heart_filled
    else -> R.drawable.ic_heart_filled
}
```

The two `when` blocks look pointless with one widget. Task 5 fills in ids 2–4; leaving them as functions now means Task 5 adds branches rather than restructuring.

- [ ] **Step 7: Write `widget/LoveWidget.kt`**

`SendAction` lands here in Task 4 — for now the tap must compile and do something honest, so it enqueues the send without touching state.

```kotlin
package com.lovebutton.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import com.lovebutton.app.work.SendWorker

class LoveWidget : MessageWidget(msgId = 1)

class LoveWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LoveWidget()
}

/**
 * Handles a tap.
 *
 * The state ladder is wired in the next task; this version only dispatches the
 * send, so a tap already does the thing that matters.
 */
class SendAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val msgId = parameters[KEY_MSG_ID] ?: return
        SendWorker.enqueue(context, msgId)
    }
}
```

- [ ] **Step 8: Register the receiver in `AndroidManifest.xml`**

Add inside `<application>`, after the existing `<service>` block:

```xml
        <receiver
            android:name=".widget.LoveWidgetReceiver"
            android:label="@string/widget_love_label"
            android:exported="false">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_love" />
        </receiver>
```

- [ ] **Step 9: Build**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 18 tests passing.

- [ ] **Step 10: Hardware check**

```bash
adb -s 923262ff install -r app/build/outputs/apk/debug/app-debug.apk
```

On phone A: long-press the home screen → Widgets → find **Love Button**. Expected: one entry labelled "I love you" showing the pixel heart as its preview. Place it. Expected: a 2x2 tile, pale pink, outline heart, label underneath.

Tap it. Expected: phone B buzzes with "I love you" and its own sound. The tile does **not** change appearance yet — that is Task 4.

- [ ] **Step 11: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/res app/src/main/java/com/lovebutton/app/widget app/src/main/AndroidManifest.xml
git commit -m "feat(app): add the first home-screen widget"
```

---

## Task 4: The state ladder

Makes the tile respond to what actually happened. Everything here exists so that a killed host process cannot leave a widget lying about its state.

**Files:**
- Modify: `app/src/main/java/com/lovebutton/app/widget/LoveWidget.kt`
- Create: `app/src/main/java/com/lovebutton/app/widget/WidgetStateWriter.kt`
- Modify: `app/src/main/java/com/lovebutton/app/work/SendWorker.kt`

**Interfaces:**
- Consumes: `KEY_STATE`, `KEY_MSG_ID`, `MessageWidget`, `WidgetState`, `holdMillis` (Tasks 2, 3)
- Produces: `suspend fun setWidgetState(context: Context, appWidgetId: Int, state: WidgetState)`; `SendWorker.enqueue(context: Context, msgId: Int, appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID)`

- [ ] **Step 1: Write `widget/WidgetStateWriter.kt`**

```kotlin
package com.lovebutton.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll

/**
 * Writes a state into one widget's own store and redraws it.
 *
 * The widget is addressed by platform `appWidgetId` rather than `GlanceId`
 * because this crosses a process boundary: a `GlanceId` cannot be put into
 * WorkManager input data, and the worker may well run after the host that
 * created it has been killed. On MIUI that is the normal case, not the edge one.
 *
 * A widget the user has since removed resolves to null; that is not an error and
 * must not fail the send that triggered it.
 */
suspend fun setWidgetState(context: Context, appWidgetId: Int, state: WidgetState) {
    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

    val manager = GlanceAppWidgetManager(context)
    val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull() ?: return

    updateAppWidgetState(context, glanceId) { prefs ->
        prefs[KEY_STATE] = state.name
    }
    LoveWidget().update(context, glanceId)
}
```

- [ ] **Step 2: Rewrite `SendAction` in `widget/LoveWidget.kt`**

Replace the `SendAction` class from Task 3 with this; leave `LoveWidget` and `LoveWidgetReceiver` untouched.

```kotlin
/**
 * Handles a tap.
 *
 * Sets SENDING immediately so the tile acknowledges the touch, then hands the
 * network call to WorkManager. Nothing blocking happens here: an ActionCallback
 * runs on the host's clock, and a slow request would freeze the launcher.
 */
class SendAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val msgId = parameters[KEY_MSG_ID] ?: return
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)

        setWidgetState(context, appWidgetId, WidgetState.SENDING)
        SendWorker.enqueue(context, msgId, appWidgetId)
    }
}
```

Add these imports to that file:

```kotlin
import androidx.glance.appwidget.GlanceAppWidgetManager
```

- [ ] **Step 3: Teach `SendWorker` to report back**

Replace the body of `app/src/main/java/com/lovebutton/app/work/SendWorker.kt` with this. The class already exists from Plan 2; the change is an added input and the state writes.

```kotlin
package com.lovebutton.app.work

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lovebutton.app.BuildConfig
import com.lovebutton.app.data.LoveButtonApi
import com.lovebutton.app.data.Prefs
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.holdMillis
import com.lovebutton.app.widget.setWidgetState
import kotlinx.coroutines.delay

/**
 * Performs one send.
 *
 * Never called inline from a tap handler: the widget host process (and the app
 * itself) can be killed mid-request, and WorkManager gives retry-on-reconnect for
 * free. On MIUI, where processes are killed aggressively, this is the difference
 * between a tap that eventually lands and one that vanishes.
 */
class SendWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val msgId = inputData.getInt(KEY_MSG_ID, -1)
        val appWidgetId = inputData.getInt(KEY_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

        if (msgId < 0) return settle(appWidgetId, WidgetState.FAILED, Result.failure())

        val enrolment = Prefs(applicationContext).current()
            ?: return settle(appWidgetId, WidgetState.FAILED, Result.failure())

        return try {
            LoveButtonApi(BuildConfig.API_BASE_URL).send(enrolment.authToken, msgId)
            // A delivered count of zero still counts as success: the send was
            // recorded, her phone just has no active device right now.
            settle(appWidgetId, WidgetState.SENT, Result.success())
        } catch (e: Exception) {
            // The tile says "Not sent" while WorkManager retries underneath. Showing
            // SENDING indefinitely would be worse: a tile that never resolves reads
            // as a broken app rather than a failed send.
            settle(appWidgetId, WidgetState.FAILED, Result.retry())
        }
    }

    /**
     * Shows a terminal state for its hold time, then returns the tile to idle.
     *
     * The delay runs inside the worker rather than as a second scheduled job: a
     * follow-up WorkRequest could be deferred by Doze for minutes, stranding a
     * tile on "Sent" long after the moment has passed.
     */
    private suspend fun settle(appWidgetId: Int, state: WidgetState, result: Result): Result {
        setWidgetState(applicationContext, appWidgetId, state)
        state.holdMillis?.let { hold ->
            delay(hold)
            setWidgetState(applicationContext, appWidgetId, WidgetState.IDLE)
        }
        return result
    }

    companion object {
        private const val KEY_MSG_ID = "msg_id"
        private const val KEY_APP_WIDGET_ID = "app_widget_id"

        /**
         * @param appWidgetId the tile to report back to, or INVALID_APPWIDGET_ID
         *   when the send came from the app's own home screen rather than a widget.
         */
        fun enqueue(
            context: Context,
            msgId: Int,
            appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
        ) {
            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(
                    Data.Builder()
                        .putInt(KEY_MSG_ID, msgId)
                        .putInt(KEY_APP_WIDGET_ID, appWidgetId)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
```

The default parameter means `HomeScreen`'s existing `SendWorker.enqueue(context, message.id)` call keeps compiling and keeps working, unchanged.

- [ ] **Step 4: Build and test**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 18 tests passing.

- [ ] **Step 5: Hardware check — the ladder**

```bash
adb -s 923262ff install -r app/build/outputs/apk/debug/app-debug.apk
```

Tap the widget on phone A and watch the tile:

1. Immediately → pale pink, outline heart (SENDING).
2. Within a second or two → **pink, filled heart** (SENT), held about 4 seconds.
3. Then → back to pale grey-pink outline (IDLE).
4. Phone B buzzes with the "I love you" sound.

Then turn phone A's wifi and mobile data off and tap again. Expected: SENDING, then **grey with "Not sent"** for about 3 seconds, then IDLE. Turn the network back on: WorkManager retries and phone B buzzes late — which is correct, the send was never abandoned.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/widget app/src/main/java/com/lovebutton/app/work/SendWorker.kt
git commit -m "feat(app): wire the widget state ladder to the send"
```

---

## Task 5: The other three widgets

Repeats Task 3's proven shape for messages 2, 3 and 4.

**Files:**
- Create: `app/src/main/res/drawable/ic_bubble_filled.xml`, `ic_bubble_outline.xml`, `ic_paw_filled.xml`, `ic_paw_outline.xml`, `ic_call_filled.xml`, `ic_call_outline.xml`
- Create: `app/src/main/res/xml/widget_thinking.xml`, `widget_miss.xml`, `widget_call.xml`
- Create: `app/src/main/java/com/lovebutton/app/widget/OtherWidgets.kt`
- Modify: `app/src/main/java/com/lovebutton/app/widget/MessageWidget.kt`
- Modify: `app/src/main/java/com/lovebutton/app/widget/WidgetStateWriter.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`
- Create: `scripts/pixel_icons.py`

**Interfaces:**
- Consumes: `MessageWidget`, `SendAction`, `setWidgetState` (Tasks 3, 4)
- Produces: `ThinkingWidget`, `MissWidget`, `CallWidget` and their receivers

- [ ] **Step 1: Copy the remaining drawables and the generator**

```bash
cp ~/Downloads/love-button-assets/icons/grid-11/ic_bubble_*.xml app/src/main/res/drawable/
cp ~/Downloads/love-button-assets/icons/grid-11/ic_paw_*.xml    app/src/main/res/drawable/
cp ~/Downloads/love-button-assets/icons/grid-11/ic_call_*.xml   app/src/main/res/drawable/
mkdir -p scripts && cp ~/Downloads/love-button-assets/icons/pixel_icons.py scripts/
```

The generator is committed so the icons stay editable as ASCII grids rather than as hand-maintained path data.

- [ ] **Step 2: Point the icon lookups at the real drawables**

In `widget/MessageWidget.kt`, replace both lookup functions:

```kotlin
private fun outlineIconFor(msgId: Int): Int = when (msgId) {
    1 -> R.drawable.ic_heart_outline
    2 -> R.drawable.ic_bubble_outline
    3 -> R.drawable.ic_paw_outline
    4 -> R.drawable.ic_call_outline
    else -> R.drawable.ic_heart_outline
}

private fun filledIconFor(msgId: Int): Int = when (msgId) {
    1 -> R.drawable.ic_heart_filled
    2 -> R.drawable.ic_bubble_filled
    3 -> R.drawable.ic_paw_filled
    4 -> R.drawable.ic_call_filled
    else -> R.drawable.ic_heart_filled
}
```

- [ ] **Step 3: Add the strings**

Append inside `<resources>` in `app/src/main/res/values/strings.xml`:

```xml
    <string name="widget_thinking_label">Thinking of you</string>
    <string name="widget_thinking_description">Send "Thinking of you"</string>
    <string name="widget_miss_label">Miss you</string>
    <string name="widget_miss_description">Send "Miss you"</string>
    <string name="widget_call_label">Call me</string>
    <string name="widget_call_description">Send "Call me when you can"</string>
```

- [ ] **Step 4: Create the three provider XMLs**

`app/src/main/res/xml/widget_thinking.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:resizeMode="none"
    android:widgetCategory="home_screen"
    android:description="@string/widget_thinking_description"
    android:previewImage="@drawable/ic_bubble_filled"
    android:initialLayout="@layout/glance_default_loading_layout" />
```

`app/src/main/res/xml/widget_miss.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:resizeMode="none"
    android:widgetCategory="home_screen"
    android:description="@string/widget_miss_description"
    android:previewImage="@drawable/ic_paw_filled"
    android:initialLayout="@layout/glance_default_loading_layout" />
```

`app/src/main/res/xml/widget_call.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:resizeMode="none"
    android:widgetCategory="home_screen"
    android:description="@string/widget_call_description"
    android:previewImage="@drawable/ic_call_filled"
    android:initialLayout="@layout/glance_default_loading_layout" />
```

- [ ] **Step 5: Write `widget/OtherWidgets.kt`**

```kotlin
package com.lovebutton.app.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class ThinkingWidget : MessageWidget(msgId = 2)

class ThinkingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ThinkingWidget()
}

class MissWidget : MessageWidget(msgId = 3)

class MissWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MissWidget()
}

class CallWidget : MessageWidget(msgId = 4)

class CallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CallWidget()
}
```

- [ ] **Step 6: Fix `setWidgetState` to redraw the right widget class**

Task 4's version hardcodes `LoveWidget()`, which would silently fail to redraw the other three. Replace the tail of the function:

```kotlin
suspend fun setWidgetState(context: Context, appWidgetId: Int, state: WidgetState) {
    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

    val manager = GlanceAppWidgetManager(context)
    val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull() ?: return

    updateAppWidgetState(context, glanceId) { prefs ->
        prefs[KEY_STATE] = state.name
    }

    // Each widget class owns its own instances, so the redraw has to go through
    // the class that actually holds this id. Asking the wrong one writes the
    // state and then quietly draws nothing.
    listOf(LoveWidget(), ThinkingWidget(), MissWidget(), CallWidget()).forEach { widget ->
        if (glanceId in manager.getGlanceIds(widget.javaClass)) {
            widget.update(context, glanceId)
        }
    }
}
```

- [ ] **Step 7: Register the three receivers**

Add inside `<application>` in `AndroidManifest.xml`, after `LoveWidgetReceiver`:

```xml
        <receiver
            android:name=".widget.ThinkingWidgetReceiver"
            android:label="@string/widget_thinking_label"
            android:exported="false">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_thinking" />
        </receiver>

        <receiver
            android:name=".widget.MissWidgetReceiver"
            android:label="@string/widget_miss_label"
            android:exported="false">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_miss" />
        </receiver>

        <receiver
            android:name=".widget.CallWidgetReceiver"
            android:label="@string/widget_call_label"
            android:exported="false">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_call" />
        </receiver>
```

- [ ] **Step 8: Build and test**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 18 tests passing.

- [ ] **Step 9: Hardware check**

```bash
adb -s 923262ff install -r app/build/outputs/apk/debug/app-debug.apk
```

Widget picker on phone A. Expected: **four** separate Love Button entries — "I love you" (heart), "Thinking of you" (smiley bubble), "Miss you" (paw), "Call me" (CALL) — each with its own preview.

Place all four. Tap each in turn. Expected: each runs its own ladder independently, phone B buzzes four times with four different sounds, and the four notifications **stack** rather than replacing one another.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/res app/src/main/java/com/lovebutton/app/widget app/src/main/AndroidManifest.xml scripts/pixel_icons.py
git commit -m "feat(app): add the remaining three widgets"
```

---

## Task 6: Hardware verification pass

No code. Spec §11 puts notification, UI and MIUI behaviour on real hardware, and these are the checks that a unit test cannot make.

- [ ] **Step 1: Install on both phones**

```bash
adb -s 923262ff install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.10.30:39751 install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: The reinstall check — the one that proves the channels**

Open the app on both phones. Tap all four widgets from A to B, then all four from B to A.

Expected: eight buzzes, four distinct sounds, each matching its message in both directions.

**Then reinstall and repeat.** The sounds must be identical the second time. A channel created wrongly the first time keeps its wrong sound forever, and a fresh install is the only way to tell a correctly-created channel from one that happens to sound right.

- [ ] **Step 3: Widgets survive the host being killed**

On phone B, swipe Love Button out of recents, then tap a widget on B.

Expected: it still sends, and the tile still runs its ladder. This is what the Glance per-widget store buys — state held in memory would have reset to IDLE.

- [ ] **Step 4: Widgets survive a reboot**

Reboot phone A. Without opening the app, tap a widget.

Expected: it sends and phone B buzzes. If the tile is stuck on a stale state after the reboot, note which one — that indicates a state written but never cleared, which Plan 4's receipt handling would compound.

- [ ] **Step 5: The unenrolled case**

This is worth checking on whichever phone you are willing to re-enrol, and can be skipped if you would rather not. Clear the app's data (Settings → Apps → Love Button → Storage → Clear data), then look at the placed widgets.

Expected per spec §7: the tile renders dimmed and opens the app rather than sending. **If it instead attempts a send and shows "Not sent", that is a gap** — `SendWorker` returns `Result.failure()` with no enrolment, which is correct, but the tile should not have offered the send in the first place. Record it for Plan 4 rather than fixing it here.

- [ ] **Step 6: Record the results**

Note the outcome in the ledger, including anything that needed a second attempt. Then run `superpowers:finishing-a-development-branch`.

---

## Self-Review

**1. Spec coverage:**

| Spec requirement | Task |
|---|---|
| §6.2 four messages, ids 1-4, text local to the app | 1 |
| §6.2 per-message sound files in `res/raw` | 1 |
| §6.3 one channel per message id, `IMPORTANCE_HIGH` | 1 |
| §6.3 sound frozen at creation — real channels created once, dev channel deleted | 1 |
| §6.3 unique notification id per send | already shipped in Plan 2, untouched |
| §6.5 all network calls via WorkManager | 3, 4 |
| §7 one widget type per message, separate receivers and provider XML | 3, 5 |
| §7 2x2, 110dp, resizing disabled, no config activity | 3, 5 |
| §7 single large icon, label underneath, whole tile is the tap target | 3 |
| §7 `ActionCallback` → WorkManager request | 3, 4 |
| §7.1 Idle / Sending / Sent / Failed, with hold durations | 2, 4 |
| §11 JVM unit tests for pure logic | 1, 2 |
| §11 hardware verification | 1, 3, 4, 5, 6 |

Deferred to Plan 4, as the design states: `/v1/receipts`, Delivered and Seen, the `pending_send_id → glanceId` correlation, the buffered-receipt race, the read-receipt toggle, and the dimmed unenrolled tile (Task 6 Step 5 records it rather than building it).

**2. Placeholder scan:** No TBDs. Every code step carries complete code. The two values an implementer cannot derive — the adb serials and the asset directory — are literals from the current environment.

**3. Type consistency:** `LoveMessage` gains `soundRes` in Task 1 and is read as `message.soundRes` in the same task only. `ensureChannel` → `ensureChannels` is renamed in Task 1 and its single caller updated in the same task. `KEY_STATE` and `KEY_MSG_ID` are defined in Task 3 and used in Tasks 4 and 5. `setWidgetState(context, appWidgetId, state)` is defined in Task 4 and revised in Task 5 Step 6 with the same signature. `SendWorker.enqueue` gains a third parameter with a default in Task 4, so Plan 2's existing two-argument call site in `HomeScreen.kt` still compiles.

**4. Known risk:** Task 3 writes `SendAction` in a form Task 4 immediately rewrites. This is deliberate — Task 3 must build and be reviewable on its own, and a tap that sends without updating the tile is honest behaviour rather than a stub.
