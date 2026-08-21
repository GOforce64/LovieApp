import type { Context } from "hono";
import type { App } from "./env";
import type { StatusCode } from "hono/utils/http-status";

/**
 * Every error the API returns has this shape, so the Android client only
 * ever has to parse one thing.
 */
export function fail(
  c: Context<App>,
  status: StatusCode,
  code: string,
  message: string,
) {
  return c.json({ error: code, message }, status);
}

/** Epoch seconds. Every timestamp column in the schema uses this unit. */
export function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

/**
 * Reads a JSON body, returning null rather than throwing when the body is
 * absent or malformed. Callers turn null into a 400.
 */
export async function readJson<T>(c: Context<App>): Promise<T | null> {
  const contentType = c.req.header("Content-Type") ?? "";
  if (!contentType.includes("application/json")) return null;

  try {
    return (await c.req.json()) as T;
  } catch {
    return null;
  }
}
