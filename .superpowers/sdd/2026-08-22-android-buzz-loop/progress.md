# SDD ledger — plan: docs/superpowers/plans/2026-08-22-android-buzz-loop.md

Spec: love-button-spec.md (read; binding authority)
Branch: feat/android-buzz-loop, cut from feat/worker-core

## Preflight conflict scan

### Cross-task rows

| A | B | Produces vs consumes | Finding |
|---|---|---|---|
| T1 | T5, T7 | T1 creates MainActivity; T5 rewrites it; T7 modifies the enrolled branch | **DEFECT** — T5's version used `var loaded by remember { mutableStateOf(false) }` without importing `setValue`. That does not compile. Fixed preflight (Ruling 1) |
| T7 | T8 | T7's MainActivity referenced `SetupScreen` | **DEFECT** — T8 creates that file, so T7 could not build standalone, breaking the rule that every task ends independently testable. T7's button is now a no-op that T8 wires. Fixed preflight (Ruling 2) |
| T2 | T6 | `messageForId`, `DEV_CHANNEL_ID` | Clean — names and signatures match |
| T3 | T5, T6, T7 | `LoveButtonApi.enrol/registerDevice/send` | Clean — all three call sites match the signatures |
| T4 | T5, T6, T7 | `Prefs.current()`, `Enrolment(authToken, person, partnerName)` | Clean |
| T6 | T7 | `SendWorker.enqueue` / `RegisterTokenWorker.enqueue` | Clean — both are `(context, arg)` |
| T5 | T1 | T5 adds `kotlinx-coroutines-play-services`, absent from T1's catalog | Acceptable — T5 adds it in its own Step 2 rather than T1 carrying an unused dependency |
| T8 | T8 | `SetupScreen` imports vs uses | **DEFECT** — imported `LocalLifecycleOwner`, never used; and needed `openAppSettings` for the fix below. Fixed preflight (Ruling 3) |

### Per-task self-consistency rows

| Task | Finding |
|---|---|
| T1 | Clean — gradle, manifest, theme and a blank activity are internally consistent |
| T2 | Clean — 5 tests against a 4-entry catalogue; the unknown-id test guards a real case (an older app receiving a newer message id) |
| T3 | Clean — 8 MockWebServer tests; the send test asserts the body carries no recipient field, which is the client-side half of invariant 2 |
| T4 | Clean — no unit test, justified in the task text against spec §11 and covered end-to-end by T5's persistence check |
| T5 | Fixed (Ruling 1) |
| T6 | Clean — channel/notification/service/worker are consistent; Step 9 is the milestone-2 gate |
| T7 | Fixed (Ruling 2) |
| T8 | **DEFECT** — the pre-Android-13 branch of the notification item called `openBatteryOptimisationSettings`, the wrong screen entirely. Now `openAppSettings`. Fixed preflight (Ruling 3) |
| T9, T10 | Clean — pure human verification; T10 is the spec-mandated overnight gate |

## Rulings

Ruling 1: Plan defect — Task 5's MainActivity omitted the `setValue` import that `var ... by remember { mutableStateOf(...) }` requires, so the task as written could not compile. Also replaced fully-qualified `androidx.compose.runtime.*` references with real imports. Cost if wrong: none; it did not build before.

Ruling 2: Plan defect — Task 7 referenced `SetupScreen`, created by Task 8, so Task 7 could not build or be reviewed independently. Rather than reorder the tasks (renumbering a 2000-line document invites worse errors), Task 7's Setup button is now an explicit no-op that Task 8 replaces. Cost if wrong: one throwaway lambda, replaced one task later.

Ruling 3: Plan defect — on Android 12 and below, the notification checklist item opened the *battery optimisation* dialog, which has nothing to do with notifications. There is no runtime notification permission before Android 13, so the only useful action is the app's own settings page. Also dropped an unused import. Cost if wrong: a settings screen opens instead of a different settings screen.

