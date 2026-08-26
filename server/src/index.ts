import { Hono } from "hono";
import type { App, Env } from "./env";
import { fail } from "./http";
import { enroll } from "./routes/enroll";
import { devices } from "./routes/devices";
import { send } from "./routes/send";
import { receipts } from "./routes/receipts";
import { purgeOldSends, RETENTION_DAYS } from "./retention";

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
app.route("/v1", receipts);

// Without these two, Hono's built-in handlers answer with text/plain, which
// breaks the spec's "all responses are JSON" contract on exactly the two paths a
// client most needs a clean failure: a mistyped route, and an unexpected throw.
app.notFound((c) => fail(c, 404, "not_found", "No such endpoint."));

app.onError((err, c) => {
  console.error(err);
  return fail(c, 500, "internal_error", "Something went wrong.");
});

/**
 * Both entry points the Worker has: HTTP, and the daily cron.
 *
 * This is an object rather than the bare Hono app because a Worker can only
 * expose a `scheduled` handler from a default-exported handler object. The
 * `fetch` line preserves exactly what `export default app` did before.
 */
export default {
  fetch: app.fetch,

  /**
   * Awaited rather than handed to `ctx.waitUntil`, so a failed purge surfaces
   * as a failed cron invocation in the dashboard instead of disappearing. The
   * next night's run retries it by simply existing — there is nothing to catch
   * up, since the query is defined by a cutoff rather than by a cursor.
   */
  async scheduled(_controller, env: Env) {
    const deleted = await purgeOldSends(env);
    console.log(`retention: deleted ${deleted} sends past ${RETENTION_DAYS} days`);
  },
} satisfies ExportedHandler<Env>;
