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
