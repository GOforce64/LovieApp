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
