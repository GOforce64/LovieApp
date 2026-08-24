import { Hono } from "hono";
import type { App } from "./env";
import { fail } from "./http";
import { enroll } from "./routes/enroll";
import { devices } from "./routes/devices";
import { send } from "./routes/send";
import { receipts } from "./routes/receipts";

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

export default app;
