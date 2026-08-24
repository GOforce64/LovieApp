## Task 3: The app mints the `send_id` and remembers which tile it belongs to

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/data/PendingSends.kt`
- Modify: `app/src/main/java/com/lovebutton/app/data/ApiModels.kt`
- Modify: `app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt`
- Modify: `app/src/main/java/com/lovebutton/app/work/SendWorker.kt`
- Test: `app/src/test/java/com/lovebutton/app/LoveButtonApiTest.kt`

**Interfaces:**
- Consumes: `LoveButtonApi.send`, `setWidgetState`, `WidgetState`
- Produces: `PendingSends(context)` with `suspend fun remember(sendId: String, appWidgetId: Int)`, `suspend fun widgetFor(sendId: String): Int?`, `suspend fun forget(sendId: String)`, `suspend fun forgetExpired(now: Long)`; `const val PENDING_WINDOW_MS = 20_000L`

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/lovebutton/app/LoveButtonApiTest.kt`, inside the existing class:

```kotlin
    @Test
    fun `send includes the client-minted send_id in the body`() {
        server.enqueue(MockResponse().setBody("""{"send_id":"abc","delivered":1}"""))

        runBlocking { api.send("token", msgId = 2, sendId = "11111111-2222-4333-8444-555555555555") }

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("11111111-2222-4333-8444-555555555555"))
        // Invariant 2's client-side half, unchanged: the client picks an id,
        // never a destination.
        assertFalse(body.contains("to_person"))
        assertFalse(body.contains("from_person"))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*LoveButtonApiTest*'`
Expected: FAIL — `send` has no `sendId` parameter.

- [ ] **Step 3: Add `send_id` to the request model**

In `app/src/main/java/com/lovebutton/app/data/ApiModels.kt`, find `SendRequest` and replace it with:

```kotlin
@Serializable
data class SendRequest(
    @SerialName("msg_id") val msgId: Int,
    // Minted by the app so the send_id -> widget mapping exists before the
    // request leaves. See the Plan 4 design, section 2.
    @SerialName("send_id") val sendId: String,
)
```

- [ ] **Step 4: Thread it through the client**

In `app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt`, change the `send` signature and its body line:

```kotlin
    suspend fun send(authToken: String, msgId: Int, sendId: String): SendResult {
        val body = json.encodeToString(SendRequest(msgId, sendId))
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*LoveButtonApiTest*'`
Expected: PASS.

- [ ] **Step 6: Write `data/PendingSends.kt`**

```kotlin
package com.lovebutton.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * How long a send waits for a receipt before the tile gives up on it.
 *
 * Spec §7.1: no `delivered` inside this window settles the tile on plain Sent,
 * and receipts arriving afterwards are dropped. A heart lighting up for
 * something sent an hour ago is confusing rather than sweet.
 */
const val PENDING_WINDOW_MS = 20_000L

private val Context.pendingStore by preferencesDataStore(name = "pending_sends")

/**
 * Which widget a send belongs to, so a receipt can find its tile.
 *
 * Written *before* the request leaves, which is the whole point of the app
 * minting the send id: a receipt can arrive before the send response does, and
 * a mapping that only exists afterwards would miss it.
 *
 * Entries are stored as `appWidgetId:timestamp` under the send id. A separate
 * DataStore file from `love_button` keeps enrolment — the thing whose loss
 * costs a re-enrolment — away from disposable correlation state.
 */
class PendingSends(private val context: Context) {

    suspend fun remember(sendId: String, appWidgetId: Int) {
        context.pendingStore.edit { prefs ->
            prefs[stringPreferencesKey(sendId)] = "$appWidgetId:${System.currentTimeMillis()}"
        }
    }

    /** The widget awaiting this send, or null if unknown or expired. */
    suspend fun widgetFor(sendId: String): Int? {
        val raw = context.pendingStore.data.first()[stringPreferencesKey(sendId)] ?: return null
        val parts = raw.split(":")
        val widget = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > PENDING_WINDOW_MS) return null
        return widget
    }

    suspend fun forget(sendId: String) {
        context.pendingStore.edit { prefs -> prefs.remove(stringPreferencesKey(sendId)) }
    }

    /**
     * Drops everything past the window.
     *
     * Without this the store grows forever: every send that never gets a receipt
     * leaves an entry behind, and nothing else would ever remove them.
     */
    suspend fun forgetExpired(now: Long = System.currentTimeMillis()) {
        context.pendingStore.edit { prefs ->
            prefs.asMap().forEach { (key, value) ->
                val at = (value as? String)?.substringAfter(":")?.toLongOrNull() ?: return@forEach
                if (now - at > PENDING_WINDOW_MS) prefs.remove(stringPreferencesKey(key.name))
            }
        }
    }
}
```

