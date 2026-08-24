# Task 5 report: Enrolment screen

## Status: DONE

Commit SHA: `fa6a0a6c21010baf7f73d18fb4f8811cda2fa3d9`
Branch: `feat/android-buzz-loop`

## Files created

- `app/src/main/java/com/lovebutton/app/ui/EnrolScreen.kt` (new, 119 lines)
  Written verbatim from the brief's Step 1. `@Composable fun EnrolScreen(onEnrolled: () -> Unit)`.
  Fetches the FCM token via `FirebaseMessaging.getInstance().token.await()`, calls
  `LoveButtonApi.enrol(code, fcmToken, label)`, and switches on all four `EnrolResult`
  cases without collapsing any of them:
  - `Ok` → `Prefs.saveEnrolment(authToken, person, partnerName)` then `onEnrolled()`
  - `InvalidCode` → "That code is not valid."
  - `RateLimited` → "Too many attempts. Try again in an hour."
  - `Failed(message)` → shows `result.message`
  A separate `catch` around the FCM-token fetch shows a distinct message
  ("Could not get a push token from Google...") if Play Services is unavailable,
  so the two failure modes (can't get a token vs. server rejected the code) are
  never conflated.
  The bearer token is never placed in a `Text`, a log call, or the `error` state —
  it only ever flows into `prefs.saveEnrolment(...)`.

## Files modified

- `app/src/main/java/com/lovebutton/app/MainActivity.kt` — replaced wholesale, verbatim
  from the brief's Step 3. `Root()` composable does three-way routing on
  `(loaded, enrolment)`:
  - `!loaded` → `CircularProgressIndicator` (DataStore hasn't answered yet — this
    is deliberately a distinct branch from "answered, not enrolled", per the task
    constraint, so an already-enrolled phone never flashes the code prompt)
  - `enrolment == null` → `EnrolScreen(onEnrolled = { /* state flow re-emits */ })`
  - else → `Text("Paired with ${enrolment!!.partnerName}")`

- `gradle/libs.versions.toml` — added exactly two lines, verbatim from Step 2:
  - `[versions]`: `coroutinesPlayServices = "1.10.2"`
  - `[libraries]`: `kotlinx-coroutines-play-services = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-play-services", version.ref = "coroutinesPlayServices" }`

- `app/build.gradle.kts` — added exactly one line to `dependencies`:
  `implementation(libs.kotlinx.coroutines.play.services)`
  (placed next to `implementation(libs.firebase.messaging)`, since it exists
  specifically to support the `.await()` call on the FCM token task).
  Confirmed via `git diff` that no other line in either build file changed —
  `agp`, `kotlin`, `composeBom`, `compileSdk`, `targetSdk`, the toolchain block,
  and the Gradle wrapper are all untouched.

## Dependency resolution

`org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2` resolved without
issue — no substitution was needed.

## Build and test output (full commands, real output)

```
$ export ANDROID_HOME="$HOME/Android/Sdk"
$ ./gradlew :app:assembleDebug
...
BUILD SUCCESSFUL in 50s
40 actionable tasks: 40 executed
```

```
$ ./gradlew :app:testDebugUnitTest
...
BUILD SUCCESSFUL in 4s
27 actionable tasks: 6 executed, 21 up-to-date
```

Test result XMLs confirm no regression:
- `LoveButtonApiTest`: `tests="8" skipped="0" failures="0" errors="0"`
- `MessagesTest`: `tests="5" skipped="0" failures="0" errors="0"`
- Total: 13/13 passing, same as before this task.

`assembleDebug` ran all 40 tasks fresh (matches the expected count exactly);
`testDebugUnitTest` reused cached compile outputs from the assemble run
(6 executed, 21 up-to-date) since nothing changed between the two invocations.

## Deviations from the brief

None. Both new/rewritten Kotlin files and both build-file edits match the
brief's Step 1–3 code verbatim. `API_BASE_URL` in `app/build.gradle.kts` was
left untouched at the placeholder `https://example.invalid` — Step 5's
instruction to point it at a real Worker URL was correctly not applied, since
no Worker is deployed yet (see below).

## Outstanding: Step 5 manual verification — NOT performed

This requires a deployed Cloudflare Worker and a real enrolment code from the
human's Task 0, neither of which exists yet. `API_BASE_URL` was deliberately
left as the placeholder. No device/emulator install or logcat capture was
attempted. Once the Worker is deployed, a human needs to:

1. Set `API_BASE_URL` to the real Worker URL, install on a clean device, and
   confirm the code prompt (`EnrolScreen`) appears on first launch.
2. Enter a wrong code and confirm it shows "That code is not valid." and stays
   on the enrolment screen.
3. Enter a wrong code six times and confirm it shows "Too many attempts. Try
   again in an hour."
4. Enter the real code and confirm the screen changes to "Paired with
   &lt;partner name&gt;".
5. Force-quit and relaunch, and confirm it goes straight to "Paired with...",
   not back to the code prompt — this is what proves DataStore persisted the
   enrolment across process death.
6. Run `adb logcat | grep -i "auth_token\|Bearer"` while doing all of the
   above, and confirm zero hits — the bearer token must never appear in logs.

None of these six checks were run or marked done. They are blocked purely on
the human completing Task 0 (Firebase project + Cloudflare Worker deploy).

## Notes for Task 6's implementer

- `MainActivity`'s `Root()` composable currently has only two real destinations:
  `EnrolScreen` and a placeholder "Paired with X" `Text`. If Task 6 adds the
  actual home/send screen, that final `else` branch in `Root()` is the place to
  swap in the new composable — the loading/enrolment gating logic above it
  should not need to change.
- `EnrolScreen`'s `onEnrolled` callback is currently a no-op comment
  (`/* state flow re-emits */`) because `Root()`'s `collectAsState` on
  `prefs.enrolment` already re-renders automatically once `saveEnrolment`
  writes to DataStore — no explicit navigation call was needed. This is
  intentional, not a stub left behind by mistake.
- `API_BASE_URL` still needs to be pointed at the real Worker URL before any
  device testing is possible — this file's own comment says "Replaced with the
  real deployment URL in Task 6," so that's expected to happen there.

---

## Fix round 1

Commit SHA: `5d75e3ac9a3febdae65fcbb589f5a6776bcf1517`

### Review outcome

Spec: pass. Quality: approved. Two minor findings, both inherited from the
plan's own reference code rather than introduced during implementation:

1. **Fixed** — the single `try` in `EnrolScreen.kt` wrapped the FCM token
   fetch, `api.enrol(...)`, and `prefs.saveEnrolment(...)` together, but its
   `catch` always attributed the failure to Play Services. A malformed 200
   response (`SerializationException` in `api.enrol`) or a DataStore write
   failure in `saveEnrolment` would show "Could not get a push token from
   Google" even though the token fetch worked fine — misdirecting debugging
   effort.
2. **Declined, no action taken** — raising the enrol button's
   `code.length >= 8` gate to 48. The spec suggests `openssl rand -hex 24`
   (48 hex chars) but does not mandate that length; hardcoding 48 would break
   the day a differently-sized code is generated. The server's `InvalidCode`
   response is the real guard, so `>= 8` (which only prevents obviously
   incomplete input) stays as-is.

### Change made

`app/src/main/java/com/lovebutton/app/ui/EnrolScreen.kt` — applied the
coordinator's fix verbatim: the FCM token fetch now has its own inner
`try { FirebaseMessaging.getInstance().token.await() } catch (e: Exception) { ... return@launch }`,
so only a Play Services failure produces the "Could not get a push token from
Google" message. The outer `catch` now reads "Something went wrong enrolling
this phone. Please try again." and covers everything else (a bad server
response, a DataStore failure, etc.) without misattributing it to Google. The
`finally { busy = false }` block, the four `EnrolResult` branches, the
button's `enabled = code.length >= 8` condition, `MainActivity.kt`, and all
build files are untouched — confirmed via `git diff`, which shows exactly one
file changed (`EnrolScreen.kt`, +18/-3).

