# SDD ledger — plan: docs/superpowers/plans/2026-08-24-receipts.md

Spec: docs/superpowers/specs/2026-08-24-receipts-design.md (read; argues from
love-button-spec.md, which is the binding authority)
Branch: feat/receipts, cut from main after Plans 1-3 merged

## Preflight conflict scan

### Cross-task rows

| A | B | Produces vs consumes | Finding |
|---|---|---|---|
| T1 | T3 | server accepts body `send_id` vs client sends `SendRequest.sendId` (`@SerialName("send_id")`) | Clean — field name matches |
| T2 | T4 | endpoint `{send_id, state}` vs `ReceiptRequest(sendId, state)` | Clean — serial names match, state values "delivered"/"seen" match on both sides |
| T2 | T5 | receipt push `data{type,send_id,state,at}` vs PushService reading `data["send_id"]`, `data["state"]` | Clean — keys and values line up; `type == "receipt"` matches the branch |
| T3 | T5 | `PendingSends.widgetFor/forget` vs PushService's use | Clean — signatures match |
| T3 | T5 | 20-second window: T3 uses `PENDING_WINDOW_MS`; T5 sets `WidgetState.SENT -> 20_000L` | **DEFECT** — two sources of truth for one number. Ruling 1 |
| T4 | T5 | `postMessageNotification(context, msgId, fromName, sendId)` | Clean — T4 adds the defaulted parameter and updates its only caller in the same task |
| T4 | T6 | `MainActivity.reportSeenFrom` | Clean by design — T6 deliberately replaces T4's version to add the toggle check; T4 must build standalone first |
| T6 | Plan 3 | `HomeScreen(partnerName, onOpenSetup)` gains two parameters | Clean — T6 updates the composable and its only call site together |

### Per-task self-consistency rows

| Task | Finding |
|---|---|
| T1 | Clean — the four tests match the implementation; `?? crypto.randomUUID()` keeps the omitted-id test passing and keeps deployed clients working |
| T2 | Clean — eight tests; the 500-push test exercises the "200 regardless" rule, and the not_recipient test asserts the DB is unchanged rather than only the status |
| T3 | Clean after the Step 8 rewrite — `mintedSendId` is a field, not input data, because the id never crosses a process boundary |
| T4 | **DEFECT** — `Result.success().takeIf { ok } ?: Result.success()` evaluates to `Result.success()` on both branches. Dead expression dressed as a decision. Ruling 2 |
| T5 | Clean, with one deliberate behaviour worth recording: DELIVERED returns the tile to idle after 4s but does NOT clear the pending entry, so a later `seen` inside the window lights it again. Intended |
| T6 | Clean — the toggle is read on the phone that would report, which is the phone whose owner the choice belongs to |
| T7 | Clean — pure human verification; Step 6 checks invariant 3 against the live server rather than only in tests |

## Rulings

Ruling 1: T5 must import `PENDING_WINDOW_MS` and use it for `WidgetState.SENT`
  rather than the literal `20_000L` the plan text shows. Two constants for one
  window means a later change to the pending window silently desynchronises the
  tile from the map that governs it — the tile would clear while the mapping
  still lived, or vice versa. The test asserting `20_000L` stays as written: it
  pins the value, and pinning it in the test is what makes the shared constant
  safe to change deliberately. Cost if wrong: none; it is one import.

Ruling 2: T4's ReceiptWorker returns `Result.success()` unconditionally, with a
  comment explaining why, replacing the plan's
  `Result.success().takeIf { ok } ?: Result.success()`. Both branches of that
  expression are identical, so it reads as a decision while making none — the
  worst kind of code to leave in a project whose stated purpose is being read
  later. The intent is real and worth keeping in words: a rejected receipt (403,
  404) must NOT be retried, because this device is not the recipient or the send
  is gone, and only a thrown network error is worth a retry. Cost if wrong: none;
  behaviour is identical, the comment carries the reasoning.

## Progress

Task 1: dispatched (implementer, haiku — the brief carries complete code, so this
  is transcription plus testing) — BASE 8d51864 — client-minted send_id on
  /v1/send. Told explicitly not to touch recipientOf or the toPerson derivation
  (invariant 2), that deployed clients still omit send_id so the field must stay
  optional, and not to deploy.
