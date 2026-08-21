# Love Button — Worker Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and deploy the Cloudflare Worker so that a single `curl` command causes a real Android phone to receive a push notification.

**Architecture:** A Hono-routed Cloudflare Worker holds the only copy of the Google service account key and is the only place authorization is decided. Devices authenticate with opaque bearer tokens issued at enrolment; the Worker stores only their SHA-256 hashes. State lives in D1 (two tables), and the Google OAuth access token is cached in KV because the free tier's 10ms CPU budget cannot absorb an RSA signature on every request.

**Tech Stack:** TypeScript, Hono, Cloudflare Workers, D1 (SQLite), KV, Vitest with `@cloudflare/vitest-pool-workers`, WebCrypto, FCM HTTP v1.

**Spec:** `love-button-spec.md` (sections 4, 5, and 9)

**Scope:** Milestones 0–1 of the spec's build order, plus the enrolment rate limit and the send ceiling pulled forward from milestone 8 because they are security invariants, not polish. Receipts (`/v1/receipts`) are deliberately **out of scope** — they belong to Plan 4, after the Android client exists to exercise them.

## Global Constraints

- **Never commit secrets.** The service account JSON, enrolment codes, and `.dev.vars` stay out of git. `wrangler.toml` may contain D1/KV ids — those are identifiers, not credentials.
- **Invariant 1:** The sender is the authenticated device. Never read a sender identity from a request body.
- **Invariant 2:** The recipient is derived server-side as `3 - from_person`. No endpoint accepts a recipient field.
- **Invariant 3:** Only the recipient of a send may acknowledge it.
- **`person` is constrained to 1 or 2 by `CHECK (person IN (1,2))`** in the schema, not by convention.
- **`msg_id` is validated against a server-side allowlist.** Never trust a client-supplied list.
- **All FCM payloads are data-only.** Never include a `notification` block — it bypasses the app's per-message channels and therefore its sounds.
- **The Google OAuth access token MUST be cached in KV** for 55 minutes (3300s TTL).
- **`MAX_SENDS_PER_HOUR = 500`**, defined as a single exported constant.
- **`MAX_ENROLL_ATTEMPTS_PER_HOUR = 5`** per IP, defined as a single exported constant.
- **No CORS headers.** Reject anything that isn't the expected method and content type.
- **All errors return `{ "error": "code", "message": "..." }`** with an appropriate HTTP status.
- **Free tier only.** No feature requiring a paid Workers, D1, or KV plan.

---

## File Structure

All server code lives under `server/`. The Android app will later live under `app/`, so keeping the Worker in its own directory prevents Gradle and npm from fighting over the repo root.

| File | Responsibility |
|---|---|
| `server/wrangler.toml` | Bindings, vars, deployment config |
| `server/package.json` | Dependencies and scripts |
| `server/tsconfig.json` | TypeScript config with Workers types |
| `server/vitest.config.ts` | Test pool config; loads migrations into a test binding |
| `server/migrations/0001_init.sql` | The two tables |
| `server/src/index.ts` | Hono app; route wiring only |
| `server/src/env.ts` | `Env` bindings interface and shared row types |
| `server/src/http.ts` | JSON error helpers, shared status codes |
| `server/src/crypto.ts` | `sha256Hex`, `randomToken`, `secureEquals` |
| `server/src/limits.ts` | The two rate-limit constants and their check functions |
| `server/src/messages.ts` | The server-side `msg_id` allowlist |
| `server/src/auth.ts` | Bearer middleware; resolves a device from a token |
| `server/src/routes/enroll.ts` | `POST /v1/enroll` |
| `server/src/routes/devices.ts` | `POST /v1/devices`, `DELETE /v1/devices` |
| `server/src/routes/send.ts` | `POST /v1/send` |
| `server/src/google-oauth.ts` | Mints and caches the Google access token |
| `server/src/fcm.ts` | Sends one push; classifies the response |
| `server/test/*.test.ts` | One test file per module |

Files are split by responsibility rather than by layer: everything about enrolment lives in one file, so a change to enrolment touches one place.

---

## Task 0: Accounts, keys, and local tooling (human, not agent)

**This task is done by Giorgos, not by an implementing agent.** No code is produced. The remaining tasks assume it is complete.

- [ ] **Step 1: Create the Firebase project**

Go to the Firebase console, create a project. Add an Android app with package name `com.lovebutton.app`. Download `google-services.json` — set it aside; it is not needed until Plan 2. Cloud Messaging is free on the Spark plan and requires no card.

- [ ] **Step 2: Generate the service account key**

Firebase console → Project settings → Service accounts → "Generate new private key". Save the JSON somewhere outside the repo. Note the `project_id` value inside it.

- [ ] **Step 3: Install and authenticate wrangler**

```bash
npm install -g wrangler
wrangler login
```

- [ ] **Step 4: Create the D1 database and KV namespace**

```bash
wrangler d1 create love-button
wrangler kv namespace create TOKEN_CACHE
```

Copy the `database_id` and the KV `id` from the output. Task 1 pastes them into `wrangler.toml`.

- [ ] **Step 5: Generate and store the two enrolment codes**

```bash
openssl rand -hex 24   # this is ENROLL_CODE_1 — yours
openssl rand -hex 24   # this is ENROLL_CODE_2 — hers
```

Save both in a password manager. You will need them again after any factory reset. They are never recoverable from the server.

**Verification:** `wrangler whoami` prints your account, and you have four values written down: the D1 database id, the KV namespace id, and two enrolment codes.

---

## Task 1: Project scaffold and `/health`

**Files:**
- Create: `server/package.json`, `server/tsconfig.json`, `server/wrangler.toml`, `server/.gitignore`, `server/vitest.config.ts`
- Create: `server/src/index.ts`, `server/src/env.ts`
- Create: `server/test/env.d.ts`, `server/test/health.test.ts`
- Modify: `.gitignore` (repo root)

**Interfaces:**
- Consumes: nothing
- Produces: `Env` interface (all bindings); a Hono app exported as default from `src/index.ts`; a working `npm test` command

- [ ] **Step 1: Initialise the project and install dependencies**

```bash
mkdir -p server/src server/test server/migrations
cd server
npm init -y
npm install hono
npm install -D wrangler typescript vitest@3 "@cloudflare/vitest-pool-workers@^0.12.0" @cloudflare/workers-types
```

The pool-workers version is pinned deliberately: 0.13.0 and later require Vitest 4,
which conflicts with the `vitest@3` this plan's test code targets. 0.12.0 exports
`SELF`, `readD1Migrations` and `applyD1Migrations` with the same signatures, so no
test or config code changes.

- [ ] **Step 2: Write the repo-root `.gitignore`**

Create `.gitignore` at the repository root (not inside `server/`):

```
# secrets — never commit
*.jks
*.keystore
keystore.properties
local.properties
service-account*.json
.dev.vars
app/google-services.json

# build
build/
.gradle/
node_modules/
.wrangler/
dist/
```

- [ ] **Step 3: Write `server/package.json` scripts**

Replace the `"scripts"` block in `server/package.json` with:

```json
{
  "scripts": {
    "dev": "wrangler dev",
    "deploy": "wrangler deploy",
    "test": "vitest run",
    "typecheck": "tsc --noEmit",
    "test:watch": "vitest",
    "migrate:local": "wrangler d1 migrations apply love-button --local",
    "migrate:remote": "wrangler d1 migrations apply love-button --remote"
  }
}
```

Also add `"type": "module"` at the top level of `package.json`.

- [ ] **Step 4: Write `server/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ES2022",
    "moduleResolution": "Bundler",
    "lib": ["ES2022"],
    "types": [
      "@cloudflare/workers-types/experimental",
      "@cloudflare/vitest-pool-workers"
    ],
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "esModuleInterop": true
  },
  "include": ["src/**/*.ts", "test/**/*.ts", "vitest.config.ts"]
}
```

- [ ] **Step 5: Write `server/wrangler.toml`**

Paste the real ids from Task 0 Step 4 in place of the two `PASTE_...` values, and the real `project_id` from the service account JSON.

```toml
name = "love-button"
main = "src/index.ts"
compatibility_date = "2026-01-01"

[vars]
FIREBASE_PROJECT_ID = "your-firebase-project-id"
PERSON_1_NAME = "Giorgos"
PERSON_2_NAME = "Her"

[[d1_databases]]
binding = "DB"
database_name = "love-button"
database_id = "PASTE_D1_DATABASE_ID_HERE"
migrations_dir = "migrations"

[[kv_namespaces]]
binding = "TOKEN_CACHE"
id = "PASTE_KV_NAMESPACE_ID_HERE"

[triggers]
crons = []
```

`ENROLL_CODE_1`, `ENROLL_CODE_2` and `FCM_SERVICE_ACCOUNT` are deliberately absent — they are secrets, set in Task 9.

- [ ] **Step 6: Write `server/src/env.ts`**

```typescript
/**
 * Everything the Worker is given at runtime.
 *
 * D1 and KV arrive as objects (bindings) rather than connection strings —
 * Cloudflare injects them, so there is no host, port or password anywhere.
 * The three secrets are set with `wrangler secret put` and never appear in
 * this repo.
 */
export interface Env {
  DB: D1Database;
  TOKEN_CACHE: KVNamespace;

  // plain vars, safe to commit in wrangler.toml
  FIREBASE_PROJECT_ID: string;
  PERSON_1_NAME: string;
  PERSON_2_NAME: string;

  // secrets, set via `wrangler secret put`
  ENROLL_CODE_1: string;
  ENROLL_CODE_2: string;
  FCM_SERVICE_ACCOUNT: string;
}

/** One row of the `devices` table, as the auth middleware loads it. */
export interface DeviceRow {
  id: string;
  person: number;
  fcm_token: string | null;
}

/** Values the auth middleware attaches to the request context. */
export type Vars = {
  device: DeviceRow;
};

/** The generic parameter every Hono router in this project uses. */
export type App = { Bindings: Env; Variables: Vars };
```