### Control-flow confirmation: `return@launch` and the outer `finally`

Confirmed this behaves correctly. `return@launch` executes inside the body of
the *outer* `try` block (the inner `try/catch` around the FCM fetch is nested
inside it). On the JVM, a non-local `return` executed from within a `try`
block always runs any enclosing `finally` block(s) before the return actually
completes — this is standard try/finally semantics, identical to Java, and
applies whether the exit is a normal fall-through, an exception, or an
explicit `return`/labeled return. Kotlin lambdas compile to the same
bytecode-level `finally` handling. So on the Play-Services-failure path:
the inner `catch` sets `error`, executes `return@launch`, and — because that
statement is lexically inside the outer `try` — the outer `finally { busy =
false }` runs before the coroutine lambda actually exits. The spinner cannot
stick. No restructuring was needed.

### Build and test output (full commands, real output)

```
$ export ANDROID_HOME="$HOME/Android/Sdk"
$ ./gradlew :app:assembleDebug
...
BUILD SUCCESSFUL in 2s
40 actionable tasks: 4 executed, 36 up-to-date
```

```
$ ./gradlew :app:testDebugUnitTest
...
BUILD SUCCESSFUL in 2s
27 actionable tasks: 3 executed, 24 up-to-date
```

Test result XMLs confirm no regression:
- `LoveButtonApiTest`: `tests="8" skipped="0" failures="0" errors="0"`
- `MessagesTest`: `tests="5" skipped="0" failures="0" errors="0"`
- Total: 13/13 passing, unchanged from before this fix.

`local.properties` was not staged or committed — `git status` before the
commit showed only `EnrolScreen.kt` modified, and only that file was `git
add`-ed.

### Outstanding

Step 5's manual verification remains outstanding for the same reason as
before: no deployed Worker exists yet. Nothing about this fix changes that
list; see the six checks above.
