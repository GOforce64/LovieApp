import { env, fetchMock } from "cloudflare:test";
import { describe, it, expect, beforeAll, afterEach } from "vitest";
import { sendPush } from "../src/fcm";

const FCM_ORIGIN = "https://fcm.googleapis.com";
const FCM_PATH = "/v1/projects/test-project/messages:send";

beforeAll(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});

afterEach(() => fetchMock.assertNoPendingInterceptors());

describe("sendPush", () => {
  it("posts a data-only high-priority message and reports ok", async () => {
    let captured: any;

    fetchMock
      .get(FCM_ORIGIN)
      .intercept({
        path: FCM_PATH,
        method: "POST",
      })
      .reply(200, (opts) => {
        captured = JSON.parse(opts.body as string);
        return { name: "projects/test-project/messages/1" };
      });

    const result = await sendPush(
      env,
      "access-token",
      "device-fcm-token",
      { type: "msg", send_id: "s1", msg_id: "3" },
      "HIGH",
    );

    expect(result).toBe("ok");
    expect(captured.message.token).toBe("device-fcm-token");
    expect(captured.message.data).toEqual({
      type: "msg",
      send_id: "s1",
      msg_id: "3",
    });
    expect(captured.message.android.priority).toBe("HIGH");

    // Critical: a `notification` block would let the system tray render the
    // message and bypass the app's per-message channels and sounds.
    expect(captured.message.notification).toBeUndefined();
  });

  it("reports unregistered when FCM says the token is dead", async () => {
    fetchMock
      .get(FCM_ORIGIN)
      .intercept({ path: FCM_PATH, method: "POST" })
      .reply(404, {
        error: {
          status: "NOT_FOUND",
          details: [
            {
              "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
              errorCode: "UNREGISTERED",
            },
          ],
        },
      });

    const result = await sendPush(env, "access-token", "dead-token", { type: "msg" }, "HIGH");

    expect(result).toBe("unregistered");
  });

  it("reports unregistered for an invalid argument", async () => {
    fetchMock
      .get(FCM_ORIGIN)
      .intercept({ path: FCM_PATH, method: "POST" })
      .reply(400, {
        error: {
          status: "INVALID_ARGUMENT",
          details: [
            {
              "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
              errorCode: "INVALID_ARGUMENT",
            },
          ],
        },
      });

    const result = await sendPush(env, "access-token", "bad-token", { type: "msg" }, "HIGH");

    expect(result).toBe("unregistered");
  });

  it("treats a 404 with no FCM detail code as transient, not a dead token", async () => {
    // This is what a mistyped FIREBASE_PROJECT_ID looks like: a generic 404 whose
    // outer status says NOT_FOUND but which carries no token-specific detail. If
    // this were classified "unregistered", the caller would delete every one of
    // the recipient's device rows and lock her out.
    fetchMock
      .get(FCM_ORIGIN)
      .intercept({ path: FCM_PATH, method: "POST" })
      .reply(404, { error: { status: "NOT_FOUND", message: "Requested entity was not found." } });

    const result = await sendPush(env, "access-token", "some-token", { type: "msg" }, "HIGH");

    expect(result).toBe("error");
  });

  it("reports error for a transient server failure", async () => {
    fetchMock
      .get(FCM_ORIGIN)
      .intercept({ path: FCM_PATH, method: "POST" })
      .reply(503, { error: { status: "UNAVAILABLE" } });

    const result = await sendPush(env, "access-token", "some-token", { type: "msg" }, "NORMAL");

    expect(result).toBe("error");
  });
});
