# Plan 4 design — receipts, delivered and seen

- **Status:** approved 2026-08-24, not yet planned
- **Depends on:** Plans 1–3, all merged to `main`
- **Spec authority:** `love-button-spec.md` §5.2, §5.3, §6.4, §7.1, §12. Where this
  document and the spec disagree, the spec wins **except** on the one deviation
  argued in §2, which is called out explicitly.

Milestone 6. Her phone tells yours that the message landed, and that she looked.

---

## 1. Scope

**In:** `POST /v1/receipts`; the reverse receipt push; the Delivered and Seen widget
states; the 20-second timeout; the read-receipt toggle.

**Out:** the in-app redesign. The app's home screen is a temporary scaffold and is
being redesigned in Plan 5, with real Compose animation for the same three states.
Building the ladder into rows that are about to be replaced would be building it
twice, so **this plan touches widgets only** — which is also the only surface spec
§7.1 designs for.

---

## 2. The `send_id` moves client-side — the one deviation

**Spec §5.2 has the server mint the `send_id`. This design has the app mint it.**

The spec flags the same hazard twice: §7.1 "her phone can acknowledge before your
`POST /v1/send` returns, so a receipt may arrive for a `send_id` the app hasn't
stored yet", and §12 "the receipt can beat the send response". Its answer is to
buffer unmatched receipts for a few seconds.

Minting the id in the app removes the hazard instead of mitigating it. The app
generates a UUID, writes `send_id → appWidgetId` to DataStore, and only then makes
the request. The mapping therefore exists before the send does, and a receipt cannot
arrive for an id the app does not know.

**Why this is worth a deviation:** the buffer is a timing mitigation whose failure is
invisible — a glow that silently never appears, on a feature whose entire purpose is
knowing she got it. The client-minted id makes that state unreachable rather than
unlikely. A buffer would also need its own expiry, and would be exercised only by a
race that is hard to reproduce on demand, so it is exactly the kind of code that
rots untested.

**What the server must do about it:** `/v1/send` accepts `send_id` in the body and
validates it is a well-formed UUID that this device has not already used. Trusting a
client-supplied primary key without validation would let a device overwrite its own
earlier sends. The recipient is still derived server-side from the authenticated
device, so invariant 2 is untouched — the client chooses an id, never a destination.

---

## 3. `POST /v1/receipts`

Bearer auth. Body `{send_id, state}` where `state` is `delivered` or `seen`.
Returns `{ok: true}`.

Per spec §5.3:

- **Only the recipient of a send may acknowledge it.** Checked against the stored
  row, so a device holding a send id cannot forge a "seen". This is the spec's third
  invariant and gets its own test.
- **Idempotent and monotonic.** Setting `delivered` when already delivered is a no-op
  200. Setting `seen` also sets `delivered_at` if unset. State never moves backwards.
- Set the timestamp, then push a receipt to the **original sender's** devices at
  normal priority — it is not urgent, and normal priority costs less against the
  free tier (spec §5.4).
- **Return 200 even if that push fails.** The receipt is recorded; redelivering it is
  not worth a failure the client cannot act on.

---

## 4. Her phone — sending receipts

`PushService` already branches on `data.type`. The `msg` branch gains, after posting
the notification, a `POST /v1/receipts {send_id, state: "delivered"}` through
WorkManager — never inline, per spec §6.5.

The notification's `PendingIntent` carries the `send_id`, so tapping it fires
`state: "seen"` before opening the app.

If the read-receipt toggle is off, the `seen` call is skipped and `delivered` is still
sent. Delivery confirmation is mechanical; read confirmation is a choice (spec §6.4).

---

## 5. Your phone — receiving receipts

The `receipt` branch **posts no notification**. Spec §6.4 is explicit, and a phone
that buzzes when she reads a message is a phone nobody wants.

It resolves `send_id → appWidgetId` from DataStore and moves that one tile. Delivered
and Seen are each held 4 seconds, then the tile returns to idle and the pending entry
is cleared.

**Timeout:** if no `delivered` arrives within 20 seconds, the tile settles on plain
Sent and the entry is dropped. Receipts arriving after that are discarded silently —
a heart lighting up for something sent an hour ago is confusing, not sweet (spec §7.1).

---

## 6. What the states look like

The tile is icon-only, so state lives in the artwork. The existing three fill stages
gain two more, an escalation in colour rather than in shape:

| State | Artwork |
|---|---|
| Sent | filled, pink `#FF6FA5` |
| Delivered | filled, deep crimson `#C2185B` |
| Seen | deep crimson fill, **gold border** `#FFC64B` |

Spec §7.1 describes Seen as "filled + soft outer glow". RemoteViews cannot blur, and
a literal dilated ring was tried and rejected: on shapes with gaps — the paw's toes,
the space between CALL's letters — an outward ring floods the tile and swallows the
icon. A gold border is the honest equivalent in this art style and reads at tile size.

**Thin shapes gild the glyph instead.** CALL is one pixel thick everywhere and so has
no border layer (the Plan 3 rule that stops the letters rendering dark). Without a
special case its Seen state would be identical to its Delivered one, so for shapes
with no border the glyph itself turns gold.

Drawables are already generated and staged at
`~/Downloads/love-button-assets/icons/receipts/`.

---

## 7. The toggle

A "send read receipts" switch on the home screen, per spec §6.1, backed by DataStore.
Plan 5's redesign will rehouse it; it lives there now because the setting has to exist
somewhere for §4 to honour it.

---

## 8. Testing

Following spec §11 — JVM and Workers tests for logic, hardware for the rest.

**Server**, which carries the heavier share because the invariants live there:
monotonicity (`seen` then `delivered` must not regress), idempotency, the
recipient-only rule, a forged `send_id` from a non-recipient, a malformed UUID, and a
duplicate `send_id` from the same device.

**App:** the pending-map expiry, and that a receipt for an unknown `send_id` is
dropped rather than throwing.

**Hardware:** tap a widget and watch pink → crimson → gold as she receives and opens
it; confirm a receipt never raises a notification on the sender; confirm the tile
settles on Sent when her phone is offline past the timeout.
