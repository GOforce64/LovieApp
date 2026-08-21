import { SELF, env, fetchMock } from "cloudflare:test";
import { describe, it, expect, beforeAll, beforeEach, afterEach } from "vitest";
import { sha256Hex } from "../src/crypto";
import { ACCESS_TOKEN_CACHE_KEY } from "../src/google-oauth";
import { recipientOf } from "../src/routes/send";
import { MAX_SENDS_PER_HOUR } from "../src/limits";
import { nowSeconds } from "../src/http";

const TOKEN = "c".repeat(64);
const FCM_ORIGIN = "https://fcm.googleapis.com";
const FCM_PATH = "/v1/projects/test-project/messages:send";

beforeAll(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});

afterEach(() => fetchMock.assertNoPendingInterceptors());

function interceptFcm(status: number, body: object) {
  fetchMock
    .get(FCM_ORIGIN)
    .intercept({ path: FCM_PATH, method: "POST" })
    .reply(status, body);
}

async function seed(id: string, person: number, fcm: string | null, token?: string) {
  await env.DB.prepare(
    `INSERT INTO devices (id, person, auth_hash, fcm_token, label, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(id, person, await sha256Hex(token ?? `${id}-token`), fcm, id, 1000, 1000)
    .run();
}

function send(body: unknown, token = TOKEN) {
  return SELF.fetch("https://love-button.test/v1/send", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  });
}

describe("recipientOf", () => {
  it("maps each person to the other one", () => {
    expect(recipientOf(1)).toBe(2);
    expect(recipientOf(2)).toBe(1);
  });
});

describe("POST /v1/send", () => {
  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM devices").run();
    await env.DB.prepare("DELETE FROM sends").run();
    await env.TOKEN_CACHE.put(ACCESS_TOKEN_CACHE_KEY, "test-access-token");

    await seed("sender", 1, "fcm-sender", TOKEN);
    await seed("receiver", 2, "fcm-receiver");
  });

  it("records the send and pushes to the partner", async () => {
    interceptFcm(200, { name: "projects/test-project/messages/1" });

    const res = await send({ msg_id: 3 });

    expect(res.status).toBe(200);
    const body = await res.json<{ send_id: string; delivered: number }>();
    expect(body.delivered).toBe(1);

    const row = await env.DB.prepare(
      "SELECT from_person, to_person, msg_id FROM sends WHERE id = ?",
    )
      .bind(body.send_id)
      .first<{ from_person: number; to_person: number; msg_id: number }>();

    // Invariant 2: the recipient was derived server-side, never supplied.
    expect(row?.from_person).toBe(1);
    expect(row?.to_person).toBe(2);
    expect(row?.msg_id).toBe(3);
  });

  it("ignores any recipient the client tries to name", async () => {
    interceptFcm(200, { name: "ok" });

    const res = await send({ msg_id: 1, to_person: 1, from_person: 2 });
    const body = await res.json<{ send_id: string }>();

    const row = await env.DB.prepare(
      "SELECT from_person, to_person FROM sends WHERE id = ?",
    )
      .bind(body.send_id)
      .first<{ from_person: number; to_person: number }>();

    expect(row?.from_person).toBe(1);
    expect(row?.to_person).toBe(2);
  });

  it("rejects a msg_id outside the allowlist with 400", async () => {
    const res = await send({ msg_id: 99 });

    expect(res.status).toBe(400);
    expect(await res.json()).toMatchObject({ error: "bad_request" });
  });

  it("rejects a non-integer msg_id with 400", async () => {
    const res = await send({ msg_id: "3" });

    expect(res.status).toBe(400);
  });

  it("returns 200 with delivered 0 when the partner has no device", async () => {
    await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind("receiver").run();

    const res = await send({ msg_id: 1 });

    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ delivered: 0 });
  });

  it("deletes a device row when FCM reports the token is dead", async () => {
    interceptFcm(404, {
      error: {
        status: "NOT_FOUND",
        details: [{ errorCode: "UNREGISTERED" }],
      },
    });

    const res = await send({ msg_id: 1 });

    expect(await res.json()).toMatchObject({ delivered: 0 });

    const gone = await env.DB.prepare("SELECT id FROM devices WHERE id = ?")
      .bind("receiver")
      .first();
    expect(gone).toBeNull();
  });

  it("still records the send when the push fails transiently", async () => {
    interceptFcm(503, { error: { status: "UNAVAILABLE" } });

    const res = await send({ msg_id: 2 });
    const body = await res.json<{ send_id: string; delivered: number }>();

    expect(body.delivered).toBe(0);

    const row = await env.DB.prepare("SELECT id FROM sends WHERE id = ?")
      .bind(body.send_id)
      .first();
    expect(row).not.toBeNull();
  });

  it("returns 429 once the hourly ceiling is reached", async () => {
    const now = nowSeconds();
    const statements = Array.from({ length: MAX_SENDS_PER_HOUR }, (_, i) =>
      env.DB.prepare(
        `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at)
         VALUES (?, ?, ?, ?, ?)`,
      ).bind(`fill-${i}`, 1, 2, 1, now - 5),
    );
    await env.DB.batch(statements);

    const res = await send({ msg_id: 1 });

    expect(res.status).toBe(429);
    expect(res.headers.get("Retry-After")).toBe("3600");
  });

  it("requires authentication", async () => {
    const res = await SELF.fetch("https://love-button.test/v1/send", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ msg_id: 1 }),
    });

    expect(res.status).toBe(401);
  });
});
