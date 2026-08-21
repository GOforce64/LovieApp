/**
 * The server's own list of valid message ids.
 *
 * The Android app has its own copy of this list, with the text, icon and
 * sound for each. The server deliberately knows only the numbers: the words
 * never touch this server, and never touch Google's. Adding a fifth message
 * means changing both this set and the app.
 */
const ALLOWED_MSG_IDS: ReadonlySet<number> = new Set([1, 2, 3, 4]);

export function isValidMsgId(id: unknown): id is number {
  return typeof id === "number" && Number.isInteger(id) && ALLOWED_MSG_IDS.has(id);
}