Ruling 4: Branch `feat/android-buzz-loop` cut from `feat/worker-core` rather than `main`. Plan 2's Task 1 modifies the repo-root `.gitignore`, which Plan 1 created; branching from main would guarantee a conflict on that file at merge. The Android code is otherwise independent of `server/`. Cost if wrong: the branches merge in sequence rather than in parallel.

Ruling 5: Tasks 1-5 are dispatched now even though Task 0 (the human's Firebase/Cloudflare setup and deploy) is incomplete. None of them needs a live Worker: Task 3's client is tested entirely against MockWebServer. Task 6 is the first that requires a deployed URL and two real phones, and execution stops there. Cost if wrong: nothing; the work is real either way.

## Progress

Task 1: dispatched (implementer, sonnet) — BASE c27fdd1 — Gradle scaffold, blank
  Compose activity. Warned about three environment realities the brief cannot know:
  the google-services plugin fails the build when google-services.json is absent (no
  Firebase project exists yet), no Gradle wrapper exists in the repo, and no Android
  SDK may be installed. Told to report BLOCKED with the exact error on the last two
  rather than hand-writing wrapper files or committing local.properties.
Task 1: implementer DONE_WITH_CONCERNS (commit f51e399). Gradle got cleanly through
  plugin application and full dependency resolution — every pinned version resolved,
  no substitutions — then failed at "SDK location not found".
Controller verified independently: ANDROID_HOME and ANDROID_SDK_ROOT unset, no SDK in
  ~/Android/Sdk or any other standard location, no sdkmanager on PATH. A stray
  /usr/bin/adb exists from a distro package but is not an SDK. JDK 26 is the only JDK.
  Only app/google-services.json.example is tracked; the real file is correctly ignored.
Valuable finding worth keeping: the Gradle wrapper had to be pinned to 9.5.0. Under
  JDK 26, Gradle 8.13 will not start at all, and Gradle 9.6+ removes an internal API
  AGP 8.13.0 still calls. 9.5.0 is the only version satisfying both, and the failure at
  each end looks nothing like a version problem. AGP itself was NOT downgraded. Patched
  into the plan so this is not rediscovered.
Ruling 6: EXECUTION HALTS after Task 1. Tasks 2 and 3 carry unit tests, and Gradle's
  Android plugin requires the SDK even for JVM unit tests, so nothing further in this
  plan can be verified on this machine. I will not write code whose tests cannot be
  run and then report it as done — that is precisely the unverified-progress failure
  the plan's TDD gates exist to prevent. Installing an SDK is a large download and a
  material change to the user's machine requiring licence acceptance, so it is theirs
  to authorise, and their own Task 0 Step 3 already calls for it. Cost if wrong: a
  pause; no work is lost and Task 1's scaffold stands.
Controller installed the Android SDK at the user's explicit request: cmdline-tools
  build 16111833 (found by parsing Google's repository2-3.xml rather than guessing a
  URL), platform-tools 37.0.1, platforms/android-36, build-tools/36.1.0. 479MB total
  under ~/Android/Sdk. Licences accepted as authorised. local.properties written and
  confirmed gitignored (.gitignore:5) and untracked. sdkmanager runs fine under
  JDK 26; it now delegates to Google's newer `android` CLI, whose package ids use
  `/` separators rather than the old `;`.
Ruling 7: with the SDK present the build reached a REAL defect in my plan — the
  version catalog declared `firebase-messaging-ktx`, which recent Firebase BOMs no
  longer carry (Google folded the KTX extensions into the main artifacts and dropped
  the -ktx variants), so it resolved to an empty version. Patching the plan to plain
  `firebase-messaging` and resuming the Task 1 implementer to apply it, rather than
  editing the file myself: controller fixes skip review and pollute my context. Cost
  if wrong: one artifact name; the KTX APIs it provided are all in the main artifact.
Ruling 8: Compose BOM pinned back to 2026.06.01 (Compose 1.11.4) rather than moving
  the stack forward. Compose 1.12.0 from BOM 2026.08.00 requires compileSdk 37 AND
  AGP 9.1+, surfacing as 22 AAR-metadata violations rather than anything naming a
  version. Both routes were genuinely open — I confirmed `platforms/android-37.0` is
  installable (plain `android-37` is not; the `.0` matters) and AGP 9.3.1 is stable.
  Chose to pin Compose because: compileSdk 36 is Android 16 and current, not stale;
  AGP 9 changed Gradle DSL this plan's build files are written against, so the upgrade
  would likely cascade into build.gradle.kts edits; and it preserves the JDK 26 +
  Gradle 9.5.0 + AGP 8.13.0 triple that took real effort to locate. Recorded the whole
  coupled set as a table in the plan with the upgrade path spelled out. Cost if wrong:
  Compose 1.11.4 instead of 1.12.0, which changes nothing this app uses; the upgrade
  remains available as its own piece of work.
Ruling 9: JDK 26 cannot compile this project. AGP 8.13's jlink-based JDK-image step
  fails on it outright; the implementer reproduced the exact jlink invocation by hand
  outside Gradle, so the diagnosis is demonstrated rather than inferred. Installed
  Temurin JDK 21 LTS to ~/.jdks (user-local, no root, nothing removed) and VERIFIED
  the fix myself before changing any project file: `./gradlew
  -Dorg.gradle.java.home=<jdk21> :app:assembleDebug` -> BUILD SUCCESSFUL, 40 tasks.
  Making it permanent via a declarative `java { toolchain { 21 } }` block rather than
  org.gradle.java.home, because the latter would either bake one machine's absolute
  path into a committed file or hide in user-level config where the next person never
  finds it. Gradle resolves the toolchain itself by scanning ~/.jdks and friends.
  Gradle continues to RUN on JDK 26; only compilation moves to 21. Cost if wrong: a
  machine without any JDK 21 fails with a clear "No matching toolchains found" rather
  than an opaque jlink error — strictly better than what it replaces.
Task 1: implementer DONE after 3 fix rounds (commit c7432cd). Controller verified
  independently: the report claimed an APK at app/build/outputs/apk/debug/ but no
  APK was there — the build directory had been cleaned. Rebuilt from scratch to
  check rather than assume: BUILD SUCCESSFUL, 40 actionable tasks, app-debug.apk
  produced. testDebugUnitTest succeeds NO-SOURCE. local.properties confirmed
  untracked. Review dispatched (sonnet) over c27fdd1..c7432cd, told which 4 of the
  8 commits are mine (plan patches, context only) and which 4 are the implementer's.
Task 1: review clean — Spec ✅, quality Approved, zero findings at every severity.
  Reviewer confirmed file-for-file match against the brief; only .example committed
  and app/google-services.json covered by a pre-existing gitignore rule;
  local.properties absent from the diff entirely; server/ untouched; .gitignore
  appended-only with nothing pre-existing removed; the toolchain block top-level in
  app/build.gradle.kts (not nested in android{}) with bytecode still targeting 17 in
  BOTH compileOptions and Kotlin jvmTarget — no silent bump to 21. Noted the Gradle
  wrapper files as necessary infrastructure for the brief's own Step 14, not scope
  creep.
Task 1: complete (commits c27fdd1..c7432cd, 3 fix rounds, review clean)
Task 2: dispatched (implementer, haiku — complete code in brief) — BASE c7432cd —
  message catalogue + first 5 unit tests. Told explicitly not to touch any build file
  or version: the toolchain took 3 fix rounds to stabilise and every value in it is
  load-bearing. Warned that ANDROID_HOME is not exported in a fresh shell, that
  DEV_CHANNEL_ID must not be "corrected" to msg_1 (channel sounds freeze at creation),
  and that a NO-SOURCE result means a wrong source-set path to fix rather than a
  reason to add a sourceSets block to build.gradle.kts.
Task 2: review clean — Spec ✅, quality Approved, 0 Critical, 0 Important. Reviewer
  confirmed messageForId matches by `it.id == id` via firstOrNull rather than by list
  index (an index lookup would pass every current test by coincidence and silently
  return the WRONG message once one is removed), that assertNull would fail against a
  default-returning implementation, and that the ids assertion pins order as well as
  membership so an off-by-one shift is caught.
Task 2: minor (deferred): the ids assertion is order-dependent in a way that is
  incidental rather than required; reordering MESSAGES for readability would produce a
  spurious failure.
Task 2: ⚠️ resolved by controller — reviewer flagged that Kotlin's bare `assert()` is a
  no-op unless -ea is enabled, which would make "each message has non-blank text"
  vacuous while still counting toward the 5 passing. Settled empirically rather than by
  reasoning: ran the test task under an init script printing the config, which reported
  `enableAssertions=true jvmArgs=[-ea]`. Gradle enables assertions on Test tasks by
  default, so the assertion genuinely fires. Not vacuous. No action.
Task 2: complete (commits c7432cd..10854ac, review clean)
Task 3: dispatched (implementer, sonnet — OkHttp 5 API drift makes this more than
  transcription) — BASE 10854ac — API models + Worker client + 8 MockWebServer tests.
  Pre-warned about two specific OkHttp 5 changes the brief's code touches: Response.body
  became non-nullable (so `body?.string()` may warn or fail), and MockWebServer moved to
  mockwebserver3 with an immutable builder-based MockResponse while the legacy package
  ships deprecated shims. Told to port if needed but keep all eight tests and every
  assertion, and to keep the .use{} blocks either way since they prevent a connection
  leak. Emphasised that the "body carries no to_person/from_person" assertion is the
  client-side half of invariant 2 and must not be weakened.
Task 3: implementer DONE (commit c95f435, 13 tests, APK builds). Three deviations:
  (1) Response.body non-nullable under OkHttp 5 — real, `body.string()`, correct.
  (2) MockWebServer legacy package still ships as a deprecated shim in 5.2.0, verified
      by inspecting the jar — no port needed, good empirical check.
  (3) NEW, undocumented: OkHttp's BridgeInterceptor derives Content-Type from the
      body's MediaType, appending "; charset=utf-8", which clobbered the explicit
      header and failed an exact-equality assertion.
Ruling 10: deviation (3) is fixed the WRONG WAY ROUND and I am reversing it. The
  implementer dropped the MediaType from the request body so the hand-set header would
  survive — i.e. changed production code to satisfy a test. But the test was the thing
  at fault: it asserted exact equality on Content-Type, while the server only requires
  `contentType.includes("application/json")` (server/src/http.ts:32), so
  "application/json; charset=utf-8" was always acceptable. Correct shape: the body
  carries its media type (idiomatic OkHttp, and the interceptor wins over a hand-set
  header anyway), the redundant header call goes, and the test asserts startsWith. I
  share the blame — my dispatch said not to weaken assertions, which left changing the
  source as the only door open. That instruction is right for assertions that pin
  behaviour and wrong for ones that over-specify an incidental detail. Cost if wrong:
  the header gains a charset suffix the server already accepts.
Task 3: re-review — finding ADDRESSED (media type restored, hand-set header deleted,
  Authorization untouched, only the one assertion relaxed). Controller verified the
  .use{} blocks directly rather than accepting "not shown in the diff chunk" as proof:
  absence from a diff is weak evidence, and a lost .use{} leaks connections silently.
Task 3: complete (commits 10854ac..b45108f, 1 fix round, review clean)
Task 4: review clean — Spec ✅, quality Approved, zero findings. Reviewer confirmed the
  DataStore delegate is module-level (one instance per Context — multiple instances over
  the same file throw at RUNTIME, invisible to the compiler and to a green build), the
  partial-write guard requires all three keys, current() consumes the first emission
  rather than blocking, and no test setup was added (correct per the intentional
  exemption, which I had to tell the reviewer about so it did not flag a settled
  decision as an omission).
Task 4: complete (commits b45108f..cd9bb08, review clean)
Task 5: review clean — Spec ✅, quality Approved, 0 Critical, 0 Important. Reviewer
  verified the things that mattered: `onEnrolled = {}` is NOT a stub bug (saveEnrolment
  writes via dataStore.edit, which makes Prefs.enrolment emit and Root()'s
  collectAsState recompose into the paired branch — so enrolling really does navigate);
  `finally { busy = false }` runs on every exit path so the spinner cannot stick; and
  the build diff adds exactly the one permitted dependency.
Ruling 11: of the two plan-mandated Minors I am fixing ONE and declining the other.
  FIXING: the single try wrapped the FCM fetch, the API call and the DataStore save,
  so a SerializationException on a malformed 200 or an IOException while saving would
  display "Could not get a push token from Google" — a confidently wrong message that
  sends the reader to debug Play Services when the token fetch worked fine. For a
  project whose stated purpose is being read and learned from, a misleading error is
  worse than a vague one. DECLINING: raising the enrol button's `code.length >= 8`
  gate to 48. The spec suggests `openssl rand -hex 24` (48 chars) but does not mandate
  a code length, so hardcoding 48 would break the day a different one is generated;
  the real guard is the server's InvalidCode response. Cost if wrong: a very short
  paste reaches the network before being rejected.
Task 5: re-review — finding ADDRESSED, control-flow claim independently confirmed
  (return@launch is lexically inside the outer try, so finally { busy = false } runs
  and the spinner cannot stick), four EnrolResult branches untouched, button gate
  correctly left at >= 8, no build files touched.
Task 5: complete (commits cd9bb08..5d75e3a, 1 fix round, review clean)

PLAN 2 PAUSED AT TASK 6. Tasks 1-5 complete and reviewed; 13 unit tests; APK builds.
  Task 6 onward requires a deployed Worker, a Firebase project and two physical
  phones — none of which exist yet. Not a failure; the planned boundary.

## Resumed 2026-08-24

Task 0 (human) completed in the interim: Firebase project lovieapp-b4068, service
  account key at ~/secrets/love-button-sa.json, D1 + KV created, schema applied
  remotely, three secrets set, Worker deployed and verified live — /health 200,
  bogus bearer 401, wrong enrolment code 403 invalid_code. Both phones attached
  (24115RA8EG over USB, 21081111RG over wireless debugging), both _eea builds.
Ruling 12: API_BASE_URL is read from local.properties rather than committed, which
  deviates from Task 6 Step 7. The workers.dev subdomain is the local-part of a
  personal email address and this repo is public, so committing the URL publishes
  the address. Cloudflare will not rename an existing account subdomain (the API
  replies "Account already has an associated subdomain" and the dashboard exposes
  no control), so the URL cannot be made neutral. Missing value fails configuration
  with the exact line to add rather than defaulting to a placeholder — an APK built
  against example.invalid installs cleanly and fails at enrolment with a network
  error, which reads as a broken Worker. Cost if wrong: a cloner must supply their
  own URL, which they must do anyway.
Task 6: executed by the controller directly, not dispatched — this session's harness
  forbids spawning subagents unless the user asks. Steps 1-8 done: Notifications.kt,
  RegisterTokenWorker.kt, PushService.kt, LoveButtonApp.kt, manifest registration.
  Step 2 was already satisfied (androidx-core-ktx and work-runtime-ktx 2.11.0 both
  already dependencies) and Step 7 by Ruling 12. Verified independently rather than
  trusting BUILD SUCCESSFUL: 13 unit tests pass, and the MERGED manifest — not the
  source one — contains LoveButtonApp, PushService, MESSAGING_EVENT and
  POST_NOTIFICATIONS.
Plan gap found in Task 6 Step 9: it instructs the human to "grant the notification
  permission when prompted", but nothing in Task 6 requests it — the request arrives
  in Task 8. POST_NOTIFICATIONS is declared in the manifest, so on Android 13+ the
  notification silently no-ops and no prompt ever appears. Granting via
  `adb shell pm grant` for the gate rather than pulling Task 8's code forward.
Finding: `adb install` to phone B fails INSTALL_FAILED_USER_RESTRICTED. HyperOS's
  "Install via USB" gates adb installs over WIRELESS debugging too, not only USB —
  worth knowing, since wireless is the documented workaround for the SIM-card wall
  and it does not lift this particular restriction. adb push still works, so the
  APK is staged at /sdcard/Download/love-button.apk for a manual install.
Task 6: Step 9 (the milestone gate) NOT RUN — phone A was mid file-transfer and
  phone B needs the manual install. Committed anyway so the work is not left loose;
  the gate is the next action and the commit message says so.
Task 6 gate PASSED on hardware: phone B buzzed with the correct title and text, and
  again after being locked several minutes, so Doze does not delay it.
Tasks 7 and 8 executed by the controller. Two plan gaps closed in Task 8, both of
  which would have shipped a screen that looked finished and was not:
  (a) the plan left onOpenSetup a no-op for Task 8 to wire but never added the
      wiring step, so SetupScreen was unreachable while its own Step 5 says to open
      it; MainActivity now carries a showSetup branch;
  (b) ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is refused unless the app declares
      the matching permission, so the battery button would have fallen back to the
      app settings page forever and looked like MIUI weirdness.
Ruling 13: two real bugs found on hardware in the notification checklist item, both
  fixed at root cause rather than papered over. First, a USER_FIXED denial makes
  RequestPermission().launch() a silent no-op, so the Grant button worked only when
  notifications were already on and went dead in the one state it exists to fix;
  the denied result now routes to ACTION_APP_NOTIFICATION_SETTINGS, with
  shouldShowRequestPermissionRationale read AFTER the attempt because before asking
  it cannot distinguish "never asked" from "permanently denied". Second, returning
  from a settings page fires no callback, so remember(refresh) served a stale value
  and the tick needed a second tap; an ON_RESUME observer re-reads live state.
  Worth noting: the preflight scan had removed Task 8's unused LocalLifecycleOwner
  import as spurious. It was not spurious — it was the missing observer, and
  deleting it removed the evidence of the gap rather than the gap.
Task 9: Steps 1-6 PASSED on hardware. Four messages in both directions, stacking
  correctly; delivery survives screen-off and being swiped from recents; force-stop
  kills delivery and reopening restores it.
Task 9 finding (expected, now documented): messages sent while phone B was
  force-stopped never arrive, even after reopening — no backlog. FCM store-and-forward
  covers an unreachable device, not a reachable one whose target app is stopped; the
  handoff succeeds at the Play Services layer and the message is dropped. Matches
  spec 515 and 672, and there is deliberately no catch-up path. Recorded in
  docs/MANUAL-SETUP.md as spec 8 requires.
Task 10 (overnight gate): NOT RUN — the user is running it later. This is the last
  open item in the plan.
Task 10, night 1 of 2: PASSED, both directions, 2026-08-24. Phones idle and
  UNPLUGGED for ~13 hours (last activity 23:10, morning send 12:19) — double the
  six-hour minimum, and on battery, where Doze and MIUI's killer are hungrier than
  while charging. Both phones buzzed before either was unlocked. Server rows confirm
  1->2 and 2->1 four seconds apart, and both device rows survived, so FCM declared
  neither token dead overnight.
Improvement over the plan's Task 10, prompted by the user asking why phone A had to
  stay idle at all when sending requires waking it: it does not. The laptop does the
  sending, so NEITHER phone needs waking, and the gate therefore tests both phones as
  receivers rather than only B. The plan's version would have missed phone A's app
  being killed overnight — the same failure in the direction that stops HER messages
  arriving. Tokens are cached before bed because phone B's wireless adb drops
  overnight, and needing adb in the morning is exactly when it is least available.
Ruling 14: night 2 of the overnight gate SKIPPED at the user's explicit decision,
  after the tradeoff was stated. The plan calls for two consecutive nights because
  MIUI's killer is memory-pressure driven and therefore probabilistic — one quiet
  night proves the setup CAN hold, not that it does. Accepted because the failure is
  recoverable and cheap to detect later: the check is one command, the phones are
  already primed, and the user intends to keep running it at night alongside further
  development. Recording it so a future silent-delivery failure is read against a
  known one-night sample rather than a presumed two-night one. Cost if wrong: a
  delivery failure surfaces during Plan 3 rather than now.
Task 10: one night PASSED, second night skipped by decision. Plan 2 closed.
