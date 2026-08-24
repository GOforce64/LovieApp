import { SELF, env, fetchMock } from "cloudflare:test";
import { describe, it, expect, beforeAll, beforeEach, afterEach } from "vitest";
import { sha256Hex } from "../src/crypto";
import { ACCESS_TOKEN_CACHE_KEY } from "../src/google-oauth";

const HER_TOKEN = "d".repeat(64);
const HIS_TOKEN = "e".repeat(64);
const SEND_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const FCM_ORIGIN = "https://fcm.googleapis.com";
const FCM_PATH = "/v1/projects/test-project/messages:send";

beforeAll(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});

afterEach(() => fetchMock.assertNoPendingInterceptors());

function interceptFcm(status: number, body: object) {
  fetchMock.get(FCM_ORIGIN).intercept({ path: FCM_PATH, method: "POST" }).reply(status, body);
}

async function seedDevice(id: string, person: number, token: string, fcm: string | null) {
  await env.DB.prepare(
    `INSERT INTO devices (id, person, auth_hash, fcm_token, label, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(id, person, await sha256Hex(token), fcm, id, 1000, 1000)
    .run();
}

async function seedSend(id = SEND_ID) {
  // person 1 sent to person 2, so only person 2 may acknowledge it
  await env.DB.prepare(
    `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at) VALUES (?, 1, 2, 1, 1000)`,
  )
    .bind(id)
    .run();
}

function receipt(body: unknown, token = HER_TOKEN) {
  return SELF.fetch("https://love-button.test/v1/receipts", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  });
}

describe("POST /v1/receipts", () => {
  beforeEach(async () => {
    await env.DB.exec("DELETE FROM sends");
    await env.DB.exec("DELETE FROM devices");
    await env.TOKEN_CACHE.delete(ACCESS_TOKEN_CACHE_KEY);
    await env.TOKEN_CACHE.put(ACCESS_TOKEN_CACHE_KEY, "cached-access-token", {
      expirationTtl: 600,
    });
    await seedDevice("her", 2, HER_TOKEN, null);
    await seedDevice("his", 1, HIS_TOKEN, "his-fcm-token");
    await seedSend();
  });

  it("records delivered and pushes a receipt to the sender", async () => {
    interceptFcm(200, { name: "projects/test-project/messages/1" });

    const res = await receipt({ send_id: SEND_ID, state: "delivered" });
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ ok: true });

    const row = await env.DB.prepare("SELECT delivered_at, seen_at FROM sends WHERE id = ?")
      .bind(SEND_ID)
      .first<{ delivered_at: number | null; seen_at: number | null }>();
    expect(row?.delivered_at).not.toBeNull();
    expect(row?.seen_at).toBeNull();
  });

  it("refuses a receipt from anyone but the recipient", async () => {
    // Invariant 3. The sender holds the send_id by definition, so without this
    // check he could forge a "seen" for his own message.
    const res = await receipt({ send_id: SEND_ID, state: "seen" }, HIS_TOKEN);
    expect(res.status).toBe(403);
    expect(await res.json()).toMatchObject({ error: "not_recipient" });

    const row = await env.DB.prepare("SELECT seen_at FROM sends WHERE id = ?")
      .bind(SEND_ID)
      .first<{ seen_at: number | null }>();
    expect(row?.seen_at).toBeNull();
  });

  it("is idempotent: delivered twice keeps the first timestamp", async () => {
    interceptFcm(200, { name: "projects/test-project/messages/1" });
    await receipt({ send_id: SEND_ID, state: "delivered" });
    const first = await env.DB.prepare("SELECT delivered_at FROM sends WHERE id = ?")
      .bind(SEND_ID)
      .first<{ delivered_at: number }>();

    interceptFcm(200, { name: "projects/test-project/messages/2" });
    const res = await receipt({ send_id: SEND_ID, state: "delivered" });
    expect(res.status).toBe(200);

    const second = await env.DB.prepare("SELECT delivered_at FROM sends WHERE id = ?")
      .bind(SEND_ID)
      .first<{ delivered_at: number }>();
    expect(second?.delivered_at).toBe(first?.delivered_at);
  });

  it("seen backfills delivered_at when it was never set", async () => {
    // She can open the notification before the delivered receipt lands. Leaving
    // delivered_at null would record a message that was read but never delivered.
    interceptFcm(200, { name: "projects/test-project/messages/1" });
    const res = await receipt({ send_id: SEND_ID, state: "seen" });
    expect(res.status).toBe(200);

    const row = await env.DB.prepare("SELECT delivered_at, seen_at FROM sends WHERE id = ?")
      .bind(SEND_ID)
      .first<{ delivered_at: number | null; seen_at: number | null }>();
    expect(row?.delivered_at).not.toBeNull();
    expect(row?.seen_at).not.toBeNull();
  });

  it("never moves state backwards", async () => {
    interceptFcm(200, { name: "projects/test-project/messages/1" });
    await receipt({ send_id: SEND_ID, state: "seen" });
    const seenAt = await env.DB.prepare("SELECT seen_at FROM sends WHERE id = ?")
      .bind(SEND_ID)
      .first<{ seen_at: number }>();

    interceptFcm(200, { name: "projects/test-project/messages/2" });
    const res = await receipt({ send_id: SEND_ID, state: "delivered" });
    expect(res.status).toBe(200);

    const after = await env.DB.prepare("SELECT seen_at FROM sends WHERE id = ?")
      .bind(SEND_ID)
      .first<{ seen_at: number | null }>();
    expect(after?.seen_at).toBe(seenAt?.seen_at);
  });

  it("returns 200 even when the reverse push fails", async () => {
    // The receipt is recorded either way, and the client can do nothing useful
    // with a push failure.
    interceptFcm(500, { error: { status: "INTERNAL" } });
    const res = await receipt({ send_id: SEND_ID, state: "delivered" });
    expect(res.status).toBe(200);
  });

  it("404s an unknown send_id", async () => {
    const res = await receipt({ send_id: "00000000-0000-4000-8000-000000000000", state: "delivered" });
    expect(res.status).toBe(404);
    expect(await res.json()).toMatchObject({ error: "unknown_send" });
  });

  it("400s an unknown state", async () => {
    const res = await receipt({ send_id: SEND_ID, state: "read" });
    expect(res.status).toBe(400);
  });
});
