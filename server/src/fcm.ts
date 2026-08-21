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

/** True when FCM is telling us this token will never work again. */
function isPermanentTokenFailure(status: number, body: FcmErrorBody): boolean {
  if (status !== 404 && status !== 400) return false;

  const codes = [
    body.error?.status,
    ...(body.error?.details ?? []).map((d) => d.errorCode),
  ];

  return codes.includes("UNREGISTERED") || codes.includes("INVALID_ARGUMENT") ||
    codes.includes("NOT_FOUND");
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
