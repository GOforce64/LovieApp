# Love Button — Worker

The server half of Love Button. Holds the only copy of the Google service
account key and is the only place that decides who may send what to whom.

## Status

**Deployed and in daily use** at `https://love-button.<subdomain>.workers.dev`,
serving two real phones. 75 tests passing.

The Firebase project, the D1 database and the KV namespace all exist; their ids
are in `wrangler.toml` (ids are identifiers, not credentials, and are safe to
commit). All three secrets are set. The retention cron runs nightly at 03:17 UTC.

## Setup

    npm install

Migrations have been applied to the remote database. To apply a new one:

    npm run migrate:remote

Secrets (never in this repo):

    wrangler secret put ENROLL_CODE_1
    wrangler secret put ENROLL_CODE_2
    wrangler secret put FCM_SERVICE_ACCOUNT

## Configuration

`server/wrangler.toml` currently holds the placeholder id `00000000-0000-0000-0000-000000000000`
for both `database_id` and the KV namespace `id`. Both must be replaced with real values before deploying.

## Commands

| Command | Does |
|---|---|
| `npm test` | Run the suite against a local Workers runtime |
| `npm run typecheck` | Type-check the TypeScript code |
| `npm run dev` | Local server at :8787 |
| `npm run deploy` | Deploy to Cloudflare |
| `npm run migrate:remote` | Apply migrations to the live D1 database |

## Endpoints

| Method | Path | Auth |
|---|---|---|
| GET | `/health` | none |
| POST | `/v1/enroll` | enrolment code |
| POST | `/v1/devices` | bearer |
| DELETE | `/v1/devices` | bearer |
| POST | `/v1/send` | bearer |

`/v1/receipts` arrives in Plan 4.

## Managing devices

List them:

    wrangler d1 execute love-button --remote \
      --command "SELECT id, person, label, updated_at FROM devices"

Remove one (for example a test phone being retired):

    wrangler d1 execute love-button --remote \
      --command "DELETE FROM devices WHERE id = '<id>'"

Rotating an enrolment code does **not** revoke tokens already issued. To cut
off a device, delete its row.

## The three invariants

1. The sender is the authenticated device — never read from a request body.
2. The recipient is derived server-side as `3 - from_person`.
3. Only the recipient of a send may acknowledge it (enforced in Plan 4).

Each has a test. Do not weaken them.