Task 1: implementer DONE (commit f037f52). Controller verified independently rather
  than trusting the report: one commit on the range, and `npm test` run here gives
  59 passed across 9 files.
Task 1: review clean — Spec ✅, quality Approved, 0 Critical, 0 Important. Reviewer
  confirmed the things that mattered: recipientOf and the toPerson derivation are
  untouched so invariant 2 holds; the `delivered: 0` path is still a 200; the
  absent-send_id branch is untouched so deployed clients keep working; and
  INSERT OR IGNORE + `meta.changes` is race-safe by construction because D1
  serialises writes, rather than the race-prone SELECT-then-INSERT.
Task 1: minor (deferred): the duplicate test exercises the sequential case only,
  never two genuinely concurrent requests. The mechanism is race-safe regardless of
  test shape, so this is an untested claim rather than a functional gap.
Task 1: minor (deferred): isUuid's doc comment says "canonical v4-shaped" but the
  regex pins neither the version nibble to 4 nor the variant nibble to 8/9/a/b, so
  it accepts any UUID-shaped 36-char hex string. Doc/impl mismatch, not a defect —
  the actual requirement is only to reject non-UUID-shaped primary keys.
Task 1: complete (commits 8d51864..f037f52, review clean)
Task 2: dispatched (implementer, sonnet — the brief carries complete code, but this
  route holds invariant 3 and a monotonic state machine, so it gets judgment rather
  than transcription) — BASE f037f52 — POST /v1/receipts.
Task 2: implementer DONE (commit 67cb94e), no deviations, deploy step correctly
  skipped per dispatch. Controller verified independently: 67 passed across 10
  files here, and the route is genuinely mounted (index.ts:7 import, :21 route)
  rather than merely written — an unmounted router would still pass its own unit
  tests through SELF.fetch only if registered, so this was worth checking.
Task 2: review clean — Spec ✅, quality Approved, 0 Critical, 0 Important. Reviewer
  verified the two things that could have been backwards while still passing a
  naive test: monotonicity is structural (the delivered branch never references
  seen_at at all, so a late delivered cannot regress a seen), and the recipient
  gate compares row.to_person against the token-derived device.person rather than
  from_person. Also confirmed the push targets the SENDER's devices, not the
  acknowledging device's own.
Task 2: minor (deferred): no test asserts the FCM request BODY, only its path and
  method, so a regression swapping NORMAL for HIGH or type "receipt" for "msg"
  would pass all eight tests. Correct today by inspection; unguarded tomorrow.
Task 2: minor (deferred): no test seeds a sender with zero FCM-token devices, so
  the `targets.length > 0` short-circuit is correct by inspection but unexercised.
Task 2: ⚠️ resolved by controller — reviewer could not verify that the Android app
  branches on type == "receipt" to suppress a notification, since that is
  client-side. Not a gap: Task 5 of this same plan implements exactly that branch,
  and Task 7 Step 4 verifies on hardware that the sender's phone stays silent.
Task 2: complete (commits f037f52..67cb94e, review clean)
Task 3: dispatched (implementer, sonnet — multi-file with DataStore and worker
  integration rather than a single transcription) — BASE 67cb94e — the app mints
  the send_id and remembers which tile it belongs to.
Task 3: implementer DONE (commit 1c6039e) with one declared deviation: the brief
  changed LoveButtonApi.send()'s signature without a default for sendId and did not
  account for two pre-existing call sites in LoveButtonApiTest, which then failed to
  compile. The implementer passed a dummy sendId at those two sites rather than
  adding a default parameter. That is my defect in the brief, not theirs.
Controller verified independently: 21 tests pass, assembleDebug succeeds, and the
  ordering that is the entire point of the task holds — pending.remember at
  SendWorker.kt:56, the network call at :60. Mapping written before the request.