- [ ] **Step 7: Write `server/src/index.ts`**

```typescript
import { Hono } from "hono";
import type { App } from "./env";

const app = new Hono<App>();

/**
 * The only unauthenticated endpoint. Used to confirm a deploy worked
 * without needing a device token.
 */
app.get("/health", (c) => c.json({ ok: true }));

export default app;
```

- [ ] **Step 8: Write `server/vitest.config.ts`**

```typescript
import {
  defineWorkersConfig,
  readD1Migrations,
} from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig(async () => {
  // Read the .sql files in migrations/ so tests can apply them to a fresh
  // in-memory D1 instead of depending on a database someone set up by hand.
  const migrations = await readD1Migrations("./migrations");

  return {
    test: {
      setupFiles: ["./test/apply-migrations.ts"],
      poolOptions: {
        workers: {
          singleWorker: true,
          wrangler: { configPath: "./wrangler.toml" },
          miniflare: {
            bindings: {
              TEST_MIGRATIONS: migrations,

              // Test doubles for the three real secrets.
              ENROLL_CODE_1: "test-code-one",
              ENROLL_CODE_2: "test-code-two",
              FCM_SERVICE_ACCOUNT: "{}",

              // These override the [vars] block in wrangler.toml so the suite
              // does not depend on whatever real values are deployed. The FCM
              // tests intercept a URL built from FIREBASE_PROJECT_ID, so this
              // value MUST stay "test-project" and match those interceptors.
              FIREBASE_PROJECT_ID: "test-project",
              PERSON_1_NAME: "Giorgos",
              PERSON_2_NAME: "Her",
            },
          },
        },
      },
    },
  };
});
```

- [ ] **Step 9: Write `server/test/env.d.ts`**

```typescript
import type { Env } from "../src/env";

declare module "cloudflare:test" {
  interface ProvidedEnv extends Env {
    TEST_MIGRATIONS: D1Migration[];
  }
}
```

- [ ] **Step 10: Write `server/test/apply-migrations.ts`**

```typescript
import { applyD1Migrations, env } from "cloudflare:test";

// Runs once per test file, before any test. Applying the same migrations
// twice is a no-op, so this is safe to repeat.
await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
```

- [ ] **Step 11: Write the failing test**

`server/test/health.test.ts`:

```typescript
import { SELF } from "cloudflare:test";
import { describe, it, expect } from "vitest";

describe("GET /health", () => {
  it("returns ok without any credentials", async () => {
    const res = await SELF.fetch("https://love-button.test/health");

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
  });

  it("returns 404 for an unknown path", async () => {
    const res = await SELF.fetch("https://love-button.test/nope");

    expect(res.status).toBe(404);
  });
});
```

- [ ] **Step 12: Create an empty migrations file so the test harness can start**

`server/migrations/0001_init.sql` — Task 2 fills this in. For now:

```sql
-- schema added in Task 2
SELECT 1;
```

- [ ] **Step 13: Run the tests**

Run: `cd server && npm test`
Expected: both tests PASS. If `applyD1Migrations` errors, confirm `migrations_dir = "migrations"` is present in `wrangler.toml`.

- [ ] **Step 14: Commit**

```bash
git add .gitignore server/
git commit -m "feat(server): scaffold Worker with health endpoint and test harness"
```

---

## Task 2: Database schema

**Files:**
- Modify: `server/migrations/0001_init.sql`
- Create: `server/test/schema.test.ts`

**Interfaces:**
- Consumes: the test harness from Task 1
- Produces: `devices` and `sends` tables, available via `env.DB` in every later task

- [ ] **Step 1: Write the failing test**

`server/test/schema.test.ts`:

```typescript
import { env } from "cloudflare:test";
import { describe, it, expect } from "vitest";

describe("schema", () => {
  it("stores a device row", async () => {
    await env.DB.prepare(
      `INSERT INTO devices (id, person, auth_hash, fcm_token, label, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    )
      .bind("dev-1", 1, "hash-1", "fcm-1", "test phone", 1000, 1000)
      .run();

    const row = await env.DB.prepare(
      "SELECT person, label FROM devices WHERE id = ?",
    )
      .bind("dev-1")
      .first<{ person: number; label: string }>();

    expect(row?.person).toBe(1);
    expect(row?.label).toBe("test phone");
  });

  it("refuses a person other than 1 or 2", async () => {
    // This is the constraint that makes a third identity impossible.
    const insert = env.DB.prepare(
      `INSERT INTO devices (id, person, auth_hash, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind("dev-bad", 3, "hash-bad", 1000, 1000)
      .run();

    await expect(insert).rejects.toThrow();
  });

  it("refuses two devices sharing an auth hash", async () => {
    await env.DB.prepare(
      `INSERT INTO devices (id, person, auth_hash, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind("dev-2", 1, "shared-hash", 1000, 1000)
      .run();

    const duplicate = env.DB.prepare(
      `INSERT INTO devices (id, person, auth_hash, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind("dev-3", 2, "shared-hash", 1000, 1000)
      .run();

    await expect(duplicate).rejects.toThrow();
  });

  it("stores a send row with null receipt timestamps", async () => {
    await env.DB.prepare(
      `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind("send-1", 1, 2, 3, 2000)
      .run();

    const row = await env.DB.prepare(
      "SELECT to_person, msg_id, delivered_at, seen_at FROM sends WHERE id = ?",
    )
      .bind("send-1")
      .first<{
        to_person: number;
        msg_id: number;
        delivered_at: number | null;
        seen_at: number | null;
      }>();

    expect(row?.to_person).toBe(2);
    expect(row?.msg_id).toBe(3);
    expect(row?.delivered_at).toBeNull();
    expect(row?.seen_at).toBeNull();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd server && npm test -- schema`
Expected: FAIL — `no such table: devices`

- [ ] **Step 3: Write the migration**

Replace the contents of `server/migrations/0001_init.sql`:

```sql
-- Two tables. There is no users table (names are wrangler vars) and no
-- invites table (there is no pairing flow — see spec section 4).

CREATE TABLE devices (
  id         TEXT PRIMARY KEY,              -- uuid
  person     INTEGER NOT NULL CHECK (person IN (1,2)),
  auth_hash  TEXT NOT NULL UNIQUE,          -- SHA-256 of the bearer token
  fcm_token  TEXT,
  label      TEXT,                          -- "Giorgos · Xiaomi 13"
  created_at INTEGER NOT NULL,              -- epoch seconds
  updated_at INTEGER NOT NULL
);

CREATE INDEX idx_devices_person ON devices(person);

CREATE TABLE sends (
  id           TEXT PRIMARY KEY,            -- crypto.randomUUID()
  from_person  INTEGER NOT NULL,
  to_person    INTEGER NOT NULL,
  msg_id       INTEGER NOT NULL,
  sent_at      INTEGER NOT NULL,
  delivered_at INTEGER,                     -- her app posted the notification
  seen_at      INTEGER                      -- she tapped it
);

CREATE INDEX idx_sends_from_time ON sends(from_person, sent_at);
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd server && npm test -- schema`
Expected: all four tests PASS

- [ ] **Step 5: Apply the migration to the real remote database** — ⚠️ **DEFERRED, STILL OUTSTANDING**

**This step was skipped during implementation** because Task 0 had not been run and
no D1 database existed to migrate. The schema is created and fully tested locally,
but it does **not** yet exist on Cloudflare.

**Run this once Task 0 is complete, before any deploy:**

```bash
cd server && npm run migrate:remote
```

Expected: wrangler reports two statements applied. Task 10 Step 3 (deploy) will not
work correctly until this has been done.

- [ ] **Step 6: Commit**

```bash
git add server/migrations server/test/schema.test.ts
git commit -m "feat(server): add devices and sends tables"
```

---

## Task 3: Cryptographic helpers

**Files:**
- Create: `server/src/crypto.ts`
- Create: `server/test/crypto.test.ts`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `randomToken(): string` — 64 lowercase hex chars (256 bits of entropy)
  - `sha256Hex(input: string): Promise<string>` — 64 lowercase hex chars
  - `secureEquals(a: string, b: string): Promise<boolean>` — timing-safe

- [ ] **Step 1: Write the failing test**

`server/test/crypto.test.ts`:

```typescript
import { describe, it, expect } from "vitest";
import { randomToken, sha256Hex, secureEquals } from "../src/crypto";

describe("randomToken", () => {
  it("returns 64 hex characters", () => {
    expect(randomToken()).toMatch(/^[0-9a-f]{64}$/);
  });

  it("does not repeat", () => {
    const tokens = new Set(Array.from({ length: 100 }, () => randomToken()));
    expect(tokens.size).toBe(100);
  });
});

describe("sha256Hex", () => {
  it("matches a known SHA-256 value", async () => {
    // The canonical SHA-256 of "abc".
    expect(await sha256Hex("abc")).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
  });

  it("is deterministic", async () => {
    expect(await sha256Hex("hello")).toBe(await sha256Hex("hello"));
  });

  it("differs for different input", async () => {
    expect(await sha256Hex("hello")).not.toBe(await sha256Hex("hellp"));
  });
});

describe("secureEquals", () => {
  it("is true for identical strings", async () => {
    expect(await secureEquals("swordfish", "swordfish")).toBe(true);
  });

  it("is false for different strings of equal length", async () => {
    expect(await secureEquals("swordfish", "swordfisi")).toBe(false);
  });

  it("is false for different strings of different length", async () => {
    expect(await secureEquals("short", "much longer string")).toBe(false);
  });

  it("is false for the empty string against a secret", async () => {
    expect(await secureEquals("", "secret")).toBe(false);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd server && npm test -- crypto`
Expected: FAIL — cannot resolve `../src/crypto`

- [ ] **Step 3: Write the implementation**

`server/src/crypto.ts`:

```typescript
/**
 * Small wrappers over WebCrypto. Workers have no Node `crypto` module —
 * everything here uses the browser-standard `crypto` global.
 */

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * A 256-bit random token, hex encoded. This is what a device presents as
 * its bearer credential. `getRandomValues` is cryptographically secure;
 * `Math.random` is not and must never be used for this.
 */
export function randomToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return toHex(bytes);
}

async function sha256Bytes(input: string): Promise<Uint8Array> {
  const data = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return new Uint8Array(digest);
}

/** SHA-256 of a string, hex encoded. Used to store tokens without storing them. */
export async function sha256Hex(input: string): Promise<string> {
  return toHex(await sha256Bytes(input));
}

/**
 * Compares two strings without leaking their contents through timing.
 *
 * A normal `===` on strings returns as soon as it finds a differing byte,
 * so an attacker who can measure response times can discover a secret one
 * character at a time. Hashing both sides first gives two values that are
 * always exactly 32 bytes, so the loop below always runs the same number
 * of iterations regardless of the inputs — this leaks neither content nor
 * length.
 */
export async function secureEquals(a: string, b: string): Promise<boolean> {
  const [ha, hb] = await Promise.all([sha256Bytes(a), sha256Bytes(b)]);

  let diff = 0;
  for (let i = 0; i < ha.length; i++) {
    diff |= ha[i] ^ hb[i];
  }
  return diff === 0;
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd server && npm test -- crypto`
Expected: all nine tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/crypto.ts server/test/crypto.test.ts
git commit -m "feat(server): add hashing, token generation and timing-safe compare"
```

---

## Task 4: Shared HTTP helpers, limits, and the message allowlist

**Files:**
- Create: `server/src/http.ts`, `server/src/limits.ts`, `server/src/messages.ts`
- Create: `server/test/messages.test.ts`

**Interfaces:**
- Consumes: `Env` from Task 1
- Produces:
  - `fail(c, status, code, message)` — returns a typed JSON error response
  - `nowSeconds(): number`
  - `MAX_SENDS_PER_HOUR = 500`, `MAX_ENROLL_ATTEMPTS_PER_HOUR = 5`
  - `tooManyEnrollAttempts(env: Env, ip: string): Promise<boolean>`
  - `overSendCeiling(env: Env, person: number): Promise<boolean>`
  - `isValidMsgId(id: unknown): id is number`

- [ ] **Step 1: Write the failing test**

`server/test/messages.test.ts`:

```typescript
import { env } from "cloudflare:test";
import { describe, it, expect } from "vitest";
import { isValidMsgId } from "../src/messages";
import { overSendCeiling, MAX_SENDS_PER_HOUR } from "../src/limits";
import { nowSeconds } from "../src/http";

describe("isValidMsgId", () => {
  it("accepts the four defined messages", () => {
    expect(isValidMsgId(1)).toBe(true);
    expect(isValidMsgId(2)).toBe(true);
    expect(isValidMsgId(3)).toBe(true);
    expect(isValidMsgId(4)).toBe(true);
  });

  it("rejects ids outside the allowlist", () => {
    expect(isValidMsgId(0)).toBe(false);
    expect(isValidMsgId(5)).toBe(false);
    expect(isValidMsgId(-1)).toBe(false);
  });

  it("rejects non-integers and non-numbers", () => {
    expect(isValidMsgId(1.5)).toBe(false);
    expect(isValidMsgId("1")).toBe(false);
    expect(isValidMsgId(null)).toBe(false);
    expect(isValidMsgId(undefined)).toBe(false);
    expect(isValidMsgId({})).toBe(false);
  });
});

describe("overSendCeiling", () => {
  it("is false when the person has sent nothing", async () => {
    expect(await overSendCeiling(env, 1)).toBe(false);
  });

  it("is true once the ceiling is reached within the hour", async () => {
    const now = nowSeconds();
    const statements = Array.from({ length: MAX_SENDS_PER_HOUR }, (_, i) =>
      env.DB.prepare(
        `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at)
         VALUES (?, ?, ?, ?, ?)`,
      ).bind(`ceiling-${i}`, 2, 1, 1, now - 10),
    );
    await env.DB.batch(statements);

    expect(await overSendCeiling(env, 2)).toBe(true);
  });

  it("ignores sends older than an hour", async () => {
    const now = nowSeconds();
    await env.DB.prepare(
      `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind("stale-1", 1, 2, 1, now - 7200)
      .run();

    expect(await overSendCeiling(env, 1)).toBe(false);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd server && npm test -- messages`
Expected: FAIL — cannot resolve `../src/messages`

- [ ] **Step 3: Write `server/src/http.ts`**

```typescript
import type { Context } from "hono";
import type { App } from "./env";
// ContentfulStatusCode, not StatusCode: `c.json()` always writes a body, so it
// rejects the contentless codes (204, 304) and the -1 unofficial code that the
// wider StatusCode union allows. fail() should never be called with those.
import type { ContentfulStatusCode } from "hono/utils/http-status";

/**
 * Every error the API returns has this shape, so the Android client only
 * ever has to parse one thing.
 */
export function fail(
  c: Context<App>,
  status: ContentfulStatusCode,
  code: string,
  message: string,
) {
  return c.json({ error: code, message }, status);
}

/** Epoch seconds. Every timestamp column in the schema uses this unit. */
export function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

/**
 * Reads a JSON body, returning null rather than throwing when the body is
 * absent or malformed. Callers turn null into a 400.
 */
export async function readJson<T>(c: Context<App>): Promise<T | null> {
  const contentType = c.req.header("Content-Type") ?? "";
  if (!contentType.includes("application/json")) return null;

  try {
    return (await c.req.json()) as T;
  } catch {
    return null;
  }
}
```

- [ ] **Step 4: Write `server/src/messages.ts`**

```typescript
/**
 * The server's own list of valid message ids.
 *
 * The Android app has its own copy of this list, with the text, icon and
 * sound for each. The server deliberately knows only the numbers: the words
 * never touch this server, and never touch Google's. Adding a fifth message
 * means changing both this set and the app.
 */
const ALLOWED_MSG_IDS: ReadonlySet<number> = new Set([1, 2, 3, 4]);

export function isValidMsgId(id: unknown): id is number {
  return typeof id === "number" && Number.isInteger(id) && ALLOWED_MSG_IDS.has(id);
}
```

- [ ] **Step 5: Write `server/src/limits.ts`**

```typescript
import type { Env } from "./env";
import { nowSeconds } from "./http";

/**
 * A circuit breaker against a compromised device or a runaway loop — not a
 * usage limit. Normal human tapping will never approach it.
 */
export const MAX_SENDS_PER_HOUR = 500;

/** Enrolment is the only endpoint reachable without a token, so it gets its own limit. */
export const MAX_ENROLL_ATTEMPTS_PER_HOUR = 5;

/** Counts this person's sends in the last hour against the ceiling. */
export async function overSendCeiling(env: Env, person: number): Promise<boolean> {
  const since = nowSeconds() - 3600;

  const row = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM sends WHERE from_person = ? AND sent_at >= ?",
  )
    .bind(person, since)
    .first<{ n: number }>();

  return (row?.n ?? 0) >= MAX_SENDS_PER_HOUR;
}

/**
 * Counts enrolment attempts per IP in a rolling hour, stored in KV.
 *
 * KV is eventually consistent, so this count can lag slightly under
 * concurrent requests. That is acceptable: the codes are 48 hex characters
 * and unguessable on their own. This limit is defence in depth, not the
 * primary control.
 */
export async function tooManyEnrollAttempts(env: Env, ip: string): Promise<boolean> {
  const key = `enroll_attempts:${ip}`;
  const current = Number(await env.TOKEN_CACHE.get(key)) || 0;

  if (current >= MAX_ENROLL_ATTEMPTS_PER_HOUR) return true;

  await env.TOKEN_CACHE.put(key, String(current + 1), { expirationTtl: 3600 });
  return false;
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd server && npm test -- messages`
Expected: all six tests PASS

- [ ] **Step 7: Commit**

```bash
git add server/src/http.ts server/src/limits.ts server/src/messages.ts server/test/messages.test.ts
git commit -m "feat(server): add HTTP helpers, rate limits and message allowlist"
```

---

## Task 5: `POST /v1/enroll`

**Files:**
- Create: `server/src/routes/enroll.ts`
- Modify: `server/src/index.ts`
- Create: `server/test/enroll.test.ts`

**Interfaces:**
- Consumes: `randomToken`, `sha256Hex`, `secureEquals` (Task 3); `fail`, `nowSeconds`, `readJson` (Task 4); `tooManyEnrollAttempts` (Task 4)
- Produces: `POST /v1/enroll` returning `{device_id, auth_token, person, partner_name}`

- [ ] **Step 1: Write the failing test**

`server/test/enroll.test.ts`:

```typescript
import { SELF, env } from "cloudflare:test";
import { describe, it, expect, beforeEach } from "vitest";

async function enroll(body: unknown, ip = "203.0.113.1") {
  return SELF.fetch("https://love-button.test/v1/enroll", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "CF-Connecting-IP": ip,
    },
    body: JSON.stringify(body),
  });
}

describe("POST /v1/enroll", () => {
  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM devices").run();
  });

  it("issues a token for a valid code and records the device", async () => {
    const res = await enroll({
      code: "test-code-one",
      fcm_token: "fcm-abc",
      label: "test phone",
    });

    expect(res.status).toBe(200);
    const body = await res.json<{
      device_id: string;
      auth_token: string;
      person: number;
      partner_name: string;
    }>();

    expect(body.person).toBe(1);
    expect(body.partner_name).toBe("Her");
    expect(body.auth_token).toMatch(/^[0-9a-f]{64}$/);

    const row = await env.DB.prepare(
      "SELECT person, fcm_token, label FROM devices WHERE id = ?",
    )
      .bind(body.device_id)
      .first<{ person: number; fcm_token: string; label: string }>();

    expect(row?.person).toBe(1);
    expect(row?.fcm_token).toBe("fcm-abc");
  });

  it("maps the second code to person 2", async () => {
    const res = await enroll({ code: "test-code-two", fcm_token: "fcm-xyz" });

    const body = await res.json<{ person: number; partner_name: string }>();
    expect(body.person).toBe(2);
    expect(body.partner_name).toBe("Giorgos");
  });

  it("never stores the raw token", async () => {
    const res = await enroll({ code: "test-code-one", fcm_token: "fcm-1" });
    const body = await res.json<{ auth_token: string; device_id: string }>();

    const row = await env.DB.prepare(
      "SELECT auth_hash FROM devices WHERE id = ?",
    )
      .bind(body.device_id)
      .first<{ auth_hash: string }>();

    expect(row?.auth_hash).not.toBe(body.auth_token);
    expect(row?.auth_hash).toMatch(/^[0-9a-f]{64}$/);
  });

  it("rejects a wrong code with 403", async () => {
    const res = await enroll({ code: "not-the-code", fcm_token: "fcm-1" });

    expect(res.status).toBe(403);
    expect(await res.json()).toMatchObject({ error: "invalid_code" });
  });

  it("rejects a missing code with 400", async () => {
    const res = await enroll({ fcm_token: "fcm-1" });

    expect(res.status).toBe(400);
  });

  it("rejects a non-JSON content type with 400", async () => {
    const res = await SELF.fetch("https://love-button.test/v1/enroll", {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: "code=test-code-one",
    });

    expect(res.status).toBe(400);
  });

  it("rate limits after five attempts from one IP", async () => {
    const ip = "203.0.113.99";

    for (let i = 0; i < 5; i++) {
      await enroll({ code: "wrong", fcm_token: "fcm-1" }, ip);
    }

    const res = await enroll({ code: "test-code-one", fcm_token: "fcm-1" }, ip);

    expect(res.status).toBe(429);
    expect(res.headers.get("Retry-After")).toBe("3600");
  });

  it("replaces an existing row that already claimed the same FCM token", async () => {
    const first = await enroll({ code: "test-code-one", fcm_token: "same-fcm" });
    const firstId = (await first.json<{ device_id: string }>()).device_id;

    const second = await enroll({ code: "test-code-one", fcm_token: "same-fcm" });
    const secondId = (await second.json<{ device_id: string }>()).device_id;

    expect(secondId).not.toBe(firstId);

    const rows = await env.DB.prepare(
      "SELECT id FROM devices WHERE fcm_token = ?",
    )
      .bind("same-fcm")
      .all<{ id: string }>();

    expect(rows.results.map((r) => r.id)).toEqual([secondId]);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd server && npm test -- enroll`
Expected: FAIL — every request returns 404 because the route does not exist

- [ ] **Step 3: Write `server/src/routes/enroll.ts`**

```typescript
import { Hono } from "hono";
import type { App, Env } from "../env";
import { randomToken, sha256Hex, secureEquals } from "../crypto";
import { fail, nowSeconds, readJson } from "../http";
import { tooManyEnrollAttempts } from "../limits";

interface EnrollBody {
  code?: unknown;
  fcm_token?: unknown;
  label?: unknown;
}

/**
 * Works out which of the two people presented a code.
 *
 * Both comparisons always run — no early return — so the time taken does
 * not reveal which code was matched or how far a guess got.
 */
async function personForCode(env: Env, code: string): Promise<1 | 2 | null> {
  const [isOne, isTwo] = await Promise.all([
    secureEquals(code, env.ENROLL_CODE_1),
    secureEquals(code, env.ENROLL_CODE_2),
  ]);

  if (isOne) return 1;
  if (isTwo) return 2;
  return null;
}

function partnerName(env: Env, person: 1 | 2): string {
  return person === 1 ? env.PERSON_2_NAME : env.PERSON_1_NAME;
}

export const enroll = new Hono<App>();

enroll.post("/enroll", async (c) => {
  const ip = c.req.header("CF-Connecting-IP") ?? "unknown";

  if (await tooManyEnrollAttempts(c.env, ip)) {
    c.header("Retry-After", "3600");
    return fail(c, 429, "rate_limited", "Too many enrolment attempts. Try again later.");
  }

  const body = await readJson<EnrollBody>(c);
  if (!body || typeof body.code !== "string" || typeof body.fcm_token !== "string") {
    return fail(c, 400, "bad_request", "code and fcm_token are required.");
  }

  const person = await personForCode(c.env, body.code);
  if (person === null) {
    return fail(c, 403, "invalid_code", "That enrolment code is not valid.");
  }

  const deviceId = crypto.randomUUID();
  const authToken = randomToken();
  const authHash = await sha256Hex(authToken);
  const label = typeof body.label === "string" ? body.label.slice(0, 80) : null;
  const now = nowSeconds();

  // A given phone has exactly one FCM token, so an existing row with the
  // same token is the same physical device re-enrolling. Drop the old row
  // rather than accumulating dead ones that would double every push.
  await c.env.DB.batch([
    c.env.DB.prepare("DELETE FROM devices WHERE fcm_token = ?").bind(body.fcm_token),
    c.env.DB.prepare(
      `INSERT INTO devices (id, person, auth_hash, fcm_token, label, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).bind(deviceId, person, authHash, body.fcm_token, label, now, now),
  ]);

  // The raw token is returned exactly once and is never recoverable again.
  return c.json({
    device_id: deviceId,
    auth_token: authToken,
    person,
    partner_name: partnerName(c.env, person),
  });
});
```

- [ ] **Step 4: Wire the route into `server/src/index.ts`**

Replace the contents of `server/src/index.ts`:

```typescript
import { Hono } from "hono";
import type { App } from "./env";
import { enroll } from "./routes/enroll";

const app = new Hono<App>();

/**
 * Exactly two endpoints are reachable without a device bearer token:
 * `/health`, which takes no credential at all, and `/v1/enroll`, which takes
 * an enrolment code instead. Every other route goes through requireDevice.
 */
app.get("/health", (c) => c.json({ ok: true }));

app.route("/v1", enroll);

export default app;
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd server && npm test -- enroll`
Expected: all eight tests PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/routes/enroll.ts server/src/index.ts server/test/enroll.test.ts
git commit -m "feat(server): add enrolment endpoint with rate limiting"
```

---

## Task 6: Bearer authentication and `/v1/devices`

**Files:**
- Create: `server/src/auth.ts`, `server/src/routes/devices.ts`
- Modify: `server/src/index.ts`
- Create: `server/test/auth.test.ts`

**Interfaces:**
- Consumes: `sha256Hex` (Task 3); `fail`, `nowSeconds`, `readJson` (Task 4); `DeviceRow`, `App` (Task 1)
- Produces:
  - `requireDevice` — Hono middleware that sets `c.get("device")` to a `DeviceRow`
  - `POST /v1/devices` (refresh FCM token), `DELETE /v1/devices` (deregister)

- [ ] **Step 1: Write the failing test**

`server/test/auth.test.ts`:

```typescript
import { SELF, env } from "cloudflare:test";
import { describe, it, expect, beforeEach } from "vitest";
import { sha256Hex } from "../src/crypto";

const TOKEN = "a".repeat(64);

// `token` is a parameter because devices.auth_hash is NOT NULL UNIQUE: any test
// that seeds a second device must give it a different token, or the insert is
// rejected by the constraint rather than by the code under test.
async function seedDevice(
  id = "dev-auth",
  person = 1,
  fcm = "fcm-seed",
  token = TOKEN,
) {
  await env.DB.prepare(
    `INSERT INTO devices (id, person, auth_hash, fcm_token, label, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(id, person, await sha256Hex(token), fcm, "seeded", 1000, 1000)
    .run();
}

function authed(path: string, init: RequestInit = {}, token = TOKEN) {
  return SELF.fetch(`https://love-button.test${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      ...(init.headers ?? {}),
    },
  });
}

describe("bearer authentication", () => {
  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM devices").run();
    await seedDevice();
  });

  it("rejects a request with no Authorization header", async () => {
    const res = await SELF.fetch("https://love-button.test/v1/devices", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fcm_token: "new" }),
    });

    expect(res.status).toBe(401);
    expect(await res.json()).toMatchObject({ error: "unauthorized" });
  });

  it("rejects a malformed Authorization header", async () => {
    const res = await SELF.fetch("https://love-button.test/v1/devices", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: TOKEN, // missing the "Bearer " prefix
      },
      body: JSON.stringify({ fcm_token: "new" }),
    });

    expect(res.status).toBe(401);
  });

  it("rejects an unknown token", async () => {
    const res = await authed(
      "/v1/devices",
      { method: "POST", body: JSON.stringify({ fcm_token: "new" }) },
      "b".repeat(64),
    );

    expect(res.status).toBe(401);
  });
});

describe("POST /v1/devices", () => {
  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM devices").run();
    await seedDevice();
  });

  it("updates the stored FCM token", async () => {
    const res = await authed("/v1/devices", {
      method: "POST",
      body: JSON.stringify({ fcm_token: "fcm-rotated" }),
    });

    expect(res.status).toBe(200);

    const row = await env.DB.prepare(
      "SELECT fcm_token FROM devices WHERE id = ?",
    )
      .bind("dev-auth")
      .first<{ fcm_token: string }>();

    expect(row?.fcm_token).toBe("fcm-rotated");
  });

  it("removes another device row holding the same FCM token", async () => {
    await seedDevice("dev-stale", 2, "fcm-rotated", "b".repeat(64));

    await authed("/v1/devices", {
      method: "POST",
      body: JSON.stringify({ fcm_token: "fcm-rotated" }),
    });

    const stale = await env.DB.prepare("SELECT id FROM devices WHERE id = ?")
      .bind("dev-stale")
      .first();

    expect(stale).toBeNull();
  });

  it("rejects a missing fcm_token with 400", async () => {
    const res = await authed("/v1/devices", {
      method: "POST",
      body: JSON.stringify({}),
    });

    expect(res.status).toBe(400);
  });
});

describe("DELETE /v1/devices", () => {
  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM devices").run();
    await seedDevice();
  });

  it("deletes only the calling device", async () => {
    await seedDevice("dev-other", 2, "fcm-other", "b".repeat(64));

    const res = await authed("/v1/devices", { method: "DELETE" });
    expect(res.status).toBe(200);

    const gone = await env.DB.prepare("SELECT id FROM devices WHERE id = ?")
      .bind("dev-auth")
      .first();
    const survivor = await env.DB.prepare("SELECT id FROM devices WHERE id = ?")
      .bind("dev-other")
      .first();

    expect(gone).toBeNull();
    expect(survivor).not.toBeNull();
  });

  it("makes the token unusable afterwards", async () => {
    await authed("/v1/devices", { method: "DELETE" });

    const res = await authed("/v1/devices", { method: "DELETE" });
    expect(res.status).toBe(401);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd server && npm test -- auth`
Expected: FAIL — routes return 404

- [ ] **Step 3: Write `server/src/auth.ts`**

```typescript
import { createMiddleware } from "hono/factory";
import type { App, DeviceRow } from "./env";
import { sha256Hex } from "./crypto";
import { fail } from "./http";

const BEARER = "Bearer ";

/**
 * Turns a bearer token into a device row, or rejects the request.
 *
 * Note what is NOT here: nothing reads an identity from the request body.
 * After this middleware runs, `c.get("device")` is the single source of
 * truth for who is calling. That is invariant 1 from the spec.
 */
export const requireDevice = createMiddleware<App>(async (c, next) => {
  const header = c.req.header("Authorization") ?? "";

  if (!header.startsWith(BEARER)) {
    return fail(c, 401, "unauthorized", "Missing bearer token.");
  }

  const token = header.slice(BEARER.length);

  // The stored value is a hash, so we hash what was presented and look for
  // a match. A leaked database yields no usable credentials.
  const authHash = await sha256Hex(token);

  const device = await c.env.DB.prepare(
    "SELECT id, person, fcm_token FROM devices WHERE auth_hash = ?",
  )
    .bind(authHash)
    .first<DeviceRow>();

  // The two 401 messages in this file are deliberately different, and that is
  // not an information leak. Each only restates what the caller already sent:
  // "Missing bearer token" means they presented no usable header, "Unknown or
  // revoked token" means they presented one that matches no device row. Neither
  // reveals server state the caller does not already hold, and a valid token is
  // already distinguishable from an invalid one by the status code alone. They
  // are kept distinct because the difference between "my header is malformed"
  // and "my phone was deregistered" is exactly what you need when debugging.
  if (!device) {
    return fail(c, 401, "unauthorized", "Unknown or revoked token.");
  }

  c.set("device", device);
  await next();
});
```

- [ ] **Step 4: Write `server/src/routes/devices.ts`**

```typescript
import { Hono } from "hono";
import type { App } from "../env";
import { requireDevice } from "../auth";
import { fail, nowSeconds, readJson } from "../http";

interface DevicesBody {
  fcm_token?: unknown;
}

export const devices = new Hono<App>();

devices.use("/devices", requireDevice);

/**
 * FCM registration tokens rotate. The app calls this on every launch and
 * whenever the Firebase SDK reports a new one.
 */
devices.post("/devices", async (c) => {
  const body = await readJson<DevicesBody>(c);

  if (!body || typeof body.fcm_token !== "string") {
    return fail(c, 400, "bad_request", "fcm_token is required.");
  }

  const device = c.get("device");
  const now = nowSeconds();

  await c.env.DB.batch([
    // Another row claiming this token is a stale record of the same phone.
    c.env.DB.prepare("DELETE FROM devices WHERE fcm_token = ? AND id != ?").bind(
      body.fcm_token,
      device.id,
    ),
    c.env.DB.prepare(
      "UPDATE devices SET fcm_token = ?, updated_at = ? WHERE id = ?",
    ).bind(body.fcm_token, now, device.id),
  ]);

  return c.json({ ok: true });
});

/** Sign-out. Deletes this device only; the other person is untouched. */
devices.delete("/devices", async (c) => {
  const device = c.get("device");

  await c.env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(device.id).run();

  return c.json({ ok: true });
});
```

- [ ] **Step 5: Wire the route into `server/src/index.ts`**

```typescript
import { Hono } from "hono";
import type { App } from "./env";
import { enroll } from "./routes/enroll";
import { devices } from "./routes/devices";

const app = new Hono<App>();

/**
 * Exactly two endpoints are reachable without a device bearer token:
 * `/health`, which takes no credential at all, and `/v1/enroll`, which takes
 * an enrolment code instead. Every other route goes through requireDevice.
 */
app.get("/health", (c) => c.json({ ok: true }));

app.route("/v1", enroll);
app.route("/v1", devices);

export default app;
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd server && npm test -- auth`
Expected: all eight tests PASS

- [ ] **Step 7: Run the whole suite**

Run: `cd server && npm test`
Expected: every test from Tasks 1–6 PASSES

- [ ] **Step 8: Commit**

```bash
git add server/src/auth.ts server/src/routes/devices.ts server/src/index.ts server/test/auth.test.ts
git commit -m "feat(server): add bearer auth middleware and device registration"
```

---

## Task 7: Google OAuth access token

**Files:**
- Create: `server/src/google-oauth.ts`
- Create: `server/test/google-oauth.test.ts`

**Interfaces:**
- Consumes: `Env` (Task 1)
- Produces:
  - `getAccessToken(env: Env): Promise<string>` — KV-cached
  - `buildSignedJwt(serviceAccount: ServiceAccount, nowSec: number): Promise<string>`
  - `ServiceAccount` interface with `client_email` and `private_key`
  - KV cache key `google_access_token`

- [ ] **Step 1: Write the failing test**

`server/test/google-oauth.test.ts`:

```typescript
import { env } from "cloudflare:test";
import { describe, it, expect, beforeEach } from "vitest";
import {
  getAccessToken,
  buildSignedJwt,
  ACCESS_TOKEN_CACHE_KEY,
} from "../src/google-oauth";

/** Generates a throwaway RSA key and returns it in the PEM form Google uses. */
async function generateTestPrivateKeyPem(): Promise<string> {
  // generateKey is typed CryptoKey | CryptoKeyPair because its return shape
  // depends on the algorithm; RSASSA-PKCS1-v1_5 always yields a pair.
  const pair = (await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;

  // exportKey is typed ArrayBuffer | JsonWebKey; the "pkcs8" format is the
  // ArrayBuffer branch.
  const pkcs8 = (await crypto.subtle.exportKey(
    "pkcs8",
    pair.privateKey,
  )) as ArrayBuffer;
  const b64 = btoa(String.fromCharCode(...new Uint8Array(pkcs8)));
  const lines = b64.match(/.{1,64}/g)!.join("\n");

  return `-----BEGIN PRIVATE KEY-----\n${lines}\n-----END PRIVATE KEY-----\n`;
}

describe("getAccessToken", () => {
  beforeEach(async () => {
    await env.TOKEN_CACHE.delete(ACCESS_TOKEN_CACHE_KEY);
  });

  it("returns the cached token without minting a new one", async () => {
    // If this tried to mint, it would fail — FCM_SERVICE_ACCOUNT is "{}" in
    // tests. Returning the cached value proves the cache is consulted first.
    await env.TOKEN_CACHE.put(ACCESS_TOKEN_CACHE_KEY, "cached-token-value");

    expect(await getAccessToken(env)).toBe("cached-token-value");
  });

  it("throws a clear error when the service account is not configured", async () => {
    await expect(getAccessToken(env)).rejects.toThrow(/service account/i);
  });
});

describe("buildSignedJwt", () => {
  it("produces three base64url segments with the expected claims", async () => {
    const jwt = await buildSignedJwt(
      {
        client_email: "robot@test-project.iam.gserviceaccount.com",
        private_key: await generateTestPrivateKeyPem(),
      },
      1_700_000_000,
    );

    const parts = jwt.split(".");
    expect(parts).toHaveLength(3);

    const decode = (segment: string) =>
      JSON.parse(atob(segment.replace(/-/g, "+").replace(/_/g, "/")));

    expect(decode(parts[0])).toEqual({ alg: "RS256", typ: "JWT" });

    const claims = decode(parts[1]);
    expect(claims.iss).toBe("robot@test-project.iam.gserviceaccount.com");
    expect(claims.scope).toBe("https://www.googleapis.com/auth/firebase.messaging");
    expect(claims.aud).toBe("https://oauth2.googleapis.com/token");
    expect(claims.iat).toBe(1_700_000_000);
    expect(claims.exp).toBe(1_700_000_000 + 3600);

    // A signature over a 2048-bit key is 256 bytes; base64url of that is 342 chars.
    expect(parts[2].length).toBe(342);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd server && npm test -- google-oauth`
Expected: FAIL — cannot resolve `../src/google-oauth`

- [ ] **Step 3: Write `server/src/google-oauth.ts`**

```typescript
import type { Env } from "./env";
import { nowSeconds } from "./http";

export const ACCESS_TOKEN_CACHE_KEY = "google_access_token";

/** 55 minutes. Google's tokens last an hour; this leaves five minutes of margin. */
const ACCESS_TOKEN_TTL_SECONDS = 3300;

const TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

/** The two fields we need from the service account JSON. */
export interface ServiceAccount {
  client_email: string;
  private_key: string;
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlEncodeJson(value: unknown): string {
  return base64UrlEncode(new TextEncoder().encode(JSON.stringify(value)));
}

/**
 * Converts a PEM private key into the raw bytes WebCrypto expects.
 *
 * The `private_key` field in a service account JSON is PEM: a base64 body
 * wrapped in header and footer lines. `importKey` wants the decoded bytes.
 */
function pemToArrayBuffer(pem: string): ArrayBuffer {
  const body = pem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "");

  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);

  return bytes.buffer;
}

/**
 * Builds the JWT that proves we hold the service account's private key.
 *
 * Google calls this a "self-signed JWT bearer token": we sign an assertion
 * about who we are, hand it to Google, and get back a short-lived access
 * token. It is how a server authenticates when no human is present to log in.
 */
export async function buildSignedJwt(
  serviceAccount: ServiceAccount,
  nowSec: number,
): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: serviceAccount.client_email,
    scope: FCM_SCOPE,
    aud: TOKEN_ENDPOINT,
    iat: nowSec,
    exp: nowSec + 3600,
  };

  const signingInput = `${base64UrlEncodeJson(header)}.${base64UrlEncodeJson(claims)}`;

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(serviceAccount.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput),
  );

  return `${signingInput}.${base64UrlEncode(new Uint8Array(signature))}`;
}

function parseServiceAccount(raw: string): ServiceAccount {
  let parsed: Partial<ServiceAccount>;

  try {
    parsed = JSON.parse(raw) as Partial<ServiceAccount>;
  } catch {
    throw new Error("FCM service account is not valid JSON.");
  }

  if (!parsed.client_email || !parsed.private_key) {
    throw new Error(
      "FCM service account is missing client_email or private_key. " +
        "Set it with: wrangler secret put FCM_SERVICE_ACCOUNT",
    );
  }

  return { client_email: parsed.client_email, private_key: parsed.private_key };
}

async function mintAccessToken(env: Env): Promise<string> {
  const serviceAccount = parseServiceAccount(env.FCM_SERVICE_ACCOUNT);
  const assertion = await buildSignedJwt(serviceAccount, nowSeconds());

  const res = await fetch(TOKEN_ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });

  if (!res.ok) {
    throw new Error(`Google token exchange failed: ${res.status} ${await res.text()}`);
  }

  const body = (await res.json()) as { access_token?: string };
  if (!body.access_token) {
    throw new Error("Google token exchange returned no access_token.");
  }

  return body.access_token;
}

/**
 * Returns a usable Google access token, minting one only when the cache is
 * cold.
 *
 * The cache is not an optimisation. The Workers free tier allows 10ms of CPU
 * per request, and the RSA signature above sits close to that ceiling — doing
 * it on every send would push requests over the limit.
 */
export async function getAccessToken(env: Env): Promise<string> {
  const cached = await env.TOKEN_CACHE.get(ACCESS_TOKEN_CACHE_KEY);
  if (cached) return cached;

  const token = await mintAccessToken(env);

  await env.TOKEN_CACHE.put(ACCESS_TOKEN_CACHE_KEY, token, {
    expirationTtl: ACCESS_TOKEN_TTL_SECONDS,
  });

  return token;
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd server && npm test -- google-oauth`
Expected: all three tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/google-oauth.ts server/test/google-oauth.test.ts
git commit -m "feat(server): mint and cache Google OAuth access tokens"
```

---

## Task 8: FCM push module

**Files:**
- Create: `server/src/fcm.ts`
- Create: `server/test/fcm.test.ts`

**Interfaces:**
- Consumes: `Env` (Task 1) for `FIREBASE_PROJECT_ID`. The access token is a parameter supplied by the caller — this module never fetches one, which keeps it independently testable.
- Produces:
  - `type PushResult = "ok" | "unregistered" | "error"`
  - `sendPush(env, accessToken, fcmToken, data, priority): Promise<PushResult>`
  - `type PushPriority = "HIGH" | "NORMAL"`

- [ ] **Step 1: Write the failing test**

`server/test/fcm.test.ts`:

```typescript
import { env, fetchMock } from "cloudflare:test";
import { describe, it, expect, beforeAll, afterEach } from "vitest";
import { sendPush } from "../src/fcm";

const FCM_ORIGIN = "https://fcm.googleapis.com";
const FCM_PATH = "/v1/projects/test-project/messages:send";

beforeAll(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});

afterEach(() => fetchMock.assertNoPendingInterceptors());

describe("sendPush", () => {
  it("posts a data-only high-priority message and reports ok", async () => {
    let captured: any;

    fetchMock
      .get(FCM_ORIGIN)
      .intercept({
        path: FCM_PATH,
        method: "POST",
      })
      .reply(200, (opts) => {
        captured = JSON.parse(opts.body as string);
        return { name: "projects/test-project/messages/1" };
      });

    const result = await sendPush(
      env,
      "access-token",
      "device-fcm-token",
      { type: "msg", send_id: "s1", msg_id: "3" },
      "HIGH",
    );

    expect(result).toBe("ok");
    expect(captured.message.token).toBe("device-fcm-token");
    expect(captured.message.data).toEqual({
      type: "msg",
      send_id: "s1",
      msg_id: "3",
    });
    expect(captured.message.android.priority).toBe("HIGH");

    // Critical: a `notification` block would let the system tray render the
    // message and bypass the app's per-message channels and sounds.
    expect(captured.message.notification).toBeUndefined();
  });

  it("reports unregistered when FCM says the token is dead", async () => {
    fetchMock
      .get(FCM_ORIGIN)
      .intercept({ path: FCM_PATH, method: "POST" })
      .reply(404, {
        error: {
          status: "NOT_FOUND",
          details: [
            {
              "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
              errorCode: "UNREGISTERED",
            },
          ],
        },
      });

    const result = await sendPush(env, "access-token", "dead-token", { type: "msg" }, "HIGH");

    expect(result).toBe("unregistered");
  });

  it("reports unregistered for an invalid argument", async () => {
    fetchMock
      .get(FCM_ORIGIN)
      .intercept({ path: FCM_PATH, method: "POST" })
      .reply(400, {
        error: {
          status: "INVALID_ARGUMENT",
          details: [
            {
              "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
              errorCode: "INVALID_ARGUMENT",
            },
          ],
        },
      });

    const result = await sendPush(env, "access-token", "bad-token", { type: "msg" }, "HIGH");

    expect(result).toBe("unregistered");
  });

  it("reports error for a transient server failure", async () => {
    fetchMock
      .get(FCM_ORIGIN)
      .intercept({ path: FCM_PATH, method: "POST" })
      .reply(503, { error: { status: "UNAVAILABLE" } });

    const result = await sendPush(env, "access-token", "some-token", { type: "msg" }, "NORMAL");

    expect(result).toBe("error");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd server && npm test -- fcm`
Expected: FAIL — cannot resolve `../src/fcm`

- [ ] **Step 3: Write `server/src/fcm.ts`**

```typescript
import type { Env } from "./env";

/**
 * What happened to one push.
 *
 * `unregistered` is separated from `error` because it means something
 * permanent — the app was uninstalled or the token expired — and the caller
 * should delete the device row rather than retry.
 */
export type PushResult = "ok" | "unregistered" | "error";

/** HIGH wakes the device through Doze. NORMAL costs far less battery. */
export type PushPriority = "HIGH" | "NORMAL";

/** FCM data payloads are string-to-string only. */
export type PushData = Record<string, string>;

function fcmEndpoint(env: Env): string {
  return `https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`;
}

interface FcmErrorBody {
  error?: {
    status?: string;
    details?: Array<{ errorCode?: string }>;
  };
}

/**
 * True when FCM is telling us this specific token will never work again.
 *
 * Only the nested `details[].errorCode` proves that. The outer `error.status` is
 * derived from the HTTP status, so a bare "NOT_FOUND" there can equally mean a
 * mistyped FIREBASE_PROJECT_ID or a decommissioned Firebase project — in which
 * case every one of the recipient's tokens 404s identically, and trusting the
 * outer field would delete every device row she has. Her app cannot then recover
 * via /v1/devices, because her bearer token's row went with them: she would have
 * to re-enrol by hand. Trust the specific detail, never the generic status.
 */
function isPermanentTokenFailure(status: number, body: FcmErrorBody): boolean {
  if (status !== 404 && status !== 400) return false;

  const detailCodes = (body.error?.details ?? []).map((d) => d.errorCode);

  return detailCodes.includes("UNREGISTERED") ||
    detailCodes.includes("INVALID_ARGUMENT");
}

/**
 * Sends one data-only push to one device.
 *
 * Never add a `notification` block. Doing so hands rendering to the Android
 * system tray, which bypasses the app's per-message notification channels
 * and therefore its per-message sounds — the whole point of the app.
 */
export async function sendPush(
  env: Env,
  accessToken: string,
  fcmToken: string,
  data: PushData,
  priority: PushPriority,
): Promise<PushResult> {
  const res = await fetch(fcmEndpoint(env), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      message: {
        token: fcmToken,
        data,
        android: { priority },
      },
    }),
  });

  if (res.ok) return "ok";

  let body: FcmErrorBody = {};
  try {
    body = (await res.json()) as FcmErrorBody;
  } catch {
    // A non-JSON error body is still an error; fall through with an empty object.
  }

  return isPermanentTokenFailure(res.status, body) ? "unregistered" : "error";
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd server && npm test -- fcm`
Expected: all four tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/fcm.ts server/test/fcm.test.ts
git commit -m "feat(server): add FCM push module with dead-token detection"
```

---

## Task 9: `POST /v1/send`

**Files:**
- Create: `server/src/routes/send.ts`
- Modify: `server/src/index.ts`
- Create: `server/test/send.test.ts`

**Interfaces:**
- Consumes: `requireDevice` (Task 6); `getAccessToken`, `ACCESS_TOKEN_CACHE_KEY` (Task 7); `sendPush` (Task 8); `isValidMsgId` (Task 4); `overSendCeiling`, `MAX_SENDS_PER_HOUR` (Task 4)
- Produces: `POST /v1/send` returning `{send_id, delivered}`; exported `recipientOf(person: number): number`

- [ ] **Step 1: Write the failing test**

`server/test/send.test.ts`:

```typescript
import { SELF, env, fetchMock } from "cloudflare:test";
import { describe, it, expect, beforeAll, beforeEach, afterEach } from "vitest";
import { sha256Hex } from "../src/crypto";
import { ACCESS_TOKEN_CACHE_KEY } from "../src/google-oauth";
import { recipientOf } from "../src/routes/send";
import { MAX_SENDS_PER_HOUR } from "../src/limits";
import { nowSeconds } from "../src/http";

const TOKEN = "c".repeat(64);
const FCM_ORIGIN = "https://fcm.googleapis.com";
const FCM_PATH = "/v1/projects/test-project/messages:send";

beforeAll(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});

afterEach(() => fetchMock.assertNoPendingInterceptors());

// `object`, not `unknown`: undici's MockInterceptor.reply is typed
// `TData extends object`, so `unknown` does not satisfy it under strict mode.
function interceptFcm(status: number, body: object) {
  fetchMock
    .get(FCM_ORIGIN)
    .intercept({ path: FCM_PATH, method: "POST" })
    .reply(status, body);
}

async function seed(id: string, person: number, fcm: string | null, token?: string) {
  await env.DB.prepare(
    `INSERT INTO devices (id, person, auth_hash, fcm_token, label, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(id, person, await sha256Hex(token ?? `${id}-token`), fcm, id, 1000, 1000)
    .run();
}

function send(body: unknown, token = TOKEN) {
  return SELF.fetch("https://love-button.test/v1/send", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  });
}

describe("recipientOf", () => {
  it("maps each person to the other one", () => {
    expect(recipientOf(1)).toBe(2);
    expect(recipientOf(2)).toBe(1);
  });
});

describe("POST /v1/send", () => {
  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM devices").run();
    await env.DB.prepare("DELETE FROM sends").run();
    await env.TOKEN_CACHE.put(ACCESS_TOKEN_CACHE_KEY, "test-access-token");

    await seed("sender", 1, "fcm-sender", TOKEN);
    await seed("receiver", 2, "fcm-receiver");
  });

  it("records the send and pushes to the partner", async () => {
    interceptFcm(200, { name: "projects/test-project/messages/1" });

    const res = await send({ msg_id: 3 });

    expect(res.status).toBe(200);
    const body = await res.json<{ send_id: string; delivered: number }>();
    expect(body.delivered).toBe(1);

    const row = await env.DB.prepare(
      "SELECT from_person, to_person, msg_id FROM sends WHERE id = ?",
    )
      .bind(body.send_id)
      .first<{ from_person: number; to_person: number; msg_id: number }>();

    // Invariant 2: the recipient was derived server-side, never supplied.
    expect(row?.from_person).toBe(1);
    expect(row?.to_person).toBe(2);
    expect(row?.msg_id).toBe(3);
  });

  it("ignores any recipient the client tries to name", async () => {
    interceptFcm(200, { name: "ok" });

    const res = await send({ msg_id: 1, to_person: 1, from_person: 2 });
    const body = await res.json<{ send_id: string }>();

    const row = await env.DB.prepare(
      "SELECT from_person, to_person FROM sends WHERE id = ?",
    )
      .bind(body.send_id)
      .first<{ from_person: number; to_person: number }>();

    expect(row?.from_person).toBe(1);
    expect(row?.to_person).toBe(2);
  });

  it("rejects a msg_id outside the allowlist with 400", async () => {
    const res = await send({ msg_id: 99 });

    expect(res.status).toBe(400);
    expect(await res.json()).toMatchObject({ error: "bad_request" });
  });

  it("rejects a non-integer msg_id with 400", async () => {
    const res = await send({ msg_id: "3" });

    expect(res.status).toBe(400);
  });

  it("returns 200 with delivered 0 when the partner has no device", async () => {
    await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind("receiver").run();

    const res = await send({ msg_id: 1 });

    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ delivered: 0 });
  });

  it("deletes a device row when FCM reports the token is dead", async () => {
    interceptFcm(404, {
      error: {
        status: "NOT_FOUND",
        details: [{ errorCode: "UNREGISTERED" }],
      },
    });

    const res = await send({ msg_id: 1 });

    expect(await res.json()).toMatchObject({ delivered: 0 });

    const gone = await env.DB.prepare("SELECT id FROM devices WHERE id = ?")
      .bind("receiver")
      .first();
    expect(gone).toBeNull();
  });

  it("still records the send when the push fails transiently", async () => {
    interceptFcm(503, { error: { status: "UNAVAILABLE" } });

    const res = await send({ msg_id: 2 });
    const body = await res.json<{ send_id: string; delivered: number }>();

    expect(body.delivered).toBe(0);

    const row = await env.DB.prepare("SELECT id FROM sends WHERE id = ?")
      .bind(body.send_id)
      .first();
    expect(row).not.toBeNull();
  });

  it("returns 429 once the hourly ceiling is reached", async () => {
    const now = nowSeconds();
    const statements = Array.from({ length: MAX_SENDS_PER_HOUR }, (_, i) =>
      env.DB.prepare(
        `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at)
         VALUES (?, ?, ?, ?, ?)`,
      ).bind(`fill-${i}`, 1, 2, 1, now - 5),
    );
    await env.DB.batch(statements);

    const res = await send({ msg_id: 1 });

    expect(res.status).toBe(429);
    expect(res.headers.get("Retry-After")).toBe("3600");
  });

  it("requires authentication", async () => {
    const res = await SELF.fetch("https://love-button.test/v1/send", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ msg_id: 1 }),
    });

    expect(res.status).toBe(401);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd server && npm test -- send`
Expected: FAIL — cannot resolve `../src/routes/send`

- [ ] **Step 3: Write `server/src/routes/send.ts`**

```typescript
import { Hono } from "hono";
import type { App } from "../env";
import { requireDevice } from "../auth";
import { fail, nowSeconds, readJson } from "../http";
import { isValidMsgId } from "../messages";
import { overSendCeiling } from "../limits";
import { getAccessToken } from "../google-oauth";
import { sendPush } from "../fcm";

interface SendBody {
  msg_id?: unknown;
}

/**
 * Invariant 2, in one line.
 *
 * With exactly two people, the recipient is arithmetic: 3 - 1 = 2, 3 - 2 = 1.
 * There is no lookup to get wrong and no request field to tamper with. The
 * CHECK constraint on `devices.person` guarantees the input is 1 or 2.
 */
export function recipientOf(person: number): number {
  return 3 - person;
}

export const send = new Hono<App>();

send.use("/send", requireDevice);

send.post("/send", async (c) => {
  const device = c.get("device");

  if (await overSendCeiling(c.env, device.person)) {
    c.header("Retry-After", "3600");
    return fail(c, 429, "rate_limited", "Hourly send limit reached.");
  }

  const body = await readJson<SendBody>(c);
  if (!body || !isValidMsgId(body.msg_id)) {
    return fail(c, 400, "bad_request", "msg_id must be one of the defined messages.");
  }

  const toPerson = recipientOf(device.person);
  const sendId = crypto.randomUUID();
  const sentAt = nowSeconds();

  // Record the send before pushing. The row is what a receipt correlates
  // against later, and it must exist even if every push fails.
  await c.env.DB.prepare(
    `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at)
     VALUES (?, ?, ?, ?, ?)`,
  )
    .bind(sendId, device.person, toPerson, body.msg_id, sentAt)
    .run();

  const targets = await c.env.DB.prepare(
    "SELECT id, fcm_token FROM devices WHERE person = ? AND fcm_token IS NOT NULL",
  )
    .bind(toPerson)
    .all<{ id: string; fcm_token: string }>();

  const accessToken = await getAccessToken(c.env);

  const data = {
    type: "msg",
    send_id: sendId,
    msg_id: String(body.msg_id),
    from_name: device.person === 1 ? c.env.PERSON_1_NAME : c.env.PERSON_2_NAME,
    sent_at: String(sentAt),
  };

  const results = await Promise.all(
    targets.results.map(async (target) => ({
      target,
      // HIGH priority is what wakes a phone that is in Doze.
      result: await sendPush(c.env, accessToken, target.fcm_token, data, "HIGH"),
    })),
  );

  const dead = results.filter((r) => r.result === "unregistered");
  if (dead.length > 0) {
    await c.env.DB.batch(
      dead.map((r) =>
        c.env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(r.target.id),
      ),
    );
  }

  const delivered = results.filter((r) => r.result === "ok").length;

  // Always 200, even when delivered is 0. The app shows "no active device on
  // her phone", which is a different and more useful message than a failure.
  return c.json({ send_id: sendId, delivered });
});
```

- [ ] **Step 4: Wire the route into `server/src/index.ts`**

```typescript
import { Hono } from "hono";
import type { App } from "./env";
import { enroll } from "./routes/enroll";
import { devices } from "./routes/devices";
import { send } from "./routes/send";
import { fail } from "./http";

const app = new Hono<App>();

/**
 * Exactly two endpoints are reachable without a device bearer token:
 * `/health`, which takes no credential at all, and `/v1/enroll`, which takes
 * an enrolment code instead. Every other route goes through requireDevice.
 */
app.get("/health", (c) => c.json({ ok: true }));

app.route("/v1", enroll);
app.route("/v1", devices);
app.route("/v1", send);

// Without these two, Hono's built-in handlers answer with text/plain, which
// breaks the spec's "all responses are JSON" contract on exactly the two paths a
// client most needs a clean failure: a mistyped route, and an unexpected throw.
app.notFound((c) => fail(c, 404, "not_found", "No such endpoint."));

app.onError((err, c) => {
  console.error(err);
  return fail(c, 500, "internal_error", "Something went wrong.");
});

export default app;
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd server && npm test -- send`
Expected: all ten tests PASS

- [ ] **Step 6: Run the whole suite**

Run: `cd server && npm test`
Expected: every test from Tasks 1–9 PASSES

- [ ] **Step 7: Commit**

```bash
git add server/src/routes/send.ts server/src/index.ts server/test/send.test.ts
git commit -m "feat(server): add send endpoint with server-derived recipient"
```

---

## Task 10: Deploy and verify on a real phone

This is the milestone gate. Everything before it was tested against a simulated
FCM; this task proves the real thing works.

**Files:**
- Create: `server/README.md`

**Interfaces:**
- Consumes: everything
- Produces: a deployed Worker; a confirmed push on a physical Android device

- [ ] **Step 1: Set the three secrets**

```bash
cd server
wrangler secret put ENROLL_CODE_1     # paste the first code from Task 0
wrangler secret put ENROLL_CODE_2     # paste the second code from Task 0
wrangler secret put FCM_SERVICE_ACCOUNT   # paste the whole service account JSON, one line
```

For the JSON, flatten it first so it pastes as a single line:

```bash
cat ~/path/to/service-account.json | tr -d '\n' | wrangler secret put FCM_SERVICE_ACCOUNT
```

- [ ] **Step 2: Confirm `PERSON_1_NAME`, `PERSON_2_NAME` and `FIREBASE_PROJECT_ID`**

Open `server/wrangler.toml` and check the `[vars]` block holds real values. `FIREBASE_PROJECT_ID` must match the `project_id` field inside the service account JSON exactly.

- [ ] **Step 3: Deploy**

```bash
cd server && npm run deploy
```

Note the deployed URL from the output, e.g. `https://love-button.<subdomain>.workers.dev`.

- [ ] **Step 4: Verify `/health`**

```bash
curl https://love-button.<subdomain>.workers.dev/health
```

Expected: `{"ok":true}`

- [ ] **Step 5: Get a real FCM token from a phone**

There is no app yet, so borrow one. In the Firebase console, open Cloud Messaging and send a test message to confirm the project works. To obtain an actual device token before Plan 2 exists, install any minimal FCM sample app with your `google-services.json`, or skip to Plan 2 and return to Step 6 afterwards.

**If no device token is available yet, stop here.** Steps 6–8 are the milestone-1 gate and must be completed before Plan 2 is considered done. Record that in the commit message.

- [ ] **Step 6: Enrol the device**

```bash
curl -X POST https://love-button.<subdomain>.workers.dev/v1/enroll \
  -H "Content-Type: application/json" \
  -d '{"code":"<ENROLL_CODE_2>","fcm_token":"<real device token>","label":"her phone"}'
```

Expected: a JSON body with `auth_token`, `person: 2`, and `partner_name`.

- [ ] **Step 7: Enrol a second device as person 1**

Repeat Step 6 with `ENROLL_CODE_1` and the second phone's token. Keep the returned `auth_token`.

- [ ] **Step 8: Send — the milestone gate**

```bash
curl -X POST https://love-button.<subdomain>.workers.dev/v1/send \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <person 1's auth_token>" \
  -d '{"msg_id":1}'
```

Expected: `{"send_id":"...","delivered":1}` **and the other phone receives the data message.**

This is milestone 1 of the spec, complete.

- [ ] **Step 9: Verify the invariants against the live server**

```bash
# Naming a recipient must change nothing — still goes to the partner.
curl -X POST .../v1/send -H "Authorization: Bearer <person 1 token>" \
  -H "Content-Type: application/json" -d '{"msg_id":1,"to_person":1}'

# An unknown token must be refused.
curl -X POST .../v1/send -H "Authorization: Bearer deadbeef" \
  -H "Content-Type: application/json" -d '{"msg_id":1}'   # expect 401

# An out-of-range message must be refused.
curl -X POST .../v1/send -H "Authorization: Bearer <person 1 token>" \
  -H "Content-Type: application/json" -d '{"msg_id":99}'  # expect 400
```

- [ ] **Step 10: Write `server/README.md`**

```markdown
# Love Button — Worker

The server half of Love Button. Holds the only copy of the Google service
account key and is the only place that decides who may send what to whom.

## Setup

    npm install
    npm run migrate:remote

Secrets (never in this repo):

    wrangler secret put ENROLL_CODE_1
    wrangler secret put ENROLL_CODE_2
    wrangler secret put FCM_SERVICE_ACCOUNT

## Commands

| Command | Does |
|---|---|
| `npm test` | Run the suite against a local Workers runtime |
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
```

- [ ] **Step 11: Confirm no secrets were committed**

```bash
git log -p | grep -i "BEGIN PRIVATE KEY" && echo "LEAK — stop and fix" || echo "clean"
git status --porcelain
```

Expected: `clean`, and no untracked `.dev.vars` or service account file.

- [ ] **Step 12: Commit**

```bash
git add server/README.md
git commit -m "docs(server): add README and record milestone 1 verification"
```

---

## Self-Review

**Spec coverage (§4, §5, §9):**

| Spec requirement | Task |
|---|---|
| Enrolment codes as Worker secrets, constant-time compare | 5 |
| 256-bit token, only SHA-256 stored | 3, 5 |
| Enrolment rate limit, 5/hour/IP | 4, 5 |
| Invariant 1 — sender is the authenticated device | 6, 9 |
| Invariant 2 — recipient is `3 - from_person` | 9 |
| Invariant 3 — recipient-only receipts | **Plan 4** (out of scope, flagged) |
| `CHECK (person IN (1,2))` | 2 |
| `msg_id` server-side allowlist | 4, 9 |
| `MAX_SENDS_PER_HOUR = 500` | 4, 9 |
| Data-only FCM payloads | 8 |
| KV-cached OAuth token, 55 min | 7 |
| RS256 JWT signed with WebCrypto | 7 |
| `UNREGISTERED` → delete device row | 8, 9 |
| `delivered: 0` still returns 200 | 9 |
| Errors as `{error, message}` | 4 |
| No CORS | Never added; `readJson` rejects non-JSON content types |
| Retention cron | **Plan 5** (milestone 8, out of scope) |
| `.gitignore` for secrets | 1 |

Two spec items are intentionally absent and named as such: `/v1/receipts` and the
retention cron. Both belong to later plans and neither blocks the milestone-1 gate.

**Placeholder scan:** No "TBD", "TODO", or "handle errors appropriately". Every code
step carries complete code. The two `PASTE_..._HERE` values in `wrangler.toml` are
real values the engineer generates in Task 0 Step 4, with the command that produces
them.

**Type consistency:** `sha256Hex`, `randomToken`, `secureEquals` (Task 3) are used
under those exact names in Tasks 5, 6 and 9. `DeviceRow` (Task 1) is what
`requireDevice` (Task 6) sets and what `send` (Task 9) reads. `getAccessToken` and
`ACCESS_TOKEN_CACHE_KEY` (Task 7) are consumed under those names in Tasks 8 and 9.
`PushResult`, `PushPriority` and `sendPush`'s five-parameter signature (Task 8) match
the call in Task 9. `fail(c, status, code, message)` keeps its argument order
throughout.

**Known risk:** the exact API surface of `@cloudflare/vitest-pool-workers` (`SELF`,
`fetchMock`, `applyD1Migrations`, `readD1Migrations`) may shift between versions. If
Task 1 Step 13 fails on an import, check the installed version's docs before changing
the plan's structure — the test *strategy* is sound even if a helper was renamed.
