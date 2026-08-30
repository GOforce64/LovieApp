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
  if (!body || typeof body.code !== "string") {
    return fail(c, 400, "bad_request", "code is required.");
  }

  // A device may enrol WITHOUT an FCM token, which makes it send-only: it gets a
  // working bearer token but never receives a push, because /v1/send fans out to
  // `WHERE fcm_token IS NOT NULL` and this row simply is not in that set.
  //
  // That is what the overnight gate runs on. A release APK is not debuggable, so
  // a phone's own bearer token can no longer be read off it with `run-as`, and
  // the alternative — shipping a debuggable release so the check keeps working —
  // would let any adb session read the app's private data on a publicly
  // downloadable build.
  //
  // No new authority is granted: the enrolment code is still required, the
  // sender is still the authenticated device, and the recipient is still derived
  // as 3 - from_person. Such a row is invisible from the phones, so label it.
  const hasToken = typeof body.fcm_token === "string";
  if (body.fcm_token !== undefined && body.fcm_token !== null && !hasToken) {
    return fail(c, 400, "bad_request", "fcm_token must be a string when given.");
  }
  const fcmToken = hasToken ? (body.fcm_token as string) : null;

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
    // `= NULL` is never true in SQL, so one send-only row cannot dedupe another
    // away. That is the behaviour we want, and it needs no special case.
    c.env.DB.prepare("DELETE FROM devices WHERE fcm_token = ?").bind(fcmToken),
    c.env.DB.prepare(
      `INSERT INTO devices (id, person, auth_hash, fcm_token, label, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).bind(deviceId, person, authHash, fcmToken, label, now, now),
  ]);

  // The raw token is returned exactly once and is never recoverable again.
  return c.json({
    device_id: deviceId,
    auth_token: authToken,
    person,
    partner_name: partnerName(c.env, person),
  });
});
