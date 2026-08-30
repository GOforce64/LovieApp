import type { Env } from "./env";

/**
 * What happened to one push.
 *
 * `unregistered` is separated from `error` because it means something
 * permanent — the app was uninstalled or the token expired — and the caller
 * should delete the device row rather than retry.
 */
export type PushResult = "ok" | "unregistered" | "error";

/** HIGH wakes the device through Doze. NORMAL costs far less battery. */
export type PushPriority = "HIGH" | "NORMAL";

/** FCM data payloads are string-to-string only. */
export type PushData = Record<string, string>;

function fcmEndpoint(env: Env): string {
  return `https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`;
}

interface FcmErrorBody {
  error?: {
    status?: string;
    details?: Array<{ errorCode?: string }>;
  };
}

/**
 * True when FCM is telling us this specific token will never work again.
 *
 * Only the nested `details[].errorCode` proves that. The outer `error.status` is
 * derived from the HTTP status, so a bare "NOT_FOUND" there can equally mean a
 * mistyped FIREBASE_PROJECT_ID or a decommissioned Firebase project — in which
 * case every one of the recipient's tokens 404s identically, and trusting the
 * outer field would delete every device row she has. Her app cannot then recover
 * via /v1/devices, because her bearer token's row went with them: she would have
 * to re-enrol by hand. Trust the specific detail, never the generic status.
 */
function isPermanentTokenFailure(status: number, body: FcmErrorBody): boolean {
  if (status !== 404 && status !== 400) return false;

  const detailCodes = (body.error?.details ?? []).map((d) => d.errorCode);

  // UNREGISTERED only. INVALID_ARGUMENT is ambiguous in exactly the way the
  // outer status is: FCM returns it for a malformed REQUEST as readily as for a
  // bad token, and a malformed request fails identically for every one of her
  // devices — so acting on it deleted every row she has, including the one her
  // bearer token is looked up by. Her app then gets 401 on everything and the
  // only way back is re-enrolling by hand with the code from a password manager.
  // Spec §12 flagged this as the milestone 8 decision; this is that decision.
  // A live token that really is invalid simply keeps failing, which is a far
  // cheaper failure than one that locks her out.
  return detailCodes.includes("UNREGISTERED");
}

/**
 * Sends one data-only push to one device.
 *
 * Never add a `notification` block. Doing so hands rendering to the Android
 * system tray, which bypasses the app's per-message notification channels
 * and therefore its per-message sounds — the whole point of the app.
 */
export async function sendPush(
  env: Env,
  accessToken: string,
  fcmToken: string,
  data: PushData,
  priority: PushPriority,
): Promise<PushResult> {
  const res = await fetch(fcmEndpoint(env), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      message: {
        token: fcmToken,
        data,
        android: { priority },
      },
    }),
  });

  if (res.ok) return "ok";

  let body: FcmErrorBody = {};
  try {
    body = (await res.json()) as FcmErrorBody;
  } catch {
    // A non-JSON error body is still an error; fall through with an empty object.
  }

  return isPermanentTokenFailure(res.status, body) ? "unregistered" : "error";
}
