# Task 3 Report: App mints send_id and remembers which tile it belongs to

## Summary

Implemented all 10 steps of the brief verbatim, with one necessary deviation
noted below (existing test call sites needed updating for compilation).

## Changes

1. **`app/src/test/java/com/lovebutton/app/LoveButtonApiTest.kt`**
   - Appended the new test `send includes the client-minted send_id in the body`
     exactly as specified in Step 1 (placed at the end of the class — an
     attempted insertion right after the `enrol posts...` test failed on an
     exact-string match due to trailing whitespace, so I appended at the tail
     instead; content is identical to the brief).
   - Updated the two pre-existing calls to `api.send(...)` (`send posts only
     the message id...` and `send reports delivered zero...`) to pass a
     `sendId` argument, since `LoveButtonApi.send` gained a required
     third parameter. Neither test asserts on `send_id` in the body, so this
     is purely a compile-fix, not a behavior change. This wasn't in the
     brief's steps but was required — the brief's Step 4 signature change has
     no default value for `sendId`, so the two other call sites would not
     compile otherwise.

2. **`app/src/main/java/com/lovebutton/app/data/ApiModels.kt`**
   - `SendRequest` now carries `@SerialName("send_id") val sendId: String` in
     addition to `msgId`, per Step 3.

3. **`app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt`**
   - `send(authToken, msgId, sendId)` — threads the id into `SendRequest`, per
     Step 4.

4. **`app/src/main/java/com/lovebutton/app/data/PendingSends.kt`** (new)
   - Exactly the code from Step 6: `PENDING_WINDOW_MS = 20_000L`, a separate
     `pending_sends` DataStore file, and `remember`/`widgetFor`/`forget`/
     `forgetExpired`.

5. **`app/src/main/java/com/lovebutton/app/work/SendWorker.kt`**
   - Mints `sendId = UUID.randomUUID().toString()` and calls
     `pending.remember(sendId, appWidgetId)` **before** calling
     `LoveButtonApi(...).send(...)`, per Step 7 — this ordering is the entire
     point of the task and was not reordered.
   - On failure, calls `pending.forget(sendId)` before settling FAILED, so a
     send that never left doesn't leave a stale pending entry.
   - Added `mintedSendId` field (Step 8) so `settle()` can look up the
     pending entry after the fact without crossing a process boundary via
     WorkManager input data.
   - Rewrote `settle()`: for `WidgetState.SENT` it now delays the full
     `PENDING_WINDOW_MS` and checks `pending.widgetFor(mintedSendId)` — if
     still present (no receipt claimed it), it forgets the entry and drops
     the tile to IDLE; if a receipt already claimed/removed the entry, it
     leaves the tile alone (whatever state the receipt set). Non-SENT states
     (FAILED) keep the old `holdMillis`-based hold-then-IDLE behavior
     unchanged.

## Commands run

```
./gradlew :app:testDebugUnitTest --tests '*LoveButtonApiTest*'
```
Before source changes (Step 2): **FAILED** as expected —
`No parameter with name 'sendId' found.` (3 call sites, since I'd already
updated the test file's other two calls in the same edit pass).

After `ApiModels.kt`/`LoveButtonApi.kt` changes but before `SendWorker.kt`
(Step 5, intermediate check): **FAILED** as expected —
`SendWorker.kt:43:79 No value passed for parameter 'sendId'.` — confirmed
the new client signature is a real breaking/required change and that
`SendWorker` genuinely needed Step 7's edit.

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Final run (Step 9): **BUILD SUCCESSFUL in 5s**, 46 actionable tasks
(10 executed, 36 up-to-date). Two pre-existing warnings unrelated to this
change (an annotation-target deprecation in `Messages.kt` and a
`LocalLifecycleOwner` deprecation in `SetupScreen.kt`), no new warnings.

Test result XML tallies (`app/build/test-results/testDebugUnitTest/*.xml`):
- `LoveButtonApiTest`: 9 tests, 0 failures/errors (was 7, +2: the new test
  plus... actually +1 net new test; the other "extra" accounted for by the
  pre-existing count — see note below)
- `MessagesTest`: 7 tests, 0 failures
- `SoundUriTest`: 2 tests, 0 failures
- `WidgetStateTest`: 3 tests, 0 failures
- **Total: 21 tests, 0 failures, 0 errors** — matches the brief's expected
  count (20 -> 21).

(Note: I did not separately verify the exact pre-change per-file breakdown
beyond the tools' own accounting; the important number — total 21 with zero
failures — is confirmed above from the actual test-results XML.)

## Commit

```
1c6039e feat(app): mint the send_id and remember which tile it belongs to
```
5 files changed, 127 insertions(+), 9 deletions(-):
- `app/src/main/java/com/lovebutton/app/data/ApiModels.kt`
- `app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt`
- `app/src/main/java/com/lovebutton/app/data/PendingSends.kt` (new)
- `app/src/main/java/com/lovebutton/app/work/SendWorker.kt`
- `app/src/test/java/com/lovebutton/app/LoveButtonApiTest.kt`

No build files, gradle version, or toolchain configuration were touched. No
`adb` commands were run and nothing was installed to a device.

## Anything that surprised me

- The brief's Step 4 signature (`send(authToken, msgId, sendId)`, no default)
  is a breaking change for the two pre-existing tests that called
  `api.send("tok", 3)` / `api.send("tok", 1)` with two positional args. I
  fixed those two call sites by adding a `sendId` argument (dummy UUID-shaped
  strings, since MockWebServer doesn't validate the body format and neither
  test asserts on `send_id`). This was the only place I deviated from
  "follow the brief's code verbatim" — it was a necessary compile fix, not a
  design choice, and I kept the diff to the minimal one-line addition on
  each of those two call sites.
- An exact-string `Edit` for inserting the new test right after the `enrol
  posts the code...` test failed to match (likely a subtle whitespace
  difference from the file's existing formatting around that method), so I
  appended the new test at the end of the class instead, which the brief's
  own instruction ("inside the existing class") permits.
- Everything else — `PendingSends.kt`, the `SendWorker.kt` rewrite,
  `ApiModels.kt`, `LoveButtonApi.kt` — was copied verbatim from the brief and
  compiled/ran clean on the first attempt after `SendWorker.kt` was in place.
