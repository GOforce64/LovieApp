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
