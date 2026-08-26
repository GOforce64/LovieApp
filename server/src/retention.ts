import type { Env } from "./env";
import { nowSeconds } from "./http";

/**
 * How long a `sends` row is kept.
 *
 * Spec §5.1: these rows exist for receipt correlation and the abuse ceiling,
 * **not as history**. Correlation needs them for twenty seconds and the ceiling
 * for one hour, so seven days is already generous — it is a margin for
 * debugging, not a retention policy anyone depends on.
 *
 * Without something deleting them the table grows without bound, and a database
 * that promises not to be a history of every message ends up being exactly that.
 */
export const RETENTION_DAYS = 7;

const SECONDS_PER_DAY = 86_400;

/**
 * Deletes sends past the retention window. Returns how many went.
 *
 * Strictly older than the window (`<`, not `<=`): a row sitting exactly on the
 * cutoff survives and is swept the following night. The choice is arbitrary but
 * deliberate, and a test pins it so it stays a decision rather than drift.
 *
 * @param now injectable so the boundary is testable without waiting a week;
 *   callers in production always want the real clock.
 */
export async function purgeOldSends(env: Env, now: number = nowSeconds()): Promise<number> {
  const cutoff = now - RETENTION_DAYS * SECONDS_PER_DAY;

  const result = await env.DB.prepare("DELETE FROM sends WHERE sent_at < ?")
    .bind(cutoff)
    .run();

  return result.meta.changes ?? 0;
}
