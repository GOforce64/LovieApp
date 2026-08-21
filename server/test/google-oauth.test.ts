import { env } from "cloudflare:test";
import { describe, it, expect, beforeEach } from "vitest";
import {
  getAccessToken,
  buildSignedJwt,
  ACCESS_TOKEN_CACHE_KEY,
} from "../src/google-oauth";

/** Generates a throwaway RSA key and returns it in the PEM form Google uses. */
async function generateTestPrivateKeyPem(): Promise<string> {
  // generateKey is typed CryptoKey | CryptoKeyPair because its return shape
  // depends on the algorithm; RSASSA-PKCS1-v1_5 always yields a pair.
  const pair = (await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;

  // exportKey is typed ArrayBuffer | JsonWebKey; the "pkcs8" format is the
  // ArrayBuffer branch.
  const pkcs8 = (await crypto.subtle.exportKey(
    "pkcs8",
    pair.privateKey,
  )) as ArrayBuffer;
  const b64 = btoa(String.fromCharCode(...new Uint8Array(pkcs8)));
  const lines = b64.match(/.{1,64}/g)!.join("\n");

  return `-----BEGIN PRIVATE KEY-----\n${lines}\n-----END PRIVATE KEY-----\n`;
}

describe("getAccessToken", () => {
  beforeEach(async () => {
    await env.TOKEN_CACHE.delete(ACCESS_TOKEN_CACHE_KEY);
  });

  it("returns the cached token without minting a new one", async () => {
    // If this tried to mint, it would fail — FCM_SERVICE_ACCOUNT is "{}" in
    // tests. Returning the cached value proves the cache is consulted first.
    await env.TOKEN_CACHE.put(ACCESS_TOKEN_CACHE_KEY, "cached-token-value");

    expect(await getAccessToken(env)).toBe("cached-token-value");
  });

  it("throws a clear error when the service account is not configured", async () => {
    await expect(getAccessToken(env)).rejects.toThrow(/service account/i);
  });
});

describe("buildSignedJwt", () => {
  it("produces three base64url segments with the expected claims", async () => {
    const jwt = await buildSignedJwt(
      {
        client_email: "robot@test-project.iam.gserviceaccount.com",
        private_key: await generateTestPrivateKeyPem(),
      },
      1_700_000_000,
    );

    const parts = jwt.split(".");
    expect(parts).toHaveLength(3);

    const decode = (segment: string) =>
      JSON.parse(atob(segment.replace(/-/g, "+").replace(/_/g, "/")));

    expect(decode(parts[0])).toEqual({ alg: "RS256", typ: "JWT" });

    const claims = decode(parts[1]);
    expect(claims.iss).toBe("robot@test-project.iam.gserviceaccount.com");
    expect(claims.scope).toBe("https://www.googleapis.com/auth/firebase.messaging");
    expect(claims.aud).toBe("https://oauth2.googleapis.com/token");
    expect(claims.iat).toBe(1_700_000_000);
    expect(claims.exp).toBe(1_700_000_000 + 3600);

    // A signature over a 2048-bit key is 256 bytes; base64url of that is 342 chars.
    expect(parts[2].length).toBe(342);
  });
});
