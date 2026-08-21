import { env } from "cloudflare:test";
import { describe, it, expect } from "vitest";

describe("schema", () => {
  it("stores a device row", async () => {
    await env.DB.prepare(
      `INSERT INTO devices (id, person, auth_hash, fcm_token, label, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    )
      .bind("dev-1", 1, "hash-1", "fcm-1", "test phone", 1000, 1000)
      .run();

    const row = await env.DB.prepare(
      "SELECT person, label FROM devices WHERE id = ?",
    )
      .bind("dev-1")
      .first<{ person: number; label: string }>();

    expect(row?.person).toBe(1);
    expect(row?.label).toBe("test phone");
  });

  it("refuses a person other than 1 or 2", async () => {
    // This is the constraint that makes a third identity impossible.
    const insert = env.DB.prepare(
      `INSERT INTO devices (id, person, auth_hash, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind("dev-bad", 3, "hash-bad", 1000, 1000)
      .run();

    await expect(insert).rejects.toThrow();
  });

  it("refuses two devices sharing an auth hash", async () => {
    await env.DB.prepare(
      `INSERT INTO devices (id, person, auth_hash, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind("dev-2", 1, "shared-hash", 1000, 1000)
      .run();

    const duplicate = env.DB.prepare(
      `INSERT INTO devices (id, person, auth_hash, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind("dev-3", 2, "shared-hash", 1000, 1000)
      .run();

    await expect(duplicate).rejects.toThrow();
  });

  it("stores a send row with null receipt timestamps", async () => {
    await env.DB.prepare(
      `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind("send-1", 1, 2, 3, 2000)
      .run();

    const row = await env.DB.prepare(
      "SELECT to_person, msg_id, delivered_at, seen_at FROM sends WHERE id = ?",
    )
      .bind("send-1")
      .first<{
        to_person: number;
        msg_id: number;
        delivered_at: number | null;
        seen_at: number | null;
      }>();

    expect(row?.to_person).toBe(2);
    expect(row?.msg_id).toBe(3);
    expect(row?.delivered_at).toBeNull();
    expect(row?.seen_at).toBeNull();
  });
});
