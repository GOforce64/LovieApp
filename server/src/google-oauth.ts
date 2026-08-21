import type { Env } from "./env";
import { nowSeconds } from "./http";

export const ACCESS_TOKEN_CACHE_KEY = "google_access_token";

/** 55 minutes. Google's tokens last an hour; this leaves five minutes of margin. */
const ACCESS_TOKEN_TTL_SECONDS = 3300;

const TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

/** The two fields we need from the service account JSON. */
export interface ServiceAccount {
  client_email: string;
  private_key: string;
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlEncodeJson(value: unknown): string {
  return base64UrlEncode(new TextEncoder().encode(JSON.stringify(value)));
}

/**
 * Converts a PEM private key into the raw bytes WebCrypto expects.
 *
 * The `private_key` field in a service account JSON is PEM: a base64 body
 * wrapped in header and footer lines. `importKey` wants the decoded bytes.
 */
function pemToArrayBuffer(pem: string): ArrayBuffer {
  const body = pem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "");

  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);

  return bytes.buffer;
}

/**
 * Builds the JWT that proves we hold the service account's private key.
 *
 * Google calls this a "self-signed JWT bearer token": we sign an assertion
 * about who we are, hand it to Google, and get back a short-lived access
 * token. It is how a server authenticates when no human is present to log in.
 */
export async function buildSignedJwt(
  serviceAccount: ServiceAccount,
  nowSec: number,
): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: serviceAccount.client_email,
    scope: FCM_SCOPE,
    aud: TOKEN_ENDPOINT,
    iat: nowSec,
    exp: nowSec + 3600,
  };

  const signingInput = `${base64UrlEncodeJson(header)}.${base64UrlEncodeJson(claims)}`;

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(serviceAccount.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput),
  );

  return `${signingInput}.${base64UrlEncode(new Uint8Array(signature))}`;
}

function parseServiceAccount(raw: string): ServiceAccount {
  let parsed: Partial<ServiceAccount>;

  try {
    parsed = JSON.parse(raw) as Partial<ServiceAccount>;
  } catch {
    throw new Error("FCM service account is not valid JSON.");
  }

  if (!parsed.client_email || !parsed.private_key) {
    throw new Error(
      "FCM service account is missing client_email or private_key. " +
        "Set it with: wrangler secret put FCM_SERVICE_ACCOUNT",
    );
  }

  return { client_email: parsed.client_email, private_key: parsed.private_key };
}

async function mintAccessToken(env: Env): Promise<string> {
  const serviceAccount = parseServiceAccount(env.FCM_SERVICE_ACCOUNT);
  const assertion = await buildSignedJwt(serviceAccount, nowSeconds());

  const res = await fetch(TOKEN_ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });

  if (!res.ok) {
    throw new Error(`Google token exchange failed: ${res.status} ${await res.text()}`);
  }

  const body = (await res.json()) as { access_token?: string };
  if (!body.access_token) {
    throw new Error("Google token exchange returned no access_token.");
  }

  return body.access_token;
}

/**
 * Returns a usable Google access token, minting one only when the cache is
 * cold.
 *
 * The cache is not an optimisation. The Workers free tier allows 10ms of CPU
 * per request, and the RSA signature above sits close to that ceiling — doing
 * it on every send would push requests over the limit.
 */
export async function getAccessToken(env: Env): Promise<string> {
  const cached = await env.TOKEN_CACHE.get(ACCESS_TOKEN_CACHE_KEY);
  if (cached) return cached;

  const token = await mintAccessToken(env);

  await env.TOKEN_CACHE.put(ACCESS_TOKEN_CACHE_KEY, token, {
    expirationTtl: ACCESS_TOKEN_TTL_SECONDS,
  });

  return token;
}
