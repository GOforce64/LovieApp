import { Hono } from "hono";
import type { App } from "./env";
import { enroll } from "./routes/enroll";

const app = new Hono<App>();

app.get("/health", (c) => c.json({ ok: true }));

app.route("/v1", enroll);

export default app;
