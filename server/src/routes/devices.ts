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
