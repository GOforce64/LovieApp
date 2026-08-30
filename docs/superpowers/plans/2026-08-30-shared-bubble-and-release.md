# Shared Bubble and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make both phones' focal bubble show the same thing — the latest message either person sent, named from each phone's own point of view — and then finish milestone 8 and cut the 1.0 release.

**Architecture:** `CurrentSend` stops meaning "my last send" and starts meaning "the latest message between the two of you". A received message is stored as `SEEN` with `fromMe = false`, which reuses the gold artwork, the never-times-out property and the top of the ladder without adding a seventh state. Ordering across two phones is settled by the server's clock alone: `/v1/send` starts returning `sent_at`, which is the one additive server change the feature needs. The copy branches on `fromMe`, never on state, so the six sender-side lines and the guide that explains them are untouched.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore, WorkManager, Firebase Messaging; Cloudflare Workers + Hono + D1 + Vitest; JUnit 4.

**Spec:** No separate spec document — the design was settled in conversation and is recorded in Decisions below. Background: `love-button-spec.md` §4 (invariants), §5.2 (`/v1/send`), §6.1 (screens), §7.1 (the ladder), §10 (milestone 8).

---

## Global Constraints

- **The three security invariants of spec §4 are untouched.** The sender is the authenticated device; the recipient is `3 - from_person`; only the recipient may acknowledge. Nothing in this plan adds a field in which to name a recipient.
- **No message history.** Exactly one record, as now. A shared bubble is still one record — it is not a log, and spec §5.1's ruling that the server keeps no history is not reopened.
- **No new `WidgetState`.** A received message reuses `SEEN`. Adding a seventh state would ripple through the widget art, the guide, `holdMillis` and `advancesTo` for something no widget can ever show.
- **Server time is only ever compared to server time.** The local `at` timestamp feeds the 20-second timeout and nothing else. Mixing the two clocks is the bug this design exists to avoid.
- **The widget is unchanged.** Tiles stay per-send and sender-side. The bubble is the only surface that becomes shared.
- **`ignoreUnknownKeys = true`** (`LoveButtonApi.kt:29`) and `sent_at` is parsed with a default, so the Worker and the APK can be deployed in either order.
- **Nothing secret is committed.** The release keystore, its passwords and `keystore.properties` stay out of git, enforced by `.gitignore` and by the pre-commit hook this plan installs.

---

## Decisions

Settled in conversation before this plan; do not relitigate during execution.

1. **A received message shows the gold "seen" artwork** and reads `"<Partner> sent you this ♡"` — named from each phone's own side.
2. **The server decides the order.** `/v1/send` returns `sent_at`; both phones compare that single clock, so the two bubbles genuinely always agree.
3. **Newest message always wins, even mid-ladder.** A message arriving while your own send is still climbing takes the bubble immediately. The widget tile you tapped still runs its own full ladder, so the moment is not lost, just not in the bubble.
4. **Received records are stored as `SEEN, fromMe = false`** — see the Architecture note above for why that is the whole trick.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `server/src/routes/send.ts` | The send endpoint | Return `sent_at` |
| `server/test/send.test.ts` | Send endpoint tests | Assert `sent_at` matches the row |
| `app/.../data/ApiModels.kt` | Wire types | `sent_at` on response and result |
| `app/.../data/LoveButtonApi.kt` | HTTP client | Carry `sentAt` into `SendResult` |
| `app/.../data/CurrentSend.kt` | **The latest message between you** | Two fields, the contest rule, two new writers |
| `app/.../push/PushService.kt` | Incoming pushes | Write the received record |
| `app/.../work/SendWorker.kt` | The send | Record the server's timestamp |
| `app/.../ui/StateCopy.kt` | The words | Branch on `fromMe`; extract the age label |
| `app/.../ui/SwayingFace.kt` | The state line | Take `fromMe` |
| `app/.../ui/HomeScreen.kt` | The screen | Pass `fromMe` through, branch the settled line |
| `app/src/test/.../SharedBubbleTest.kt` | **New** | The contest rule and the copy branch |

---

## Phase 1 — the shared bubble

### Task 1: The server returns its own clock

**Files:**
- Modify: `server/src/routes/send.ts:121`
- Test: `server/test/send.test.ts`

**Interfaces:**
- Produces: `/v1/send` → `{send_id, delivered, sent_at}`, `sent_at` in epoch **seconds**, the same value written to `sends.sent_at` and pushed to the recipient.

- [ ] **Step 1: Write the failing test**

Add to `server/test/send.test.ts`, following the file's existing helper style:

