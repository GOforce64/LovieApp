import { Hono } from "hono";
import type { App } from "./env";

const app = new Hono<App>();

/**
 * The only unauthenticated endpoint. Used to confirm a deploy worked
 * without needing a device token.
 */
app.get("/health", (c) => c.json({ ok: true }));

export default app;
