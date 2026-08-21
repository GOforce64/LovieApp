import type { Env } from "./env";
import { nowSeconds } from "./http";

/**
 * A circuit breaker against a compromised device or a runaway loop — not a
 * usage limit. Normal human tapping will never approach it.
 */
export const MAX_SENDS_PER_HOUR = 500;

/** Enrolment is the only endpoint reachable without a token, so it gets its own limit. */
export const MAX_ENROLL_ATTEMPTS_PER_HOUR = 5;

/** Counts this person's sends in the last hour against the ceiling. */
export async function overSendCeiling(env: Env, person: number): Promise<boolean> {
  const since = nowSeconds() - 3600;

  const row = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM sends WHERE from_person = ? AND sent_at >= ?",
  )
    .bind(person, since)
    .first<{ n: number }>();

  return (row?.n ?? 0) >= MAX_SENDS_PER_HOUR;
}

/**
 * Counts enrolment attempts per IP in a rolling hour, stored in KV.
 *
 * KV is eventually consistent, so this count can lag slightly under
 * concurrent requests. That is acceptable: the codes are 48 hex characters
 * and unguessable on their own. This limit is defence in depth, not the
 * primary control.
 */
export async function tooManyEnrollAttempts(env: Env, ip: string): Promise<boolean> {
  const key = `enroll_attempts:${ip}`;
  const current = Number(await env.TOKEN_CACHE.get(key)) || 0;

  if (current >= MAX_ENROLL_ATTEMPTS_PER_HOUR) return true;

  await env.TOKEN_CACHE.put(key, String(current + 1), { expirationTtl: 3600 });
  return false;
}
