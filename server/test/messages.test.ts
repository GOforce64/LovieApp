import { env } from "cloudflare:test";
import { describe, it, expect } from "vitest";
import { isValidMsgId } from "../src/messages";
import { overSendCeiling, MAX_SENDS_PER_HOUR } from "../src/limits";
import { nowSeconds } from "../src/http";

describe("isValidMsgId", () => {
  it("accepts the four defined messages", () => {
    expect(isValidMsgId(1)).toBe(true);
    expect(isValidMsgId(2)).toBe(true);
    expect(isValidMsgId(3)).toBe(true);
    expect(isValidMsgId(4)).toBe(true);
  });

  it("rejects ids outside the allowlist", () => {
    expect(isValidMsgId(0)).toBe(false);
    expect(isValidMsgId(5)).toBe(false);
    expect(isValidMsgId(-1)).toBe(false);
  });

  it("rejects non-integers and non-numbers", () => {
    expect(isValidMsgId(1.5)).toBe(false);
    expect(isValidMsgId("1")).toBe(false);
    expect(isValidMsgId(null)).toBe(false);
    expect(isValidMsgId(undefined)).toBe(false);
    expect(isValidMsgId({})).toBe(false);
  });
});

describe("overSendCeiling", () => {
  it("is false when the person has sent nothing", async () => {
    expect(await overSendCeiling(env, 1)).toBe(false);
  });

  it("is true once the ceiling is reached within the hour", async () => {
    const now = nowSeconds();
    const statements = Array.from({ length: MAX_SENDS_PER_HOUR }, (_, i) =>
      env.DB.prepare(
        `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at)
         VALUES (?, ?, ?, ?, ?)`,
      ).bind(`ceiling-${i}`, 2, 1, 1, now - 10),
    );
    await env.DB.batch(statements);

    expect(await overSendCeiling(env, 2)).toBe(true);
  });

  it("ignores sends older than an hour", async () => {
    const now = nowSeconds();
    await env.DB.prepare(
      `INSERT INTO sends (id, from_person, to_person, msg_id, sent_at)
       VALUES (?, ?, ?, ?, ?)`,
    )
      .bind("stale-1", 1, 2, 1, now - 7200)
      .run();

    expect(await overSendCeiling(env, 1)).toBe(false);
  });
});
