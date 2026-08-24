# Task 2 report: POST /v1/receipts

## What was changed

- Created `server/src/routes/receipts.ts`: a Hono router (`export const receipts`) mounted at
  `/v1`, exposing `POST /v1/receipts` behind `requireDevice`. It validates `{ send_id, state }`
  (state must be `"delivered"` or `"seen"`), looks up the `sends` row, returns 404
  `unknown_send` if missing, enforces invariant 3 with 403 `not_recipient` when the caller is not
  `row.to_person`, then updates `delivered_at`/`seen_at` with `COALESCE` so writes are idempotent
  and monotonic (`seen` also backfills `delivered_at` if unset). It then looks up the sender's
  devices with a non-null `fcm_token` and, if any exist, fetches an access token and sends a
  NORMAL-priority push with `data: { type: "receipt", send_id, state, at }` to each, deleting any
  device whose token comes back `"unregistered"`. Always returns 200 `{ ok: true }`, regardless of
  push outcome.
- Modified `server/src/index.ts`: added `import { receipts } from "./routes/receipts";` and
  `app.route("/v1", receipts);` alongside the other route mounts.
- Created `server/test/receipts.test.ts`: the 8-case suite from the brief, verbatim — records
  delivered + pushes receipt; refuses non-recipient (403 `not_recipient`); idempotent delivered
  (timestamp doesn't move); seen backfills delivered_at; state never regresses; 200 even when the
  reverse push 500s; 404 on unknown send_id; 400 on unknown state.

Both the test file and the route implementation were used exactly as given in the brief — no
deviations were needed.

## Test commands and output

`cd server && npx vitest run test/receipts.test.ts` (before implementation, to confirm failure):
- 8 failed — every case either 404'd with `not_found` (route unmounted) or otherwise mismatched,
  as expected since the route didn't exist yet.

`cd server && npx vitest run test/receipts.test.ts` (after implementation):
```
✓ test/receipts.test.ts (8 tests) 220ms
Test Files  1 passed (1)
     Tests  8 passed (8)
```

`cd server && npm test` (full suite):
```
✓ test/send.test.ts (14 tests)
✓ test/receipts.test.ts (8 tests)
✓ test/enroll.test.ts (8 tests)
✓ test/auth.test.ts (8 tests)
✓ test/google-oauth.test.ts (3 tests)
✓ test/messages.test.ts (6 tests)
✓ test/crypto.test.ts (9 tests)
✓ test/fcm.test.ts (5 tests)
✓ test/schema.test.ts (4 tests)
✓ test/health.test.ts (2 tests)
Test Files  10 passed (10)
     Tests  67 passed (67)
```

`cd server && npm run typecheck` — clean, no errors.

## Deploy / smoke test

Skipped per explicit instruction not to run `npm run deploy` — the Worker is live and serving two
real phones, and deployment is out of scope for this task.

## Anything that surprised me

Nothing. The brief's test file and route implementation worked as written with zero edits; the
existing helpers (`requireDevice`, `fail`, `nowSeconds`, `readJson`, `getAccessToken`, `sendPush`)
matched their described signatures exactly, and the suite landed at exactly 67 tests as predicted.
