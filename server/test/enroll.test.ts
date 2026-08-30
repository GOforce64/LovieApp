import { SELF, env } from "cloudflare:test";
import { describe, it, expect, beforeEach } from "vitest";

async function enroll(body: unknown, ip = "203.0.113.1") {
  return SELF.fetch("https://love-button.test/v1/enroll", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "CF-Connecting-IP": ip,
    },
    body: JSON.stringify(body),
  });
}

describe("POST /v1/enroll", () => {
  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM devices").run();
  });

  it("issues a token for a valid code and records the device", async () => {
    const res = await enroll({
      code: "test-code-one",
      fcm_token: "fcm-abc",
      label: "test phone",
    });

    expect(res.status).toBe(200);
    const body = await res.json<{
      device_id: string;
      auth_token: string;
      person: number;
      partner_name: string;
    }>();

    expect(body.person).toBe(1);
    expect(body.partner_name).toBe("Her");
    expect(body.auth_token).toMatch(/^[0-9a-f]{64}$/);

    const row = await env.DB.prepare(
      "SELECT person, fcm_token, label FROM devices WHERE id = ?",
    )
      .bind(body.device_id)
      .first<{ person: number; fcm_token: string; label: string }>();

    expect(row?.person).toBe(1);
    expect(row?.fcm_token).toBe("fcm-abc");
  });

  it("maps the second code to person 2", async () => {
    const res = await enroll({ code: "test-code-two", fcm_token: "fcm-xyz" });

    const body = await res.json<{ person: number; partner_name: string }>();
    expect(body.person).toBe(2);
    expect(body.partner_name).toBe("Giorgos");
  });

  it("never stores the raw token", async () => {
    const res = await enroll({ code: "test-code-one", fcm_token: "fcm-1" });
    const body = await res.json<{ auth_token: string; device_id: string }>();

    const row = await env.DB.prepare(
      "SELECT auth_hash FROM devices WHERE id = ?",
    )
      .bind(body.device_id)
      .first<{ auth_hash: string }>();

    expect(row?.auth_hash).not.toBe(body.auth_token);
    expect(row?.auth_hash).toMatch(/^[0-9a-f]{64}$/);
  });

  it("enrols a send-only device when no FCM token is given", async () => {
    // The overnight gate needs to send AS each person without touching either
    // handset. A release APK is not debuggable, so its bearer token can no
    // longer be read off the phone — this is where that token comes from now.
    const res = await enroll({
      code: "test-code-one",
      label: "overnight-check · laptop",
    });

    expect(res.status).toBe(200);
    const body = await res.json<{ auth_token: string; person: number }>();
    expect(body.person).toBe(1);
    expect(body.auth_token).toHaveLength(64);

    const row = await env.DB.prepare(
      "SELECT fcm_token, label FROM devices WHERE person = 1",
    ).first<{ fcm_token: string | null; label: string }>();

    // NULL, not a placeholder string: /v1/send fans out to
    // `WHERE fcm_token IS NOT NULL`, so this row is skipped by the query that
    // already exists rather than by a new special case.
    expect(row?.fcm_token).toBeNull();
    expect(row?.label).toBe("overnight-check · laptop");
  });

  it("an explicit null FCM token is send-only too", async () => {
    const res = await enroll({ code: "test-code-one", fcm_token: null });
    expect(res.status).toBe(200);

    const row = await env.DB.prepare("SELECT fcm_token FROM devices").first<{
      fcm_token: string | null;
    }>();
    expect(row?.fcm_token).toBeNull();
  });

  it("rejects an FCM token that is neither a string nor absent", async () => {
    const res = await enroll({ code: "test-code-one", fcm_token: 42 });
    expect(res.status).toBe(400);
  });

  it("send-only rows never dedupe each other away", async () => {
    // The dedupe deletes rows sharing an FCM token. Two send-only rows both
    // hold NULL, and `= NULL` is never true in SQL — so this is already safe,
    // but it is the kind of safety that must not regress silently.
    await enroll({ code: "test-code-one", label: "laptop" });
    await enroll({ code: "test-code-two", label: "laptop" });

    const rows = await env.DB.prepare("SELECT person FROM devices ORDER BY person").all<{
      person: number;
    }>();
    expect(rows.results.map((r) => r.person)).toEqual([1, 2]);
  });

  it("rejects a wrong code with 403", async () => {
    const res = await enroll({ code: "not-the-code", fcm_token: "fcm-1" });

    expect(res.status).toBe(403);
    expect(await res.json()).toMatchObject({ error: "invalid_code" });
  });

  it("rejects a missing code with 400", async () => {
    const res = await enroll({ fcm_token: "fcm-1" });

    expect(res.status).toBe(400);
  });

  it("rejects a non-JSON content type with 400", async () => {
    const res = await SELF.fetch("https://love-button.test/v1/enroll", {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: "code=test-code-one",
    });

    expect(res.status).toBe(400);
  });

  it("rate limits after five attempts from one IP", async () => {
    const ip = "203.0.113.99";

    for (let i = 0; i < 5; i++) {
      await enroll({ code: "wrong", fcm_token: "fcm-1" }, ip);
    }

    const res = await enroll({ code: "test-code-one", fcm_token: "fcm-1" }, ip);

    expect(res.status).toBe(429);
    expect(res.headers.get("Retry-After")).toBe("3600");
  });

  it("replaces an existing row that already claimed the same FCM token", async () => {
    const first = await enroll({ code: "test-code-one", fcm_token: "same-fcm" });
    const firstId = (await first.json<{ device_id: string }>()).device_id;

    const second = await enroll({ code: "test-code-one", fcm_token: "same-fcm" });
    const secondId = (await second.json<{ device_id: string }>()).device_id;

    expect(secondId).not.toBe(firstId);

    const rows = await env.DB.prepare(
      "SELECT id FROM devices WHERE fcm_token = ?",
    )
      .bind("same-fcm")
      .all<{ id: string }>();

    expect(rows.results.map((r) => r.id)).toEqual([secondId]);
  });
});