```ts
it("returns the server timestamp it recorded", async () => {
  const res = await send(app, env, token, 1);
  expect(res.status).toBe(200);
  const body = await res.json<{ send_id: string; delivered: number; sent_at: number }>();

  expect(typeof body.sent_at).toBe("number");

  // The same value three ways: the response, the stored row, and the push the
  // recipient gets. Two phones order their shared bubble by this number, so a
  // response that disagreed with the row would be a clock nobody could trust.
  const row = await env.DB.prepare("SELECT sent_at FROM sends WHERE id = ?")
    .bind(body.send_id)
    .first<{ sent_at: number }>();
  expect(row?.sent_at).toBe(body.sent_at);
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd server && npx vitest run test/send.test.ts`
Expected: FAIL — `expected undefined to be 'number'`.

- [ ] **Step 3: Return it**

In `server/src/routes/send.ts`, replace line 121:

```ts
  return c.json({ send_id: sendId, delivered });
```

with:

```ts
  // sent_at is the ordering clock for the app's shared bubble: both phones sort
  // the latest message by it, and it is the only clock they both see. The same
  // value is already in the sends row and in the recipient's push, so all three
  // agree by construction.
  return c.json({ send_id: sendId, delivered, sent_at: sentAt });
```

- [ ] **Step 4: Run the whole server suite**

Run: `cd server && npx vitest run`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/routes/send.ts server/test/send.test.ts
git commit -m "feat(server): /v1/send returns the clock both phones order by"
```

---

### Task 2: The record becomes the latest message, not my last send

**Files:**
- Modify: `app/src/main/java/com/lovebutton/app/data/CurrentSend.kt`
- Test: `app/src/test/java/com/lovebutton/app/SharedBubbleTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `SendSnapshot.fromMe: Boolean`, `SendSnapshot.serverAt: Long?`;
  `fun receivedWins(current: SendSnapshot?, incomingServerAt: Long): Boolean`;
  `CurrentSend.markSentAt(sendId: String, serverAt: Long)`;
  `CurrentSend.receive(sendId: String, msgId: Int, serverAt: Long)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/lovebutton/app/SharedBubbleTest.kt`:

```kotlin
package com.lovebutton.app

import com.lovebutton.app.data.SendSnapshot
import com.lovebutton.app.data.receivedWins
import com.lovebutton.app.widget.WidgetState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which message owns the bubble.
 *
 * Both phones must land on the same answer, so the contest is decided by the
 * server's clock and nothing else. The local timestamp exists for the
 * twenty-second timeout and is deliberately not consulted here.
 */
class SharedBubbleTest {

    private fun mine(serverAt: Long?, state: WidgetState = WidgetState.SENT) =
        SendSnapshot("s1", 1, state, at = 1_000L, fromMe = true, serverAt = serverAt)

    private fun hers(serverAt: Long) =
        SendSnapshot("s2", 2, WidgetState.SEEN, at = 1_000L, fromMe = false, serverAt = serverAt)

    @Test
    fun `an empty bubble takes whatever arrives`() {
        assertTrue(receivedWins(null, 5_000L))
    }

    @Test
    fun `a message newer than what is showing takes the bubble`() {
        assertTrue(receivedWins(mine(serverAt = 5_000L), 5_001L))
        assertTrue(receivedWins(hers(serverAt = 5_000L), 5_001L))
    }

    @Test
    fun `a message older than what is showing does not`() {
        // Pushes can arrive out of order, and a late one must not drag the
        // bubble backwards to a message that has already been superseded.
        assertFalse(receivedWins(mine(serverAt = 5_000L), 4_999L))
        assertFalse(receivedWins(hers(serverAt = 5_000L), 4_999L))
    }

    @Test
    fun `a message with the very same timestamp does not steal the bubble`() {
        // Strictly newer, so a duplicate delivery of one push is a no-op rather
        // than a rewrite.
        assertFalse(receivedWins(mine(serverAt = 5_000L), 5_000L))
    }

    @Test
    fun `a send still in flight yields to anything that arrives`() {
        // Your own send has no server timestamp until its response lands, so
        // there is nothing to compare. The ruling is that the newest message
        // takes the bubble, and the tile you tapped still runs its own ladder.
        assertTrue(receivedWins(mine(serverAt = null, state = WidgetState.SENDING), 1L))
        assertTrue(receivedWins(mine(serverAt = null, state = WidgetState.SENT), 1L))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SharedBubbleTest*'`
Expected: FAIL to compile — `Unresolved reference: receivedWins`, and `fromMe`/`serverAt` are not parameters of `SendSnapshot`.

- [ ] **Step 3: Extend the record**

In `app/src/main/java/com/lovebutton/app/data/CurrentSend.kt`, add the import:

```kotlin
import androidx.datastore.preferences.core.booleanPreferencesKey
```

