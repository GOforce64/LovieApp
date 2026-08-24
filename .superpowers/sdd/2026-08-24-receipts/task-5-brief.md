## Task 5: Your tile shows delivered and seen

**Files:**
- Create: `app/src/main/res/drawable/ic_heart_delivered.xml`, `ic_heart_seen.xml`, `ic_bubble_delivered.xml`, `ic_bubble_seen.xml`, `ic_paw_delivered.xml`, `ic_paw_seen.xml`, `ic_call_delivered.xml`, `ic_call_seen.xml`
- Modify: `app/src/main/java/com/lovebutton/app/widget/WidgetState.kt`
- Modify: `app/src/main/java/com/lovebutton/app/widget/MessageWidget.kt`
- Modify: `app/src/main/java/com/lovebutton/app/push/PushService.kt`
- Modify: `scripts/pixel_icons.py`
- Test: `app/src/test/java/com/lovebutton/app/WidgetStateTest.kt`

**Interfaces:**
- Consumes: `PendingSends`, `setWidgetState`, `WidgetState`
- Produces: `WidgetState.DELIVERED`, `WidgetState.SEEN`

- [ ] **Step 1: Copy the drawables and the updated generator**

```bash
cp ~/Downloads/love-button-assets/icons/receipts/ic_*_delivered.xml app/src/main/res/drawable/
cp ~/Downloads/love-button-assets/icons/receipts/ic_*_seen.xml      app/src/main/res/drawable/
cp ~/Downloads/love-button-assets/icons/pixel_icons.py              scripts/pixel_icons.py
```

- [ ] **Step 2: Write the failing test**

Replace the third test in `app/src/test/java/com/lovebutton/app/WidgetStateTest.kt`:

```kotlin
    @Test
    fun `only the terminal states are held then cleared`() {
        // IDLE is the resting state so it is never "held". SENDING lasts as long as
        // the request does, which is not a fixed duration and must not be timed out
        // by the UI — the worker is the only thing that knows when it ended. SENT
        // waits out the pending window because a receipt may still be coming.
        assertNull(WidgetState.IDLE.holdMillis)
        assertNull(WidgetState.SENDING.holdMillis)
        assertEquals(20_000L, WidgetState.SENT.holdMillis)
        assertEquals(3_000L, WidgetState.FAILED.holdMillis)
        assertEquals(4_000L, WidgetState.DELIVERED.holdMillis)
        assertEquals(4_000L, WidgetState.SEEN.holdMillis)
    }
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*WidgetStateTest*'`
Expected: FAIL — `Unresolved reference: DELIVERED`.

- [ ] **Step 4: Extend the state model**

In `app/src/main/java/com/lovebutton/app/widget/WidgetState.kt`:

```kotlin
enum class WidgetState { IDLE, SENDING, SENT, FAILED, DELIVERED, SEEN }
```

and replace `holdMillis`:

```kotlin
val WidgetState.holdMillis: Long?
    get() = when (this) {
        WidgetState.IDLE, WidgetState.SENDING -> null
        // Held for the whole pending window: a receipt may still arrive, and
        // dropping to idle sooner would hide a delivered that was on its way.
        WidgetState.SENT -> 20_000L
        WidgetState.FAILED -> 3_000L
        WidgetState.DELIVERED, WidgetState.SEEN -> 4_000L
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*WidgetStateTest*'`
Expected: PASS.

- [ ] **Step 6: Render the new states**

In `app/src/main/java/com/lovebutton/app/widget/MessageWidget.kt`, replace the icon selection:

```kotlin
        val icon = when (state) {
            WidgetState.IDLE, WidgetState.FAILED -> outlineIconFor(msgId)
            WidgetState.SENDING -> halfIconFor(msgId)
            WidgetState.SENT -> filledIconFor(msgId)
            WidgetState.DELIVERED -> deliveredIconFor(msgId)
            WidgetState.SEEN -> seenIconFor(msgId)
        }
```

and add the two lookups beside the existing ones:

```kotlin
private fun deliveredIconFor(msgId: Int): Int = when (msgId) {
    1 -> R.drawable.ic_heart_delivered
    2 -> R.drawable.ic_bubble_delivered
    3 -> R.drawable.ic_paw_delivered
    4 -> R.drawable.ic_call_delivered
    else -> R.drawable.ic_heart_delivered
}

private fun seenIconFor(msgId: Int): Int = when (msgId) {
    1 -> R.drawable.ic_heart_seen
    2 -> R.drawable.ic_bubble_seen
    3 -> R.drawable.ic_paw_seen
    4 -> R.drawable.ic_call_seen
    else -> R.drawable.ic_heart_seen
}
```

- [ ] **Step 7: Handle the receipt push**

In `app/src/main/java/com/lovebutton/app/push/PushService.kt`, replace the `else -> Unit` branch:

```kotlin
            "receipt" -> {
                // Never a notification. A phone that buzzes when she reads a
                // message is a phone nobody wants (spec §6.4).
                val sendId = data["send_id"] ?: return
                val state = when (data["state"]) {
                    "delivered" -> WidgetState.DELIVERED
                    "seen" -> WidgetState.SEEN
                    else -> return
                }
                CoroutineScope(Dispatchers.Default).launch {
                    val pending = PendingSends(applicationContext)
                    // Null means the window expired or this app never sent it.
                    // Dropping it silently is the spec's answer: a tile lighting
                    // up for something sent an hour ago is confusing.
                    val appWidgetId = pending.widgetFor(sendId) ?: return@launch

                    setWidgetState(applicationContext, appWidgetId, state)
                    if (state == WidgetState.SEEN) pending.forget(sendId)

                    delay(state.holdMillis ?: 0L)
                    setWidgetState(applicationContext, appWidgetId, WidgetState.IDLE)
                }
            }
            else -> Unit
```

with imports:

```kotlin
import com.lovebutton.app.data.PendingSends
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.holdMillis
import com.lovebutton.app.widget.setWidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
```

`delivered` deliberately does **not** forget the entry: `seen` may still arrive within the window and needs the mapping.

- [ ] **Step 8: Build and run the suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 21 tests.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/drawable app/src/main/java/com/lovebutton/app scripts/pixel_icons.py app/src/test/java/com/lovebutton/app/WidgetStateTest.kt
git commit -m "feat(app): show delivered and seen on the widget"
```

---

