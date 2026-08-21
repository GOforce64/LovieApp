import { SELF } from "cloudflare:test";
import { describe, it, expect } from "vitest";

describe("GET /health", () => {
  it("returns ok without any credentials", async () => {
    const res = await SELF.fetch("https://love-button.test/health");

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
  });

  it("returns 404 for an unknown path", async () => {
    const res = await SELF.fetch("https://love-button.test/nope");

    expect(res.status).toBe(404);
  });
});