Replace `SendSnapshot` with:

```kotlin
/** One message, as the app screen needs to see it — sent by either of you. */
data class SendSnapshot(
    val sendId: String,
    val msgId: Int,
    val state: WidgetState,
    /** This phone's clock, at the tap. Feeds the timeout, never the ordering. */
    val at: Long,
    /** False when the other phone sent it. */
    val fromMe: Boolean = true,
    /** The server's clock. Null only while your own send is still in flight. */
    val serverAt: Long? = null,
)

/**
 * Whether an arriving message takes the bubble from what is already there.
 *
 * The bubble is shared, so both phones have to reach the same answer from the
 * same facts — which means the server's clock decides, and only ever against
 * another server clock. Comparing it to this phone's own `at` would be two
 * clocks pretending to be one.
 *
 * Strictly newer, so a push delivered twice is a no-op rather than a rewrite.
 *
 * A send of your own that has not had its response yet has no server timestamp
 * to compare, and yields: the ruling is that the newest message takes the
 * bubble even mid-ladder, and the tile you tapped still finishes its own.
 */
fun receivedWins(current: SendSnapshot?, incomingServerAt: Long): Boolean = when {
    current == null -> true
    current.serverAt == null -> true
    else -> incomingServerAt > current.serverAt
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*SharedBubbleTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Store the two new fields and add the two writers**

In the same file, add to `object Keys`:

```kotlin
        val FROM_ME = booleanPreferencesKey("from_me")
        val SERVER_AT = longPreferencesKey("server_at")
```

Replace the `flow` body's snapshot construction with:

```kotlin
        SendSnapshot(
            sendId,
            msgId,
            fromName(prefs[Keys.STATE]),
            at,
            fromMe = prefs[Keys.FROM_ME] ?: true,
            serverAt = prefs[Keys.SERVER_AT],
        )
```

In `start`, add beside the other writes so a new send clears the previous one's provenance:

```kotlin
            prefs[Keys.FROM_ME] = true
            prefs.remove(Keys.SERVER_AT)
```

Then add both writers after `update`:

```kotlin
    /**
     * Records the server's timestamp for a send of ours, once the response
     * carries it. Until this lands the record has no place in the ordering, and
     * anything arriving takes the bubble.
     */
    suspend fun markSentAt(sendId: String, serverAt: Long) {
        context.currentSendStore.edit { prefs ->
            if (prefs[Keys.SEND_ID] != sendId) return@edit
            prefs[Keys.SERVER_AT] = serverAt
        }
    }

    /**
     * Puts a message the other phone sent into the bubble, if it wins.
     *
     * Stored as SEEN because that is exactly what it is: it arrived, and you are
     * looking at it. That also buys the gold artwork, immunity from the
     * twenty-second timeout, and the top of the ladder — so no receipt and no
     * stale push can move it afterwards.
     *
     * The read and the compare happen inside one edit block, so a send of your
     * own cannot land between them and be silently overwritten.
     */
    suspend fun receive(sendId: String, msgId: Int, serverAt: Long) {
        context.currentSendStore.edit { prefs ->
            val current = prefs[Keys.SEND_ID]?.let {
                SendSnapshot(
                    it,
                    prefs[Keys.MSG_ID] ?: return@let null,
                    fromName(prefs[Keys.STATE]),
                    prefs[Keys.AT] ?: 0L,
                    fromMe = prefs[Keys.FROM_ME] ?: true,
                    serverAt = prefs[Keys.SERVER_AT],
                )
            }
            if (!receivedWins(current, serverAt)) return@edit

            prefs[Keys.SEND_ID] = sendId
            prefs[Keys.MSG_ID] = msgId
            prefs[Keys.STATE] = WidgetState.SEEN.name
            prefs[Keys.AT] = System.currentTimeMillis()
            prefs[Keys.FROM_ME] = false
            prefs[Keys.SERVER_AT] = serverAt
        }
    }
```

- [ ] **Step 6: Run the whole app suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. `CurrentSendTest`'s existing four-argument `SendSnapshot` uses still compile, because both new fields have defaults.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/data/CurrentSend.kt \
        app/src/test/java/com/lovebutton/app/SharedBubbleTest.kt
git commit -m "feat(app): the record becomes the latest message, not my last send"
```

---

### Task 3: Both ends write to it

**Files:**
- Modify: `app/src/main/java/com/lovebutton/app/data/ApiModels.kt`
- Modify: `app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt:112`
- Modify: `app/src/main/java/com/lovebutton/app/work/SendWorker.kt`
- Modify: `app/src/main/java/com/lovebutton/app/push/PushService.kt`