- [ ] **Step 7: Mint the id in `SendWorker` and remember the mapping**

In `app/src/main/java/com/lovebutton/app/work/SendWorker.kt`, replace the body of `doWork` from the enrolment line onward:

```kotlin
        val enrolment = Prefs(applicationContext).current()
            ?: return settle(appWidgetId, WidgetState.FAILED, Result.failure())

        val pending = PendingSends(applicationContext)
        pending.forgetExpired()

        // Minted and recorded BEFORE the request. A receipt can beat the send
        // response, and a mapping written afterwards would miss it.
        val sendId = UUID.randomUUID().toString()
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            pending.remember(sendId, appWidgetId)
        }

        return try {
            LoveButtonApi(BuildConfig.API_BASE_URL).send(enrolment.authToken, msgId, sendId)
            settle(appWidgetId, WidgetState.SENT, Result.success())
        } catch (e: Exception) {
            pending.forget(sendId)
            settle(appWidgetId, WidgetState.FAILED, Result.retry())
        }
```

Add these imports:

```kotlin
import com.lovebutton.app.data.PendingSends
import java.util.UUID
```

- [ ] **Step 8: Stop the 4-second hold from clearing a tile that is awaiting a receipt**

The minted id has to be readable from `settle`, and it never crosses a process
boundary, so it lives in a field rather than in WorkManager input data. Add to the
class body, above `doWork`:

```kotlin
    /** The id minted for this run, needed by settle() to check for a receipt. */
    private var mintedSendId: String = ""
```

In `doWork`, immediately after `val sendId = UUID.randomUUID().toString()`, add:

```kotlin
        mintedSendId = sendId
```

Then replace `settle` entirely:

```kotlin
    /**
     * Shows a state for its hold time, then returns the tile to idle.
     *
     * SENT is held for the whole pending window rather than four seconds: it is
     * waiting for a receipt, and dropping to idle sooner would hide a delivered
     * that was about to arrive.
     *
     * A receipt landing first clears the pending entry, so the check below finds
     * nothing and leaves the tile alone — otherwise this would overwrite the
     * crimson or gold the receipt just set with idle.
     */
    private suspend fun settle(appWidgetId: Int, state: WidgetState, result: Result): Result {
        setWidgetState(applicationContext, appWidgetId, state)

        if (state == WidgetState.SENT) {
            delay(PENDING_WINDOW_MS)
            val pending = PendingSends(applicationContext)
            if (pending.widgetFor(mintedSendId) != null) {
                pending.forget(mintedSendId)
                setWidgetState(applicationContext, appWidgetId, WidgetState.IDLE)
            }
            return result
        }

        state.holdMillis?.let { hold ->
            delay(hold)
            setWidgetState(applicationContext, appWidgetId, WidgetState.IDLE)
        }
        return result
    }
```

Add the import `com.lovebutton.app.data.PENDING_WINDOW_MS`.

- [ ] **Step 9: Build and run the suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 21 tests.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/data app/src/main/java/com/lovebutton/app/work/SendWorker.kt app/src/test/java/com/lovebutton/app/LoveButtonApiTest.kt
git commit -m "feat(app): mint the send_id and remember which tile it belongs to"
```

---

