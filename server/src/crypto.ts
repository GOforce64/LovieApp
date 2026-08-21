/**
 * Small wrappers over WebCrypto. Workers have no Node `crypto` module —
 * everything here uses the browser-standard `crypto` global.
 */

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * A 256-bit random token, hex encoded. This is what a device presents as
 * its bearer credential. `getRandomValues` is cryptographically secure;
 * `Math.random` is not and must never be used for this.
 */
export function randomToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return toHex(bytes);
}

async function sha256Bytes(input: string): Promise<Uint8Array> {
  const data = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return new Uint8Array(digest);
}

/** SHA-256 of a string, hex encoded. Used to store tokens without storing them. */
export async function sha256Hex(input: string): Promise<string> {
  return toHex(await sha256Bytes(input));
}

/**
 * Compares two strings without leaking their contents through timing.
 *
 * A normal `===` on strings returns as soon as it finds a differing byte,
 * so an attacker who can measure response times can discover a secret one
 * character at a time. Hashing both sides first gives two values that are
 * always exactly 32 bytes, so the loop below always runs the same number
 * of iterations regardless of the inputs — this leaks neither content nor
 * length.
 */
export async function secureEquals(a: string, b: string): Promise<boolean> {
  const [ha, hb] = await Promise.all([sha256Bytes(a), sha256Bytes(b)]);

  let diff = 0;
  for (let i = 0; i < ha.length; i++) {
    diff |= ha[i] ^ hb[i];
  }
  return diff === 0;
}