**Interfaces:**
- Consumes: `markSentAt`, `receive` from Task 2; `sent_at` from Task 1.
- Produces: `SendResult.sentAt: Long` (epoch **seconds**, 0 when the server has not been deployed yet).

- [ ] **Step 1: Carry the timestamp through the wire types**

In `ApiModels.kt`, replace `SendResponse` and `SendResult`:

```kotlin
@Serializable
data class SendResponse(
    @SerialName("send_id") val sendId: String,
    val delivered: Int,
    // Defaulted, so an app talking to a Worker that predates this field still
    // parses. Zero means "no server clock", and the caller skips the ordering.
    @SerialName("sent_at") val sentAt: Long = 0L,
)
```

```kotlin
/** What the caller of [LoveButtonApi.send] actually needs. */
data class SendResult(val sendId: String, val delivered: Int, val sentAt: Long = 0L)
```

In `LoveButtonApi.kt`, replace line 112:

```kotlin
            return SendResult(parsed.sendId, parsed.delivered)
```

with:

```kotlin
            return SendResult(parsed.sendId, parsed.delivered, parsed.sentAt)
```

- [ ] **Step 2: Record it after a successful send**

In `SendWorker.kt`, replace the success path inside the `try`:

```kotlin
        return try {
            val result = LoveButtonApi(BuildConfig.API_BASE_URL).send(enrolment.authToken, msgId, sendId)
            currentSend.update(sendId, WidgetState.SENT)
            // The server's clock, which is what both phones order the shared
            // bubble by. Zero means this Worker predates the field; the record
            // then has no place in the ordering and anything arriving wins.
            if (result.sentAt > 0L) currentSend.markSentAt(sendId, result.sentAt * 1000L)
            settle(appWidgetId, WidgetState.SENT, Result.success())
```

- [ ] **Step 3: Write the received record when a message arrives**

In `PushService.kt`, replace the body of the `"msg"` branch:

```kotlin
            "msg" -> {
                val msgId = data["msg_id"]?.toIntOrNull() ?: return
                val fromName = data["from_name"] ?: "Someone"
                val sendId = data["send_id"]
                val sentAt = data["sent_at"]?.toLongOrNull()
                postMessageNotification(applicationContext, msgId, fromName)
                // After posting, not before: the receipt says "it arrived and she
                // can see it", and a receipt for a notification that failed to
                // post would be a lie.
                if (sendId != null) {
                    // The bubble is shared, so her message becomes what both
                    // phones show — if the server's clock says it is the newest.
                    if (sentAt != null) {
                        CoroutineScope(Dispatchers.Default).launch {
                            CurrentSend(applicationContext).receive(sendId, msgId, sentAt * 1000L)
                        }
                    }
                    ReceiptWorker.enqueue(applicationContext, sendId, "delivered")
                    reportOrRememberSeen(sendId)
                }
            }
```

- [ ] **Step 4: Build and run the suite**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: all pass, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/data/ApiModels.kt \
        app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt \
        app/src/main/java/com/lovebutton/app/work/SendWorker.kt \
        app/src/main/java/com/lovebutton/app/push/PushService.kt