Task 3: review clean — Spec ✅, quality Approved, 0 Critical, 0 Important. Reviewer
  confirmed the ordering closes the race on every path, that the catch block
  forgets the entry so a failed send leaves nothing stale, that mintedSendId as a
  Worker field is safe (WorkManager builds a fresh Worker per execution attempt,
  so no two runs share one), and that forgetExpired iterates a defensive copy
  rather than the live map. Endorsed the implementer's compile-fix as the right
  call over adding a default parameter.
Task 3: minor (deferred): PendingSends and SendWorker's new ordering/cleanup/settle
  logic have no unit tests of their own — the invariant this task exists to
  guarantee has no regression coverage. Inherited from my brief, which specified
  only the one client-body test.
Task 3: minor (deferred): settle() holds the worker the full 20s even on the
  widget-less in-app path, where nothing is displayed and the delay buys nothing.
Task 3: minor (deferred): a DELIVERED arriving late in the window can have its 4s
  display truncated by settle's expiry at 20s, and a rapid double-tap on one widget
  can let the earlier send's expiry clear the later send's state. Both are display
  glitches in the brief's own design, not implementer defects.
Task 3: ⚠️ resolved by controller — reviewer could not verify settle()'s contract
  because the receipt handler does not exist yet. Task 5 implements it, and the
  contract is exact: SEEN forgets the pending entry, DELIVERED deliberately does
  NOT, because a seen may still arrive inside the window and needs the mapping.
  Carried into Task 5's dispatch rather than left implied.
Task 3: complete (commits 67cb94e..1c6039e, review clean)
Task 4: dispatched (implementer, sonnet — five files across push, worker and
  activity) — BASE 1c6039e — her phone reports delivered and seen. Carries Ruling 2.
Task 4: implementer DONE (commit 629cd78), Ruling 2 applied as instructed. Controller
  verified independently: 21 tests, assembleDebug succeeds, no `takeIf` remains in
  ReceiptWorker, and PushService's `else -> Unit` is intact at :40 so Task 5 has its
  insertion point. Dispatched review with the PendingIntent request code, onNewIntent
  and double-reporting called out — this task is almost entirely untestable by unit
  test, so the reviewer's reading is the only gate before hardware.
Task 4: review — Spec ✅, but ONE IMPORTANT: onNewIntent is unreachable. MainActivity
  declares no launchMode (so "standard") and the notification intent sets
  FLAG_ACTIVITY_CLEAR_TOP without FLAG_ACTIVITY_SINGLE_TOP; on a standard activity
  that combination destroys and recreates rather than delivering onNewIntent. Seen
  reporting still works — every tap lands in onCreate with a fresh intent — but the
  override is dead and its comment claims the opposite, and each tap rebuilds the
  entire Compose tree.
Ruling 3: the finding is correct and I am fixing it by adding
  FLAG_ACTIVITY_SINGLE_TOP to the notification intent rather than deleting
  onNewIntent. Deleting would leave working code (onCreate already reports) but keep
  the wasteful teardown-and-rebuild on every tap, and would lose the in-place update
  a paired app wants when she taps a notification while already looking at the app.
  Adding the flag makes the code do what it already says it does. Choosing the
  intent flag over manifest launchMode="singleTop" keeps the change scoped to
  notification taps rather than altering how the launcher icon behaves. The
  misleading comment is corrected in the same round. Cost if wrong: a notification
  tap resumes the existing screen instead of recreating it, which is what the code
  was written to expect anyway.
Task 4: fix round 1/5 dispatched (resumed original implementer) — FIX_BASE 629cd78.
Task 6: fix round 1/5 implementer DONE (commit 1f3fbf6). Controller verified
  independently: three files, 25 insertions, both rulings applied exactly.
  clearEnrolment does the read-clear-rewrite inside one edit block with a
  `if (keep != null)` guard, so a user who never touched the switch does not get
  `false` written for them. reportSeenFrom is synchronous again and is now SHORTER
  than the version it replaces — the fix removed code rather than adding a guard,
  which is the shape a good fix has. lifecycleScope and flow.first dropped from
  MainActivity; launch and rememberCoroutineScope kept for the switch's write.
  `./gradlew :app:assembleDebug :app:testDebugUnitTest` re-run here: 21 tests, 0
  failures, BUILD SUCCESSFUL.
