import { Hono } from "hono";
import type { App } from "./env";
import { enroll } from "./routes/enroll";
import { devices } from "./routes/devices";
import { send } from "./routes/send";

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

export default app;