git commit -m "feat(app): a message she sends lands in the bubble too"
```

---

### Task 4: It says who it came from

**Files:**
- Modify: `app/src/main/java/com/lovebutton/app/ui/StateCopy.kt`
- Modify: `app/src/main/java/com/lovebutton/app/ui/SwayingFace.kt`
- Modify: `app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt`
- Test: `app/src/test/java/com/lovebutton/app/SharedBubbleTest.kt`

**Interfaces:**
- Consumes: `SendSnapshot.fromMe`.
- Produces: `guideWords(state, partnerName, fromMe = true)`, `guideFace(state, fromMe = true)`, `ageLabel(ageMillis)`, `receivedLine(messageText, ageMillis)`, `SwayingStateLine(state, partnerName, fromMe = true, modifier)`.

- [ ] **Step 1: Write the failing test**

Add to `SharedBubbleTest.kt` — new imports first:

```kotlin
import com.lovebutton.app.ui.guideFace
import com.lovebutton.app.ui.guideWords
import com.lovebutton.app.ui.receivedLine
import org.junit.Assert.assertEquals
```

and these tests:

```kotlin
    @Test
    fun `a message she sent names her, in every state`() {
        // The stored state of a received message is SEEN, but the copy must not
        // depend on that — it branches on who sent it, so a state that somehow
        // arrived on a received record could never produce "Wifey looked at it"
        // on Wifey's own phone.
        WidgetState.entries.forEach { state ->
            val words = guideWords(state, "Wifey", fromMe = false)
            assertEquals("Wifey sent you this", words)
            assertTrue("$state has no face", guideFace(state, fromMe = false).isNotBlank())
        }
    }

    @Test
    fun `my own lines are untouched`() {
        WidgetState.entries.forEach { state ->
            assertEquals(guideWords(state, "Wifey"), guideWords(state, "Wifey", fromMe = true))
            assertEquals(guideFace(state), guideFace(state, fromMe = true))
        }
    }

    @Test
    fun `the line under a received message names the message, not the reader`() {
        // "Wifey saw your Miss you" is what the sender's side says. On the side
        // that received it, the only useful facts are what it was and how long
        // ago — saying she saw it would be telling her about herself.
        val line = receivedLine("Miss you", 5 * 60_000L)
        assertTrue(line, line.contains("Miss you"))
        assertTrue(line, line.contains("5m"))
        assertFalse(line, line.contains("saw"))
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SharedBubbleTest*'`
Expected: FAIL to compile — `Unresolved reference: receivedLine`, and too many arguments for `guideWords`.

- [ ] **Step 3: Branch the copy**

In `StateCopy.kt`, replace `guideWords`, `guideFace` and `coldOpenLine` with:

```kotlin
fun guideWords(state: WidgetState, partnerName: String, fromMe: Boolean = true): String =
    if (!fromMe) "$partnerName sent you this" else when (state) {
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
 * so a face that only appeared once something was in flight would leave the app
 * perfectly still exactly when a reader is asking themselves whether it works.
 *
 * Every state must have one — a blank here is a state that sits frozen, which
 * `every state has a face to sway` in StateCopyTest exists to catch.
 */
fun guideFace(state: WidgetState, fromMe: Boolean = true): String =
    if (!fromMe) "♡" else when (state) {
        WidgetState.IDLE -> "(・ω・)"
        WidgetState.SENDING -> "0o0"
        WidgetState.SENT -> "(• ε •)"
        WidgetState.DELIVERED -> ":3"
        WidgetState.SEEN -> "(>^o^)>"
        WidgetState.FAILED -> "（◞‸◟）"
    }

/** How long ago, in the one phrasing both settled lines use. */
fun ageLabel(ageMillis: Long): String = when {
    ageMillis < 60_000L -> "just now"
    ageMillis < 3_600_000L -> "${ageMillis / 60_000L}m ago"
    ageMillis < 86_400_000L -> "${ageMillis / 3_600_000L}h ago"
    else -> "${ageMillis / 86_400_000L}d ago"
}

/**
 * What the focal area says on a cold open, when nothing was just sent.
 *
 * The app remembers the last send where the widget forgets it (spec §4.3),
 * because remembering is the app screen's job and a permanently lit button on
 * the home screen would only be noise.
 */
fun coldOpenLine(partnerName: String, messageText: String, ageMillis: Long): String =
    "$partnerName saw your \"$messageText\" · ${ageLabel(ageMillis)}"

/**
 * The same slot, for a message that came the other way.
 *
 * Says what it was and how long ago, and nothing about who read it: on this side
 * the reader is the person holding the phone, and telling her she saw it would
 * be reporting her own action back to her.
 */
fun receivedLine(messageText: String, ageMillis: Long): String =
    "\"$messageText\" · ${ageLabel(ageMillis)}"
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*SharedBubbleTest*' --tests '*StateCopyTest*'`
Expected: PASS. `guideLine` is unchanged and still two-argument, so the guide and its pinned lines are unaffected.

- [ ] **Step 5: Pass it through the focal line**

In `SwayingFace.kt`, add the parameter and forward it:

```kotlin
@Composable
fun SwayingStateLine(
    state: WidgetState,
    partnerName: String,
    fromMe: Boolean = true,
    modifier: Modifier = Modifier,
) {
```

and inside, change the two copy calls to `guideFace(state, fromMe)` and `guideWords(state, partnerName, fromMe)`.

In `HomeScreen.kt`, pass it at the call site and branch the settled line. Replace the `SwayingStateLine(...)` call's arguments with:

```kotlin
                        state = state,
                        partnerName = partnerName,
                        fromMe = snapshot?.fromMe ?: true,
```

and replace the settled-line `Text(...)` with:

```kotlin
                            Text(
                                text = if (settled.fromMe) {
                                    coldOpenLine(
                                        partnerName,
                                        messageForId(settled.msgId)?.text ?: "",
                                        System.currentTimeMillis() - settled.at,
                                    )
                                } else {
                                    receivedLine(
                                        messageForId(settled.msgId)?.text ?: "",
                                        System.currentTimeMillis() - settled.at,
                                    )
                                },
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center,
                            )
```

- [ ] **Step 6: Build and run everything**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: all pass, BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/ui/StateCopy.kt \
        app/src/main/java/com/lovebutton/app/ui/SwayingFace.kt \
        app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt \
        app/src/test/java/com/lovebutton/app/SharedBubbleTest.kt
git commit -m "feat(ui): the bubble says who the message came from"
```

---

### Task 5: Deploy, install, and prove it on both phones

**Files:** none — this is the gate.

- [ ] **Step 1: Deploy the Worker**

```bash
cd server && npx wrangler deploy
```

Expected: a deployed version id. `curl "$BASE_URL/health"` returns `{"ok":true}`.

- [ ] **Step 2: Install on both phones**

Phone B's wireless address moves; find it rather than trusting a stale one.

```bash
adb devices -l
ANDROID_SERIAL=923262ff ./gradlew :app:installDebug
ANDROID_SERIAL=<phone B> ./gradlew :app:installDebug
```

- [ ] **Step 3: Prove the bubble is shared, both directions**

With both apps open on screen:

1. Send from **A**. A's bubble climbs its ladder. **B's bubble must switch to that message**, in gold, reading `"<A's name> sent you this ♡"`.
2. Send from **B**. Now A's bubble must switch to it, gold, naming B.
3. Close both apps, send from A, then open B cold. B must still show it — the record is persisted, not a live event.
4. Send from A and, before it settles, send from B. Both phones must end on **B's** message, because the server stamped it later. This is the case the whole `sent_at` change exists for.

Report each of the four. Do not mark this task done on tests alone.

- [ ] **Step 4: Commit nothing, and push the branch**

```bash
git push -u origin feat/cuter-labels-and-swaying-faces
```

---

## Phase 2 — milestone 8, and the release

### Task 6: Narrow the device deletion

**Files:**
- Modify: `server/src/fcm.ts:44-46`
- Test: `server/test/fcm.test.ts`

Spec §12 names this as the trap to revisit at milestone 8: `INVALID_ARGUMENT` is returned both for a dead token and for a malformed *request*, and the second case deletes **every** one of the recipient's device rows at once — including the row her bearer token depends on, so her app gets 401 and can only recover by re-enrolling by hand.

- [ ] **Step 1: Write the failing test**

Add to `server/test/fcm.test.ts`:

```ts
it("does not treat a malformed request as a dead token", () => {
  // FCM returns INVALID_ARGUMENT for a bad request as well as a bad token. The
  // first would fail identically for every one of her devices and delete all of
  // them, leaving her unable to recover without the enrolment code (spec §12).
  expect(isDeadToken({ error: { details: [{ errorCode: "INVALID_ARGUMENT" }] } })).toBe(false);
});

it("still treats an unregistered token as dead", () => {
  expect(isDeadToken({ error: { details: [{ errorCode: "UNREGISTERED" }] } })).toBe(true);
});
```

Match the existing file's import of the function under test; if it is not exported, export it.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd server && npx vitest run test/fcm.test.ts`
Expected: the first test FAILS, returning true.

- [ ] **Step 3: Narrow it**

In `server/src/fcm.ts`, replace lines 44-46's condition with:

```ts
  // UNREGISTERED only. INVALID_ARGUMENT is ambiguous — FCM returns it for a
  // malformed request as readily as for a bad token, and a malformed request
  // fails identically for every one of the recipient's devices. Acting on it
  // deleted all her rows at once, and with them the row her bearer token is
  // looked up by: her app then gets 401 on everything and the only way back is
  // re-enrolling by hand with the code from a password manager. Spec §12 flags
  // this as the milestone 8 decision, and this is that decision.
  return detailCodes.includes("UNREGISTERED");
```

- [ ] **Step 4: Run the server suite**

Run: `cd server && npx vitest run`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/fcm.ts server/test/fcm.test.ts
git commit -m "fix(server): only UNREGISTERED means the token is dead"
```

---

### Task 7: The pre-commit hook, and a README

**Files:**
- Create: `.githooks/pre-commit`
- Create: `README.md`
- Modify: `docs/MANUAL-SETUP.md`, `server/README.md`

- [ ] **Step 1: Write the hook**

Create `.githooks/pre-commit`, then `chmod +x` it:

```bash
#!/usr/bin/env bash
# Blocks the two things that must never reach a public repo.
#
# Not a substitute for .gitignore — this catches the case .gitignore cannot:
# a key pasted into a file that is meant to be committed.
set -euo pipefail

staged=$(git diff --cached --name-only --diff-filter=ACM)
[ -z "$staged" ] && exit 0

if git diff --cached -U0 -- $staged | grep -qE '^\+.*(-----BEGIN [A-Z ]*PRIVATE KEY-----|"type": *"service_account")'; then
  echo "pre-commit: a private key or service account JSON is in this commit. Refusing."
  exit 1
fi

for f in $staged; do
  case "$f" in
    *.jks|*.keystore|keystore.properties|local.properties|.dev.vars|app/google-services.json|*service-account*.json)
      echo "pre-commit: $f must never be committed. Refusing."
      exit 1 ;;
  esac