Task 6: fix round 1/5 scoped re-review dispatched (sonnet) — range c5da90e..1f3fbf6.
  Five things named for it to check rather than a general pass, the first being the
  highest-consequence way this fix could go wrong: the new gate must suppress "seen"
  and NOTHING else, because a condition that also caught "delivered" would break the
  ladder for every user regardless of their toggle. Also asked whether the move
  changed WHO decides (the spec requires the toggle be read on the reporting phone),
  whether placing the gate after the enrolment null-check changes behaviour for a
  toggle-off user with no valid enrolment, and whether the import split is right in
  both directions rather than just compiling.
Task 6: fix round 1/5 re-review — both findings ADDRESSED, no new
  Critical/Important breakage. The reviewer did all five named checks and grounded
  the important one rather than eyeballing it: it grepped the ReceiptWorker.enqueue
  call sites and confirmed there are exactly two, "seen" from MainActivity and
  "delivered" from PushService:45, so the gate cannot catch delivered. It also
  confirmed the move preserved WHO decides (WorkManager runs on the device that
  enqueued, reading that device's own DataStore) and that a never-touched toggle
  leaves the key absent rather than writing false.
Task 6: minor (deferred, new, from the re-review): with the toggle off AND no valid
  enrolment, a job is now enqueued that returns Result.failure() where previously
  none was enqueued at all. No network call either way, no user-visible difference —
  WorkManager bookkeeping only.
Task 6: minor (deferred, new, from the re-review): doWork() constructs
  Prefs(applicationContext) twice rather than reusing one instance. Same underlying
  DataStore singleton, so harmless.
Task 6: complete (commits ed2aed8..1f3fbf6, review clean after 1 fix round)

## All code tasks complete — Tasks 1-6. Task 7 is the human hardware pass (Ruling 6).
Controller ran the SERVER suite independently before the final review's verdict, since
  every prior verification in this session was Android-only and the branch also
  changed two server routes: `cd server && npm test` gives 67 passed across 10 files
  (up from the 59/9 recorded at Task 1 — Task 2's receipts endpoint added the
  difference). Both suites green on 1f3fbf6.
Final whole-branch review dispatched (opus, most capable per Model Selection) —
  range 8d51864..1f3fbf6, 10 commits. Given the eight spec invariants to verify
  against the CODE rather than against the plan's claims, the full deferred-minor
  list to triage explicitly, and Ruling 5 for an independent second opinion on
  whether deferring it was right.
Final whole-branch verification done by the controller directly (this session; the
  dispatched final review's verdict was never recorded before the previous session
  ended, so the gate was re-run here rather than assumed). Both suites re-run on
  1f3fbf6: `cd server && npm test` gives 67 passed across 10 files;
  `./gradlew :app:assembleDebug :app:testDebugUnitTest` gives 21 tests, 0 failures,
  BUILD SUCCESSFUL. Read the branch diff (8d51864..1f3fbf6, 10 commits) against the
  spec's §4 invariants rather than against the plan's claims:
  - Invariant 1 (sender is the authenticated device): both routes read
    `device.person` from the bearer context; no sender identity is read from a body.
  - Invariant 2 (recipient derived server-side): send.ts still computes
    `recipientOf(device.person)`. The new `send_id` field lets the client choose an
    id, never a destination — and send.test.ts:85 "ignores any recipient the client
    tries to name" pins that with the new field in play.
  - Invariant 3 (only the recipient may acknowledge): receipts.ts compares
    `row.to_person` against `device.person` and 403s `not_recipient` BEFORE any
    write, so a refused call leaves the row untouched. Tested at receipts.test.ts:76.
  All three rulings confirmed present in the code: PENDING_WINDOW_MS is imported by
  WidgetState.kt and used for SENT (Ruling 1); ReceiptWorker returns
  `Result.success()` unconditionally with the reasoning in a comment and no `takeIf`
  (Ruling 2); the notification intent carries
  FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP and onNewIntent's comment
  matches what the flags actually do (Ruling 3).
Task 7: BLOCKED on the human partner, as designed. It is the hardware pass: no
  Android devices are attached here (`adb devices` lists none), Steps 2-5 require a
  person watching a tile and tapping a notification on two phones, and Step 1/Step 6
  need a production `npm run deploy` — an outward-facing action not taken without
  explicit authorisation. Handed to the partner with the branch green.

## Task 7 (hardware pass) — in progress
Step 1: DONE. Partner ran `cd server && npm run deploy` themselves — deployed
  love-button, version 53c2bfab-b91f-4347-845e-f6506e1b11b8, bindings TOKEN_CACHE
  (KV), DB (D1 love-button), FIREBASE_PROJECT_ID, PERSON_1/2_NAME all present.
  Two devices now attached where the previous session had none: 923262ff
  (24115RA8EG) and 192.168.10.28:39881 (21081111RG). `./gradlew :app:assembleDebug`
  up-to-date on 1f3fbf6, installed with `-r` on both — Success on both.
Step 6 groundwork: the debug build is run-as-able, so phone A's bearer token can be
  read out of /data/data/com.lovebutton.app/files/datastore/love_button.preferences_pb
  without a manual copy. The curl still needs a real send_id, which only exists
  after Step 2, so Step 6 runs after the ladder — not before.
Steps 2-5: BLOCKED on the human partner by design — each one requires a person
  watching a tile and tapping a notification on two phones.

## Plan 4 rework — partner-directed, after Step 1-4 hardware feedback
Partner ran Steps 2-4 and rejected three design points and reported one bug. Handled
  through brainstorming (bounded path, approved before any code was written).

Ruling 7: the read-receipt toggle is REMOVED entirely. The partner said they never
  specified it; checked before answering rather than agreeing reflexively, and it was
  in fact theirs — love-button-spec.md:366 (§6.1 home screen) and :418 (§6.4). So the
  plan built exactly what the binding spec asked for. It is still their spec and
  their call, so the toggle goes and BOTH spec sections were amended in the same
  change — leaving §6.1 asking for a switch would have had Plan 5 rebuild it.
  Deleted: the Switch and its two HomeScreen parameters, Prefs.readReceipts /
  setReadReceipts / the READ_RECEIPTS key, the "seen" gate in ReceiptWorker, and
  clearEnrolment's read-clear-rewrite (now a plain prefs.clear() again). This
  reverses Task 6 and the whole of its fix round 1/5.

Ruling 8: "seen" now means she LOOKED at the screen, not that she tapped the
  notification. Asked the partner rather than guessing, because their message read
  two ways ("delivered status should be when phone B unlocks"). They chose
  unlock-OR-already-awake over unlock-only: if the phone is awake and unlocked when
  the notification posts she is already looking at it, so seen fires immediately;
  otherwise the send id waits for the next unlock. Both halves of the awake test are
  required — an interactive screen behind the keyguard is the phone reacting to the
  push, not a person reading.
  New: data/UnseenSends.kt (own DataStore file, drain() reads and clears in ONE edit
  so two close unlock broadcasts cannot double-report), push/Presence.kt
  (couldBeLookingNow, pure predicate + live-device overload), push/UnlockReceiver.kt.
  Removed: EXTRA_SEND_ID, MainActivity.reportSeenFrom, postMessageNotification's
  sendId parameter, and the per-send PendingIntent request code that existed only to
  keep those extras distinct. onNewIntent and the SINGLE_TOP flag from Ruling 3 stay
  — they are about resuming in place, not about receipts.
Ruling 8a: UnlockReceiver is registered from LoveButtonApp.onCreate, NEVER the
  manifest. Verified against Android's implicit-broadcast exemption list rather than
  assumed: ACTION_USER_PRESENT is not on it, so on targetSdk 36 a manifest-declared
  receiver is never invoked — it would have looked correct and silently done nothing,
  which is the worst possible failure for this feature. Checked before designing, not
  after building. ACTION_SCREEN_ON is also listened for (a phone with no keyguard
  gives no USER_PRESENT), and both are re-checked against the live device rather than
  trusted by action name. Cost if the process dies before the unlock: no seen is
  reported. Silence, never a wrong answer.
Ruling 8b: the seen POST fires regardless of the send's age, but PENDING_WINDOW_MS
  stays 20s. Partner's explicit choice: recording is uncapped because the receipt is
  a record; DISPLAY stays capped because spec §7.1's reasoning (a heart lighting up
  for something sent an hour ago) is still right. UnseenSends therefore has no expiry
  by design, and says so.

