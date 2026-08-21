import { SELF, env } from "cloudflare:test";
import { describe, it, expect, beforeEach } from "vitest";
import { sha256Hex } from "../src/crypto";

const TOKEN = "a".repeat(64);

async function seedDevice(id = "dev-auth", person = 1, fcm = "fcm-seed", token = TOKEN) {
  await env.DB.prepare(
    `INSERT INTO devices (id, person, auth_hash, fcm_token, label, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(id, person, await sha256Hex(token), fcm, "seeded", 1000, 1000)
    .run();
}

function authed(path: string, init: RequestInit = {}, token = TOKEN) {
  return SELF.fetch(`https://love-button.test${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      ...(init.headers ?? {}),
    },
  });
}

describe("bearer authentication", () => {
  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM devices").run();
    await seedDevice();
  });

  it("rejects a request with no Authorization header", async () => {
    const res = await SELF.fetch("https://love-button.test/v1/devices", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fcm_token: "new" }),
    });

    expect(res.status).toBe(401);
    expect(await res.json()).toMatchObject({ error: "unauthorized" });
  });

  it("rejects a malformed Authorization header", async () => {
    const res = await SELF.fetch("https://love-button.test/v1/devices", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: TOKEN, // missing the "Bearer " prefix
      },
      body: JSON.stringify({ fcm_token: "new" }),
    });

    expect(res.status).toBe(401);
  });

  it("rejects an unknown token", async () => {
    const res = await authed(
      "/v1/devices",
      { method: "POST", body: JSON.stringify({ fcm_token: "new" }) },
      "b".repeat(64),
    );

    expect(res.status).toBe(401);
  });
});

describe("POST /v1/devices", () => {
  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM devices").run();
    await seedDevice();
  });

  it("updates the stored FCM token", async () => {
    const res = await authed("/v1/devices", {
      method: "POST",
      body: JSON.stringify({ fcm_token: "fcm-rotated" }),
    });

    expect(res.status).toBe(200);

    const row = await env.DB.prepare(
      "SELECT fcm_token FROM devices WHERE id = ?",
    )
      .bind("dev-auth")
      .first<{ fcm_token: string }>();

    expect(row?.fcm_token).toBe("fcm-rotated");
  });

  it("removes another device row holding the same FCM token", async () => {
    await seedDevice("dev-stale", 2, "fcm-rotated", "b".repeat(64));

    await authed("/v1/devices", {
      method: "POST",
      body: JSON.stringify({ fcm_token: "fcm-rotated" }),
    });

    const stale = await env.DB.prepare("SELECT id FROM devices WHERE id = ?")
      .bind("dev-stale")
      .first();

    expect(stale).toBeNull();
  });

  it("rejects a missing fcm_token with 400", async () => {
    const res = await authed("/v1/devices", {
      method: "POST",
      body: JSON.stringify({}),
    });

    expect(res.status).toBe(400);
  });
});

describe("DELETE /v1/devices", () => {
  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM devices").run();
    await seedDevice();
  });

  it("deletes only the calling device", async () => {
    await seedDevice("dev-other", 2, "fcm-other", "b".repeat(64));

    const res = await authed("/v1/devices", { method: "DELETE" });
    expect(res.status).toBe(200);

    const gone = await env.DB.prepare("SELECT id FROM devices WHERE id = ?")
      .bind("dev-auth")
      .first();
    const survivor = await env.DB.prepare("SELECT id FROM devices WHERE id = ?")
      .bind("dev-other")
      .first();

    expect(gone).toBeNull();
    expect(survivor).not.toBeNull();
  });

  it("makes the token unusable afterwards", async () => {
    await authed("/v1/devices", { method: "DELETE" });

    const res = await authed("/v1/devices", { method: "DELETE" });
    expect(res.status).toBe(401);
  });
});