done
```

Then point git at it, and record that in the README:

```bash
chmod +x .githooks/pre-commit
git config core.hooksPath .githooks
```

- [ ] **Step 2: Verify the hook actually blocks**

```bash
printf '%s\n' '-----BEGIN PRIVATE KEY-----' > /tmp/leak-probe.txt
cp /tmp/leak-probe.txt ./leak-probe.txt
git add leak-probe.txt
git commit -m "probe" ; echo "exit: $?"
```

Expected: the commit is refused with the pre-commit message and a non-zero exit. Then clean up:

```bash
git reset HEAD leak-probe.txt && rm leak-probe.txt
```

- [ ] **Step 3: Write the root README**

Create `README.md`. It must cover, because nothing else in the repo does: what the app is; that it is for exactly two people; the $0 constraint; the architecture in three lines; that **force-stopping the app kills FCM delivery until it is reopened**, which spec §8 requires be documented here; that `app/google-services.json` is not in the repo and `app/google-services.json.example` shows its shape; how to build; and pointers to `love-button-spec.md` and `docs/MANUAL-SETUP.md`. Enable the hook in the setup section:

```bash
git config core.hooksPath .githooks
```

- [ ] **Step 4: Correct the two stale documents**

`docs/MANUAL-SETUP.md` claims the Firebase project and the D1 database "do not exist" and leaves blocks A and E unticked; the repo is pushed and the four sounds have been in `res/raw` since 2026-08-24. `server/README.md` says the Worker "has not been deployed yet" and that `npm run migrate:remote` "has not yet been run". Both are false. Update the status blocks to what is true, and tick the boxes that are done.

- [ ] **Step 5: Commit**

```bash
git add README.md .githooks/pre-commit docs/MANUAL-SETUP.md server/README.md
git commit -m "docs: a front door, a hook that refuses secrets, and two corrections"
```

---

### Task 8: A release keystore and a signed build

**Files:**
- Modify: `app/build.gradle.kts`
- Create (never committed): `love-button-release.jks`, `keystore.properties`

**This is the one irreversible step.** A release-signed APK cannot be installed over the debug-signed builds on either phone: Android refuses a signature change. The cutover therefore needs an uninstall and a re-enrol with the codes from the password manager, on both phones. Losing this keystore means the app can never be updated in place again (spec §4.1).

- [ ] **Step 1: Generate the keystore and its password**

```bash
PASS="$(openssl rand -base64 33)"
keytool -genkeypair -v \
  -keystore love-button-release.jks \
  -alias love-button \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Love Button, OU=Personal, O=Personal, L=, ST=, C=GR"

