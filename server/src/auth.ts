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

  if (!device) {
    return fail(c, 401, "unauthorized", "Unknown or revoked token.");
  }

  c.set("device", device);
  await next();
});
