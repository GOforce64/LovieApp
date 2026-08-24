import { Hono } from "hono";
import type { App } from "../env";
import { requireDevice } from "../auth";
import { fail, nowSeconds, readJson } from "../http";
import { getAccessToken } from "../google-oauth";
import { sendPush } from "../fcm";

interface ReceiptBody {
  send_id?: unknown;
  state?: unknown;
}

interface SendRow {
  id: string;
  from_person: number;
  to_person: number;
  msg_id: number;
  delivered_at: number | null;
  seen_at: number | null;
}

export const receipts = new Hono<App>();

receipts.use("/receipts", requireDevice);

receipts.post("/receipts", async (c) => {
  const device = c.get("device");
  const body = await readJson<ReceiptBody>(c);

  if (
    !body ||
    typeof body.send_id !== "string" ||
    (body.state !== "delivered" && body.state !== "seen")
  ) {
    return fail(c, 400, "bad_request", "send_id and state (delivered|seen) are required.");
  }

  const row = await c.env.DB.prepare(
    `SELECT id, from_person, to_person, msg_id, delivered_at, seen_at
     FROM sends WHERE id = ?`,
  )
    .bind(body.send_id)
    .first<SendRow>();

  if (!row) {
    return fail(c, 404, "unknown_send", "No such send.");
  }

  /**
   * Invariant 3: only the recipient may acknowledge.
   *
   * The sender holds the send_id by definition — he generated it — so without
   * this check he could forge a "seen" for his own message and the widget would
   * light up for something she never opened.
   */
  if (row.to_person !== device.person) {
    return fail(c, 403, "not_recipient", "Only the recipient may acknowledge a send.");
  }

  const now = nowSeconds();

  /**
   * Monotonic by construction. COALESCE keeps whichever timestamp was written
   * first, so a replayed or out-of-order receipt cannot move state backwards,
   * and `seen` backfills `delivered_at` when she opened the notification before
   * the delivered receipt landed.
   */
  if (body.state === "delivered") {
    await c.env.DB.prepare(
      "UPDATE sends SET delivered_at = COALESCE(delivered_at, ?) WHERE id = ?",
    )
      .bind(now, row.id)
      .run();
  } else {
    await c.env.DB.prepare(
      `UPDATE sends
       SET seen_at = COALESCE(seen_at, ?), delivered_at = COALESCE(delivered_at, ?)
       WHERE id = ?`,
    )
      .bind(now, now, row.id)
      .run();
  }

  const targets = await c.env.DB.prepare(
    "SELECT id, fcm_token FROM devices WHERE person = ? AND fcm_token IS NOT NULL",
  )
    .bind(row.from_person)
    .all<{ id: string; fcm_token: string }>();

  if (targets.results.length > 0) {
    const accessToken = await getAccessToken(c.env);
    const data = {
      type: "receipt",
      send_id: row.id,
      state: body.state,
      at: String(now),
    };

    const results = await Promise.all(
      targets.results.map(async (target) => ({
        target,
        // NORMAL, not HIGH: a receipt is not urgent, and normal priority costs
        // less against the free tier (spec §5.4).
        result: await sendPush(c.env, accessToken, target.fcm_token, data, "NORMAL"),
      })),
    );

    const dead = results.filter((r) => r.result === "unregistered");
    if (dead.length > 0) {
      await c.env.DB.batch(
        dead.map((r) => c.env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(r.target.id)),
      );
    }
  }

  // 200 regardless of the push outcome. The receipt is recorded, and there is
  // nothing the client could usefully do about a failed push.
  return c.json({ ok: true });
});