cat > keystore.properties <<EOF
storeFile=love-button-release.jks
storePassword=$PASS
keyAlias=love-button
keyPassword=$PASS
EOF
chmod 600 keystore.properties love-button-release.jks
```

- [ ] **Step 2: Confirm both are untracked**

```bash
git status --porcelain --ignored | grep -E "love-button-release.jks|keystore.properties"
```

Expected: both listed as ignored (`!!`). If either shows as untracked-but-not-ignored, stop — `.gitignore` is not covering them.

- [ ] **Step 3: Wire the signing config**

In `app/build.gradle.kts`, after the `apiBaseUrl` block, add:

```kotlin
/**
 * Release signing, read from a file that is never committed.
 *
 * Absent on a machine that only builds debug, and that must not fail the build —
 * so the config is only registered when the file is there, and `assembleRelease`
 * is what needs it.
 */
val keystoreProps: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
```

and inside `android { }`:

```kotlin
    signingConfigs {
        keystoreProps?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
```

and in `buildTypes { release { ... } }`, add as the first line:

```kotlin
            keystoreProps?.let { signingConfig = signingConfigs.getByName("release") }
```

- [ ] **Step 4: Bump the version**

In `app/build.gradle.kts`:

```kotlin
        versionCode = 2
        versionName = "1.0"
```

- [ ] **Step 5: Build and verify the signature**

```bash
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Expected: BUILD SUCCESSFUL, and the certificate's `CN=Love Button`. If `apksigner` is not on PATH, use the one under `$ANDROID_HOME/build-tools/*/apksigner`.

- [ ] **Step 6: Back the keystore up outside the repo**

```bash
cp love-button-release.jks keystore.properties ~/secrets/
./scripts/secrets-backup.sh
```

Then tell the user, in plain terms, that losing this file ends the app's ability to be updated on her phone, and that `~/secrets` and the encrypted bundle are the only two copies.

- [ ] **Step 7: Commit the build change only**

```bash
git add app/build.gradle.kts
git commit -m "build(app): sign the release, and call it 1.0"
```

---

### Task 9: Push, and cut the release

- [ ] **Step 1: Run everything, one last time**

```bash
./gradlew :app:testDebugUnitTest
cd server && npx vitest run && cd ..
```

Expected: both suites fully green. **If anything fails, stop here** — the instruction to release is conditional on the tests being good.

- [ ] **Step 2: Confirm no secret is staged or tracked**

```bash
git ls-files | grep -E "\.jks$|keystore.properties|google-services.json$|service-account|\.dev\.vars" || echo "clean"
```

Expected: `clean`.

- [ ] **Step 3: Merge to main and push**

```bash
git checkout main
git merge --no-ff feat/cuter-labels-and-swaying-faces -m "Release 1.0: cuter labels, a living face, honest failures, one shared bubble"
git push origin main
```

- [ ] **Step 4: Cut the GitHub release**

```bash
gh release create v1.0 \
  app/build/outputs/apk/release/app-release.apk \
  --title "Love Button 1.0" \
  --notes "<written from the commits in this branch>"
```

The notes should say what changed for the two people using it, not what changed in the code.

- [ ] **Step 5: Report what remains manual**

The phones are still running debug-signed 0.1. Moving them to the signed 1.0 needs an uninstall, an install, and a re-enrol with both codes — which only the user can do, because only they have the codes. Say so plainly rather than doing it.

---

## Out of scope

- Installing the signed release on either phone. It needs an uninstall and a re-enrol; the codes are the user's.
- Rotating the enrolment codes. Spec §10.1: rotating does not revoke an issued device token, so it is not a security step on its own.
- The remaining polish from the earlier review — the panda in the notification shade, the "no phone signed in" line, the widget picker previews, the `bodySmall`/`titleSmall` Roboto leak on Delivery setup, and the focal icon's missing contentDescription. All still open, none release-blocking.

---

## Self-Review

**1. Coverage**

| Requirement | Task |
|---|---|
| Both phones show the same message | 2, 3 |
| The latest one, whoever sent it | 1 (clock), 2 (contest rule) |
| Partner-specific text on each phone | 4 |
| Received shows gold, named | 2 (stored SEEN), 4 (copy) |
| Milestone 8: `INVALID_ARGUMENT` | 6 |
| Milestone 8: README + force-stop note | 7 |
| Milestone 8: pre-commit hook | 7 |
| Milestone 8: stale docs | 7 |
| Milestone 8: keystore + signing | 8 |
| Push to GitHub, release | 5 (branch), 9 (main + release) |

**2. Placeholder scan.** One deliberate gap: Task 7 Step 3 and Step 4 describe the README and the doc corrections by required content rather than by literal text, because both are prose about the project's true current state and cannot be pinned before the state is re-read. Every code step carries literal code. Task 9's release notes are likewise written from the merged history.

**3. Type consistency**

- `SendSnapshot` gains `fromMe` and `serverAt`, both defaulted — existing four-argument constructions in `CurrentSendTest` and `SendTimeoutTest` keep compiling.
- `receivedWins(current, incomingServerAt)` — same signature in Task 2's implementation and both tests.
- `SendResult(sendId, delivered, sentAt = 0)` — `sentAt` defaulted, so `LoveButtonApiTest`'s existing constructions still compile.
- `guideWords(state, partnerName, fromMe = true)` and `guideFace(state, fromMe = true)` — defaults keep `guideLine`, `GuideScreen` and every pinned line in `StateCopyTest` working untouched.
- `SwayingStateLine(state, partnerName, fromMe, modifier)` — `modifier` stays last, and `HomeScreen` passes by name.
- `ageLabel` is extracted from `coldOpenLine` and shared with `receivedLine`; `coldOpenLine`'s output is unchanged, which `StateCopyTest` already pins.

**4. Known risks**

- **Task 3's `receive` runs in a bare `CoroutineScope`** launched from `onMessageReceived`, matching what the receipt branch beside it already does. A process killed in that instant loses the bubble update but not the notification, which is the right way round.
- **Task 5 Step 3 case 4 is timing-dependent.** Two sends seconds apart is the realistic version of "simultaneous"; if both phones agree on the later one, the ordering works. A true tie is not reachable by hand.
- **Task 8 is irreversible for the phones.** Covered in the task's own preamble.
- **`sent_at` is seconds server-side and milliseconds in the app.** Task 3 multiplies in both places it crosses. A missed multiplication would put every received message in 1970 and it would never win the bubble — visible immediately in Task 5 Step 3 case 1.
