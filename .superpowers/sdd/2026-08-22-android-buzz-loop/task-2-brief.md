## Task 2: The message catalogue

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/data/Messages.kt`
- Create: `app/src/test/java/com/lovebutton/app/MessagesTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `data class LoveMessage(val id: Int, val text: String, val channelId: String)`
  - `val MESSAGES: List<LoveMessage>`
  - `fun messageForId(id: Int): LoveMessage?`
  - `const val DEV_CHANNEL_ID = "dev_buzz_v1"`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/lovebutton/app/MessagesTest.kt`:

```kotlin
package com.lovebutton.app

import com.lovebutton.app.data.DEV_CHANNEL_ID
import com.lovebutton.app.data.MESSAGES
import com.lovebutton.app.data.messageForId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `every message uses the temporary dev channel for now`() {
        // Channel sounds are frozen at creation (spec 6.3), so the real per-message
        // channels are not created until their sounds are final in milestone 4.
        MESSAGES.forEach { message ->
            assertEquals(DEV_CHANNEL_ID, message.channelId)
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.lovebutton.app.MessagesTest"`
Expected: FAIL — unresolved reference `com.lovebutton.app.data`

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/lovebutton/app/data/Messages.kt`:

```kotlin
package com.lovebutton.app.data

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
)

/**
 * A deliberately temporary notification channel.
 *
 * Android freezes a channel's sound when the channel is created and will not let
 * you change it afterwards (spec 6.3). The four real sounds are not chosen until
 * milestone 4, so creating `msg_1`..`msg_4` now would burn those channel ids with
 * the default sound permanently. This throwaway id is deleted and replaced when
 * the real channels arrive.
 */
const val DEV_CHANNEL_ID = "dev_buzz_v1"

val MESSAGES: List<LoveMessage> = listOf(
    LoveMessage(1, "I love you", DEV_CHANNEL_ID),
    LoveMessage(2, "Thinking of you", DEV_CHANNEL_ID),
    LoveMessage(3, "Miss you", DEV_CHANNEL_ID),
    LoveMessage(4, "Call me when you can", DEV_CHANNEL_ID),
)

/** Null when this build does not know the id — an older app, a newer message. */
fun messageForId(id: Int): LoveMessage? = MESSAGES.firstOrNull { it.id == id }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.lovebutton.app.MessagesTest"`
Expected: all five tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/data/Messages.kt app/src/test/java/com/lovebutton/app/MessagesTest.kt
git commit -m "feat(app): add local message catalogue"
```

---

