import { Hono } from "hono";
import type { App } from "../env";
import { requireDevice } from "../auth";
import { fail, nowSeconds, readJson } from "../http";
import { isValidMsgId } from "../messages";
import { overSendCeiling } from "../limits";
import { getAccessToken } from "../google-oauth";
import { sendPush } from "../fcm";

interface SendBody {
  msg_id?: unknown;
}

/**
 * Invariant 2, in one line.
 *
 * With exactly two people, the recipient is arithmetic: 3 - 1 = 2, 3 - 2 = 1.
 * There is no lookup to get wrong and no request field to tamper with. The
 * CHECK constraint on `devices.person` guarantees the input is 1 or 2.
 */
export function recipientOf(person: number): number {
  return 3 - person;
}

export const send = new Hono<App>();

send.use("/send", requireDevice);

send.post("/send", async (c) => {
  const device = c.get("device");

  if (await overSendCeiling(c.env, device.person)) {
    c.header("Retry-After", "3600");
    return fail(c, 429, "rate_limited", "Hourly send limit reached.");
  }

  const body = await readJson<SendBody>(c);
  if (!body || !isValidMsgId(body.msg_id)) {
    return fail(c, 400, "bad_request", "msg_id must be one of the defined messages.");
  }

  const toPerson = recipientOf(device.person);
  const sendId = crypto.randomUUID();
  const sentAt = nowSeconds();

  // Record the send before pushing. The row is what a receipt correlates
  // against later, and it must exist even if every push fails.
  await c.env.DB.prepare(
    `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at)
     VALUES (?, ?, ?, ?, ?)`,
  )
    .bind(sendId, device.person, toPerson, body.msg_id, sentAt)
    .run();

  const targets = await c.env.DB.prepare(
    "SELECT id, fcm_token FROM devices WHERE person = ? AND fcm_token IS NOT NULL",
  )
    .bind(toPerson)
    .all<{ id: string; fcm_token: string }>();

  const accessToken = await getAccessToken(c.env);

  const data = {
    type: "msg",
    send_id: sendId,
    msg_id: String(body.msg_id),
    from_name: device.person === 1 ? c.env.PERSON_1_NAME : c.env.PERSON_2_NAME,
    sent_at: String(sentAt),
  };

  const results = await Promise.all(
    targets.results.map(async (target) => ({
      target,
      // HIGH priority is what wakes a phone that is in Doze.
      result: await sendPush(c.env, accessToken, target.fcm_token, data, "HIGH"),
    })),
  );

  const dead = results.filter((r) => r.result === "unregistered");
  if (dead.length > 0) {
    await c.env.DB.batch(
      dead.map((r) =>
        c.env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(r.target.id),
      ),
    );
  }

  const delivered = results.filter((r) => r.result === "ok").length;

  // Always 200, even when delivered is 0. The app shows "no active device on
  // her phone", which is a different and more useful message than a failure.
  return c.json({ send_id: sendId, delivered });
});
