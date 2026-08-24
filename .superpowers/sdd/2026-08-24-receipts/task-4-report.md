# Task 4 report: Her phone reports delivered and seen

## Summary

Implemented all 5 steps from the brief exactly as specified, with the one
correction from the assignment applied to `ReceiptWorker.doWork()`.

## Changes

1. **`app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt`** — added
   `receipt(authToken, sendId, state): Boolean`, placed after `send` and before
   `errorMessage`, following the same `post`/`execute` pattern as
   `registerDevice`.

2. **`app/src/main/java/com/lovebutton/app/data/ApiModels.kt`** — added
   `ReceiptRequest(sendId, state)` with `@SerialName("send_id")`, placed
   directly above `ApiError`.

3. **`app/src/main/java/com/lovebutton/app/work/ReceiptWorker.kt`** (new) —
   `CoroutineWorker` reading `send_id`/`state` from input data, resolving the
   current enrolment via `Prefs`, calling `LoveButtonApi.receipt`, and a
   `companion object` with `enqueue(context, sendId, state)` that builds a
   `OneTimeWorkRequest` constrained to `NetworkType.CONNECTED`.

   Applied the assignment's correction to `doWork()`: rather than binding the
   `Boolean` result to `ok` and writing
   `Result.success().takeIf { ok } ?: Result.success()` (a no-op decision),
   the call's result is discarded and `Result.success()` is returned
   unconditionally after the call, with a comment explaining why a rejected
   receipt (403/404) is not retried while a thrown exception is (caught below,
   returns `Result.retry()`).

4. **`app/src/main/java/com/lovebutton/app/push/PushService.kt`** — `"msg"`
   branch now extracts `send_id` from the data payload, passes it to
   `postMessageNotification`, and — only after the notification is posted —
   enqueues a `ReceiptWorker` with state `"delivered"` if `sendId` is
   non-null. Added the `ReceiptWorker` import. Left the `else -> Unit` branch
   untouched, per the brief's note that a later task adds the `"receipt"`
   case there.

5. **`app/src/main/java/com/lovebutton/app/push/Notifications.kt`** — added
   `const val EXTRA_SEND_ID = "send_id"`; `postMessageNotification` gained a
   fourth parameter `sendId: String? = null` (default preserves the existing
   3-arg call sites, though `PushService` now passes it explicitly); the
   `openApp` `PendingIntent` now uses `sendId?.hashCode() ?: 0` as its request
   code (so distinct sends get distinct intents, per the brief's comment
   about `FLAG_UPDATE_CURRENT` clobbering older notifications' extras) and
   carries `EXTRA_SEND_ID` as a putExtra.

6. **`app/src/main/java/com/lovebutton/app/MainActivity.kt`** — added
   `reportSeenFrom(intent)` call in `onCreate` before `setContent`; added
   `onNewIntent` (calls `setIntent` then `reportSeenFrom`); added private
   `reportSeenFrom(intent: Intent?)` which reads `EXTRA_SEND_ID`, enqueues a
   `ReceiptWorker` with state `"seen"`, and clears the extra via
   `intent.removeExtra(EXTRA_SEND_ID)` to avoid double-reporting on a
   configuration change. Added imports for `android.content.Intent`,
   `com.lovebutton.app.push.EXTRA_SEND_ID`, and
   `com.lovebutton.app.work.ReceiptWorker`.

## Commands run

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Output: `BUILD SUCCESSFUL in 5s`, `46 actionable tasks: 8 executed, 38
up-to-date`. No test task failures reported (Gradle would have failed the
build and printed failures otherwise — `testDebugUnitTest` ran quietly to
success).

Verified the total test count directly from the XML results, since Gradle's
own summary line doesn't appear in this output mode:

```
find app/build/test-results -name "*.xml" | xargs grep -o 'tests="[0-9]*"' | awk -F'"' '{sum+=$2} END {print sum}'
```

Result: `21` — matches the expected count exactly (this task adds no new
tests, as noted in the assignment).

## Anything that surprised me

Nothing surprising. The brief's code compiled and integrated cleanly against
the existing `LoveButtonApi`, `ApiModels`, `PushService`, `Notifications`,
and `MainActivity` as they stood on this branch. The only deviation from the
brief's literal text was the pre-specified correction to
`ReceiptWorker.doWork()`'s final expression, which was applied as instructed.

## Commit

```
629cd78 feat(app): report delivered on arrival and seen on tap
```

6 files changed: `MainActivity.kt`, `data/ApiModels.kt`, `data/LoveButtonApi.kt`,
`push/Notifications.kt`, `push/PushService.kt` (modified), and
`work/ReceiptWorker.kt` (new).
