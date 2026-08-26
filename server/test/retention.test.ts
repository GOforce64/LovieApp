import { env } from "cloudflare:test";
import { describe, it, expect, beforeEach } from "vitest";
import { purgeOldSends, RETENTION_DAYS } from "../src/retention";

const DAY = 86_400;
const NOW = 1_800_000_000;

/** Seeds one send at a given age in days, and returns its id. */
async function seedSend(id: string, ageDays: number): Promise<string> {
  await env.DB.prepare(
    `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at, delivered_at, seen_at)
     VALUES (?, 1, 2, 1, ?, NULL, NULL)`,
  )
    .bind(id, NOW - ageDays * DAY)
    .run();
  return id;
}

/** Seeds one send at an absolute timestamp. */
async function seedSendAt(id: string, sentAt: number): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at, delivered_at, seen_at)
     VALUES (?, 1, 2, 1, ?, NULL, NULL)`,
  )
    .bind(id, sentAt)
    .run();
}

async function remainingIds(): Promise<string[]> {
  const { results } = await env.DB.prepare("SELECT id FROM sends ORDER BY id").all<{ id: string }>();
  return results.map((r) => r.id);
}

beforeEach(async () => {
  await env.DB.prepare("DELETE FROM sends").run();
  await env.DB.prepare("DELETE FROM devices").run();
});

describe("purgeOldSends", () => {
  it("deletes sends older than the retention window", async () => {
    await seedSend("old-30d", 30);
    await seedSend("old-8d", 8);

    await purgeOldSends(env, NOW);

    expect(await remainingIds()).toEqual([]);
  });

  it("keeps sends inside the retention window", async () => {
    await seedSend("fresh-0d", 0);
    await seedSend("recent-6d", 6);

    await purgeOldSends(env, NOW);

    expect(await remainingIds()).toEqual(["fresh-0d", "recent-6d"]);
  });

  it("keeps a send sitting exactly on the boundary", async () => {
    // Deliberately `<` and not `<=`: a row at exactly the cutoff survives and
    // is swept the following night. Arbitrary, but pinned so it stays a
    // decision rather than an accident.
    await seedSend("exactly-7d", RETENTION_DAYS);

    await purgeOldSends(env, NOW);

    expect(await remainingIds()).toEqual(["exactly-7d"]);
  });

  it("returns how many rows it deleted", async () => {
    await seedSend("old-a", 10);
    await seedSend("old-b", 20);
    await seedSend("keep", 1);

    expect(await purgeOldSends(env, NOW)).toBe(2);
  });

  it("returns zero on an empty table without throwing", async () => {
    expect(await purgeOldSends(env, NOW)).toBe(0);
  });

  it("leaves devices untouched", async () => {
    await env.DB.prepare(
      `INSERT INTO devices (id, person, auth_hash, fcm_token, label, created_at, updated_at)
       VALUES ('d1', 1, 'hash', 'tok', 'phone', 1000, 1000)`,
    ).run();
    await seedSend("old", 99);

    await purgeOldSends(env, NOW);

    const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices").first<{ n: number }>();
    expect(row?.n).toBe(1);
  });
});

describe("the scheduled handler", () => {
  it("purges old sends when the cron fires", async () => {
    // Ages measured from the REAL clock, not the NOW constant: the handler
    // takes no `now` argument, so rows dated relative to a fixed future
    // timestamp would never be old enough for it to delete.
    const realNow = Math.floor(Date.now() / 1000);
    await seedSendAt("ancient", realNow - 60 * DAY);
    await seedSendAt("recent", realNow - 2 * DAY);

    // Exercised through the Worker's own export, so this fails if the
    // scheduled handler is missing, unwired, or silently swallows its work —
    // which testing purgeOldSends directly would never catch.
    const worker = (await import("../src/index")).default;
    await worker.scheduled(
      { cron: "17 3 * * *", scheduledTime: Date.now(), noRetry() {} } as ScheduledController,
      env,
    );

    expect(await remainingIds()).toEqual(["recent"]);
  });
});
