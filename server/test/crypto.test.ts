import { describe, it, expect } from "vitest";
import { randomToken, sha256Hex, secureEquals } from "../src/crypto";

describe("randomToken", () => {
  it("returns 64 hex characters", () => {
    expect(randomToken()).toMatch(/^[0-9a-f]{64}$/);
  });

  it("does not repeat", () => {
    const tokens = new Set(Array.from({ length: 100 }, () => randomToken()));
    expect(tokens.size).toBe(100);
  });
});

describe("sha256Hex", () => {
  it("matches a known SHA-256 value", async () => {
    // The canonical SHA-256 of "abc".
    expect(await sha256Hex("abc")).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
  });

  it("is deterministic", async () => {
    expect(await sha256Hex("hello")).toBe(await sha256Hex("hello"));
  });

  it("differs for different input", async () => {
    expect(await sha256Hex("hello")).not.toBe(await sha256Hex("hellp"));
  });
});

describe("secureEquals", () => {
  it("is true for identical strings", async () => {
    expect(await secureEquals("swordfish", "swordfish")).toBe(true);
  });

  it("is false for different strings of equal length", async () => {
    expect(await secureEquals("swordfish", "swordfisi")).toBe(false);
  });

  it("is false for different strings of different length", async () => {
    expect(await secureEquals("short", "much longer string")).toBe(false);
  });

  it("is false for the empty string against a secret", async () => {
    expect(await secureEquals("", "secret")).toBe(false);
  });
});