Ruling 9 (the Step 3 bug — partner reported A's tile stayed filled "for minutes,
  never times out"): root cause found by reading, then PINNED BY TEST rather than
  asserted. SendWorker.settle waited exactly PENDING_WINDOW_MS and then guarded the
  clear on `pending.widgetFor(mintedSendId) != null`. But the entry is written BEFORE
  the request and widgetFor expires entries at exactly PENDING_WINDOW_MS, so by the
  time the guard ran the answer was null by construction — every time, not
  occasionally. The tile was never returned to idle. Fixed by asking the tile what it
  is DISPLAYING instead: clearWidgetStateIf(ctx, id, SENT), the primitive that
  already existed in WidgetStateWriter.kt for exactly this shape of race, plus an
  unconditional forget(). The trap is now written into spec §7.1 so it cannot be
  reintroduced.

Ruling 10: crimson #C2185B -> #D81B60 across the delivered/seen drawables, partner
  said the old one was too dark. NOTE for the partner: ic_call_seen.xml was already
  drawn entirely in gold with no crimson body at all, unlike the other three *_seen
  icons — a pre-existing art inconsistency, untouched here because changing icon art
  is a design decision, not a recolour.

Test infrastructure (declared scope creep, flagged to the partner before doing it):
  Robolectric 4.16 added as testImplementation, with androidx.test:core and
  coroutines-test. Task 3's review had deferred "PendingSends has no tests" precisely
  because there was no way to test a DataStore class here. 4.16 is the first release
  with SDK 36 support and needs a JDK 21+ runtime (JDK 26 here); both facts checked
  against the docs first and recorded in libs.versions.toml, matching that file's
  existing convention of writing down version traps. Probed with one throwaway test
  and deleted it before building anything on top.
Verification: `./gradlew :app:assembleDebug :app:testDebugUnitTest` — BUILD
  SUCCESSFUL, 37 tests / 0 failures / 0 errors across 7 classes (was 21 across 4).
  New: PendingSendsTest (7, including the expiry boundary that made the old guard
  impossible), PresenceTest (4, all four corners of the awake/locked predicate),
  UnseenSendsTest (5, including drain-empties and duplicate-remember).
NOT covered by unit test, stated plainly rather than papered over: SendWorker.settle
  itself, UnlockReceiver's broadcast wiring, and PushService's branch — all need a
  worker/broadcast harness this project does not have. They are what Step 2/3/5 of
  the hardware pass exist to check.
Install: phone A (923262ff) updated. Phone B (192.168.10.28:39881) had dropped its
  wireless-debugging port by then and needs reconnecting before its install.

Ruling 10a (supersedes the colour half of Ruling 10): partner asked to SWAP the two
  ladder colours rather than just lighten one — the filling animation becomes crimson
  #D81B60 and the frame that was crimson becomes pink #FF6FA5. Applied across
  half/filled/delivered/seen for all four icon families, and spec §7.1's table plus
  Task 7's brief updated to match so the hardware pass checks the right colours.
  Judgment call flagged to the partner: they named only "the originally crimson
  frame" (delivered), but SEEN also had a crimson body. Swapped it too, so seen stays
  "delivered plus a gold border" rather than becoming the only crimson frame left.
  One sed to reverse if that reads wrong on glass.
Step 2/5 retest was INVALID, not a failure: partner reported seen still needing a
  notification tap, but `dumpsys package` shows phone B was updated at 12:47 and
  phone A at 12:40 — B was running the pre-change build when they tested. Worth
  noting the observation could not have distinguished the two designs anyway, since
  tapping a notification requires unlocking first, and the unlock is the new trigger.
  Both phones now on the same build; retest pending.
Note for the retest: `adb install -r` kills the app process, and UnlockReceiver is
  registered from LoveButtonApp.onCreate. Opening the app once on phone B after an
  install is the cheapest way to guarantee the process is up and the receiver live.

## Partner confirmed the ladder works end to end on hardware (Steps 2/5 behaviour)
Two follow-ups from that pass:

Ruling 11: DELIVERED no longer holds a flat 4s. Partner saw the tile go dark and then
  re-light gold a moment later when they unlocked phone B — a `seen` can arrive right
  up until the pending window closes, so a 4s hold left a visible gap in the middle
  of one continuous event. DELIVERED now holds the pending window like SENT does.
  Anchored to the SEND rather than to the receipt's arrival, via the new
  PendingSends.remainingMs(): a flat window measured from when `delivered` happened
  to land would leave the tile lit past the point where anything could still update
  it — on a slow delivered that overhang is the whole delay, not a rounding error.
  This is the partner's own words ("the same as the timeout for the message to be
  seen") read literally: the seen deadline is measured from the send, so the tile's
  is too. SEEN keeps its 4s — it is the one receipt state that is genuinely terminal.
  Spec §7.1's table updated from "4s, then idle" to "until seen, or the window
  closes". Tests: 40 passing (3 new remainingMs cases, WidgetStateTest updated).

Ruling 12: ic_bubble_outline.xml loses its smiling face — partner wants the resting
  tile to be an empty speech bubble. Checked the art before touching it rather than
  assuming the face had to be added elsewhere: the face is DRAWN into the outline as
  9 pixel cells but KNOCKED OUT of ic_bubble_filled.xml as negative space, so it
  already appears on press exactly as the partner asked. The change is therefore a
  pure deletion of those 9 cells, identified by differencing the outline's cell set
  against ic_bubble_half.xml's (which never had a face) rather than by eyeballing
  path data. Result: the idle frame is now geometrically identical to the half frame,
  in the idle colour. FAILED also reuses this drawable tinted grey, so it loses the
  face too — consistent, and worth knowing.

## Task 7 Step 6 — DONE, invariant 3 verified against the LIVE server
Phone A's bearer token read off the device with `run-as` (debug build), phone A is
  person 1 ("Hubby", partner Wifey), and every row in `sends` is from_person=1 — so A
  is the sender on all of them, which is what makes the refusal meaningful.
  A (the SENDER) acknowledging its own send dd8dac87:
    -> 403 {"error":"not_recipient"}  invariant 3 holds in production.
Three controls run so the 403 could not be a blanket refusal:
  - unknown send_id 00000000-...     -> 404 unknown_send  (the gate discriminates)
  - no Authorization header          -> 401 unauthorized  (auth runs first)
  - row re-queried after the refusal -> delivered_at and seen_at BOTH unchanged at
    1787738427, confirming the 403 happens before any write, not after a partial one.
Unplanned but valuable: the D1 rows are independent evidence that BOTH new seen paths
  work on hardware, which no unit test covers.
  - dd8dac87: delivered_at == seen_at == 1787738427. Same second — the already-awake
    path firing seen at notification time, exactly Ruling 8's first branch.
  - bd518a22: delivered_at 1787738331, seen_at 1787738390. Fifty-nine seconds later:
    the unlock path, and well outside the 20s tile window — recorded but never
    displayed, which is precisely Ruling 8b's split of uncapped recording from capped
    display, observed in production rather than argued.
Partner also confirmed Step 3 (the timeout) passes and that FAILED losing the face is
  wanted, not a regression. Steps 2,3,4,5,6 all now verified. Only Step 7 remains.

## Toolchain note (not part of the plan)
Phone B's wireless-debugging port kept rotating (39881 -> 36681 -> 41853), because
  Android 11+ wireless debugging assigns a fresh random port every time it restarts.
  Switched B to the legacy fixed-port mode with `adb tcpip 5555`; `service.adb.tcp.port`
  now reads 5555 and B reconnects as 192.168.10.28:5555. Survives Wi-Fi drops and
  screen-off, but NOT a reboot — adbd returns to the random-port mode then, and the
  command must be re-run over USB or over a fresh wireless-debugging session.
