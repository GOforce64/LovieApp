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
