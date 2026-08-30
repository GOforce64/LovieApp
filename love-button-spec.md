# Love Button — project spec

An Android app for exactly two people. Tap a widget on your home screen, her phone
buzzes with a distinct sound. That's the whole product.

This document is written to be read twice: once to build from, and once later to
understand what was built. Sections marked **Why** explain reasoning rather than
instructions — skip them while building, read them when you come back to learn.

- **Status:** design approved, not yet implemented
- **Date:** 2026-08-21
- **Author's context:** first project of this kind; the code is meant to be dissected later

---

## 1. What we're building

Tapping a home-screen widget sends an instant push notification to your partner's
phone. Four fixed messages, each with its own widget, its own icon and its own
notification sound. The sender's widget lights up when the message is delivered,
and again when she opens it.

**The core is the buzz.** Widgets, sounds, and the delivered/seen indicators are
decoration around one moment: she taps, your phone buzzes, within seconds. Every
design decision below resolves in favour of that moment.

### Hard constraints

| Constraint | Consequence |
|---|---|
| Must run at $0, free tiers only, no credit card | Cloudflare Workers free plan + Firebase Spark plan |
| Source code published publicly on GitHub | Nothing secret may live in the repo |
| Sideloaded APK, not Google Play | You control signing; no store review |
| **Both phones are Xiaomi (MIUI/HyperOS)** | Delivery reliability is a first-class feature (§8), not late-stage polish |

### Deliberately not built

- **No send counter, streak, or tally.** Turning the gesture into a number to beat
  is the wrong incentive.
- **No friend list, no recipient picker, no target selection anywhere.** There are
  two people. The app knows which one you are.
- **No message history.** Sends are stored for seven days purely to correlate
  receipts, then deleted.

### Deferred to a later version

Firebase App Check, end-to-end encryption, free-text messages, quick-reply actions.
Do not build these now.

---

## 2. How it actually works

**Read this section first.** Everything below it is detail.

You tap a widget. The widget doesn't talk to her phone — phones can't reach each
other directly, because neither has a fixed address on the internet and both spend
most of their lives asleep. Instead:

1. Your phone sends a small HTTPS request to **a server you own** (a Cloudflare
   Worker), saying only *"message 3."* It doesn't say who it's for.
2. The server checks who you are from your request's credentials, then looks up who
   your partner is — this is fixed and stored server-side.
3. The server asks **Google's Firebase Cloud Messaging (FCM)** to wake her phone.
   FCM is the only mechanism that can reliably wake a sleeping Android device;
   Google maintains a persistent connection to every Android phone for exactly this.
4. Her phone wakes, receives `{type: "msg", msg_id: 3}`, looks up what message 3
   means locally, and posts a notification with the right icon and sound.
5. Her phone immediately reports back *"delivered"* through the same server, which
   pushes that back to your phone, which lights up your widget.

### Why there's a server at all

Sending through FCM requires a Google **service account private key**. That key can
send a push to any device registered to your Firebase project. If it shipped inside
the APK — which you're publishing on GitHub — anyone could extract it.

So the key lives in exactly one place: as a secret attached to your Cloudflare
Worker. The phone never sees it. The repo never sees it. The Worker is both the only
holder of the key and the only place that decides who is allowed to send what to whom.

### Why the push carries a number, not words

The notification payload contains `msg_id: 3`, not `"I love you"`. The receiving app
maps 3 to text, icon and sound locally. Two consequences: the actual words never
transit Google's servers, and adding a fifth message later is a change to the app
alone, with no server deploy.

---

## 3. Architecture

```
Phone A ──HTTPS + Bearer token──> Cloudflare Worker ──> FCM v1 ──> Phone B
   ▲                                    │                             │
   └────── receipt push ────────────────┴────── receipt POST ─────────┘
                                        │
                              D1 (devices, sends)
                              KV (Google OAuth token cache)
```

**Client:** Kotlin, Jetpack Compose (screens), Glance (widgets), WorkManager
(network calls), DataStore (local state), Firebase Messaging SDK. `minSdk 26`
(notification channels require it), target current stable.

**Server:** Cloudflare Worker in TypeScript with the Hono router. D1 (Cloudflare's
SQLite) for state. KV for one cached token.

**Identity:** opaque per-device bearer tokens issued by the Worker. No Firebase Auth,
no Google Sign-In.

### Why not Firebase Auth

The original draft of this project used Google Sign-In. For an app with exactly two
accounts, that means a sign-in screen, SHA-1 fingerprints registered for both debug
and release builds, API key restrictions, token refresh handling, and server-side JWT
verification against Google's rotating public keys — a reception desk installed in
your own house. Opaque tokens give the same security for this threat model with a
fraction of the machinery. See §4.

### Why not a third-party push relay (ntfy, Pushy, OneSignal)

Considered and rejected. The battery-friendly path for services like ntfy requires
running *their* app; a custom client would need to hold a persistent socket open,
which MIUI will kill within minutes — it fails precisely where reliability matters
most. And routing through a shared sender loses per-message notification channels,
which is how distinct sounds work at all.

---

## 4. Security model

The repo is public. This section is what makes that safe.

### Identity: enrolment codes and device tokens

Two identities exist, `person 1` and `person 2`, and no third can ever exist — this
is enforced by a `CHECK (person IN (1,2))` constraint in the database, not by
convention.

Two long random codes live as Worker secrets: `ENROLL_CODE_1` and `ENROLL_CODE_2`.
On first launch the app asks for one. The Worker compares it in **constant time**,
and on a match issues a 256-bit device token, storing only its SHA-256 hash.

Every subsequent request carries `Authorization: Bearer <device token>`. The Worker
hashes the presented token and looks up the device row by hash. No row, no service.

- Codes are **reusable**, so you can re-enrol after a factory reset. They are long
  enough (32+ chars, generated with `crypto.getRandomValues`) that brute force is
  hopeless, and enrolment is additionally rate-limited to 5 attempts per hour per IP.
- Codes are **rotatable** with a single `wrangler secret put`.
- Because only hashes are stored, a database leak yields no working credentials.

### The three invariants

These are the rules that make the whole thing safe. Every endpoint must uphold them.

1. **The sender is the authenticated device.** Never read a sender identity out of a
   request body.
2. **The recipient is derived arithmetically, server-side: `3 - from_person`.**
   `/v1/send` accepts a message id and nothing else. There is no field in which to
   name a victim. Even a fully compromised device token can only send to its own
   partner.
3. **Only the recipient may acknowledge a send.** `/v1/receipts` verifies the send
   row's `to_person` against the caller before recording anything. Otherwise anyone
   holding a send id could forge a "seen."

### Other rules

- The service account JSON is a Worker secret (`wrangler secret put`). Never in the
  repo, never in the APK, never in `wrangler.toml`.
- Every endpoint except `/health` requires a valid bearer token.
- `msg_id` is validated against a **server-side** allowlist. Never trust a
  client-supplied list of valid messages.
- No CORS headers. Reject anything that isn't the expected method and content type.
- Abuse ceiling: `MAX_SENDS_PER_HOUR = 500` per person, defined as a single constant.
  This is a circuit breaker against a compromised device or a runaway loop, **not** a
  usage limit — normal human tapping will never approach it. Returns 429 with
  `Retry-After`.

### 4.1 Publishing safely

`.gitignore`:

```
# secrets — never commit
*.jks
*.keystore
keystore.properties
local.properties
service-account*.json
.dev.vars
app/google-services.json

# build
build/
.gradle/
node_modules/
.wrangler/
```

- Commit `app/google-services.json.example` with placeholder values plus a README
  note. The real file ships inside the APK anyway, so it isn't strictly secret, but
  keeping it out of the repo stops anyone cloning a working client against your
  Firebase project by accident.
- In the Google Cloud console, restrict the Android API key to the app's package name
  plus its release SHA-1.
- Install a pre-commit hook (`gitleaks` or `git-secrets`) that blocks commits
  containing `-----BEGIN PRIVATE KEY-----` or `"type": "service_account"`.
- `wrangler.toml` may contain D1 and KV ids — those are identifiers, not credentials,
  and are safe to commit.
- Store the release keystore and its passwords outside the repo, and back them up.
  **Losing the keystore means the app can never be updated in place on her phone.**

---

## 5. Server

### 5.1 Schema

```sql
CREATE TABLE devices (
  id         TEXT PRIMARY KEY,              -- uuid
  person     INTEGER NOT NULL CHECK (person IN (1,2)),
  auth_hash  TEXT NOT NULL UNIQUE,          -- SHA-256 of the bearer token
  fcm_token  TEXT,
  label      TEXT,                          -- "Giorgos · Xiaomi 13"
  created_at INTEGER NOT NULL,              -- epoch seconds
  updated_at INTEGER NOT NULL
);
CREATE INDEX idx_devices_person ON devices(person);

CREATE TABLE sends (
  id           TEXT PRIMARY KEY,            -- crypto.randomUUID()
  from_person  INTEGER NOT NULL,
  to_person    INTEGER NOT NULL,
  msg_id       INTEGER NOT NULL,
  sent_at      INTEGER NOT NULL,
  delivered_at INTEGER,                     -- her app posted the notification
  seen_at      INTEGER                      -- she tapped it
);
CREATE INDEX idx_sends_from_time ON sends(from_person, sent_at);
```

Two tables. There is no `users` table (display names are Worker vars) and no
`invites` table (there is no pairing flow).

`sends.id` is a UUID rather than an autoincrementing integer so that a receipt can be
correlated without leaking how many messages have ever been sent.

**Retention:** a daily cron trigger deletes `sends` rows older than 7 days. They exist
for receipt correlation and the abuse ceiling, not as history.

### 5.2 API

All responses are JSON. All errors are `{ "error": "code", "message": "..." }`.

| Method | Path | Auth | Body | Returns |
|---|---|---|---|---|
| GET | `/health` | none | — | `{ok: true}` |
| POST | `/v1/enroll` | code | `{code, fcm_token, label}` | `{device_id, auth_token, person, partner_name}` |
| POST | `/v1/devices` | bearer | `{fcm_token}` | `{ok: true}` |
| DELETE | `/v1/devices` | bearer | — | `{ok: true}` |
| POST | `/v1/send` | bearer | `{msg_id}` | `{send_id, delivered: n}` |
| POST | `/v1/receipts` | bearer | `{send_id, state}` | `{ok: true}` |

#### `/v1/enroll`

Rate-limited to 5 attempts per hour per IP. Compares the supplied code against both
secrets in constant time. On success: generate a 256-bit token, insert a `devices`
row storing its SHA-256, return the token **once** — it is never retrievable again.

#### `/v1/devices`

FCM registration tokens rotate. The app calls this on every launch and whenever the
SDK reports a new token. If a row already exists with the same `fcm_token` under a
different device id, delete it — the same physical phone re-enrolled.

#### `/v1/send`

- Validate `msg_id` against the server-side allowlist.
- Check the abuse ceiling.
- `to_person = 3 - from_person`.
- Generate `send_id`, insert the `sends` row, then fan out to all of the partner's
  device tokens.
- If FCM returns `UNREGISTERED` for a token, delete that device row — the app was
  uninstalled or the token expired. **`INVALID_ARGUMENT` does not qualify**; see
  §12, where this was decided at milestone 8.
- Return `{send_id, delivered: n}`. **If `n == 0`, still return 200.** The app should
  say "no active device on her phone", not show a generic failure.

#### `/v1/receipts`

- Look up the `sends` row. 404 if missing.
- **403 unless `row.to_person` equals the caller's person.**
- Idempotent and monotonic: setting `delivered` when already delivered is a no-op
  200; setting `seen` also sets `delivered_at` if it is unset; state never moves
  backwards.
- Set the timestamp, then push a receipt to the **original sender's** devices.
- Return 200 regardless of whether that push succeeded — the receipt is recorded
  either way.

### 5.3 Talking to FCM

The Worker mints its own Google OAuth access token, because the `googleapis` library
does not run in the Workers runtime:

1. Build a JWT — issuer is the service account's `client_email`, scope
   `https://www.googleapis.com/auth/firebase.messaging`, audience
   `https://oauth2.googleapis.com/token`, one hour expiry.
2. Sign it RS256 using WebCrypto: `crypto.subtle.importKey` on the PKCS#8 private
   key, then `RSASSA-PKCS1-v1_5` with SHA-256.
3. Exchange it at `https://oauth2.googleapis.com/token` with
   `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`.
4. **Cache the access token in KV for 55 minutes. This is not optional** — see the
   CPU note in §12.
5. POST to `https://fcm.googleapis.com/v1/projects/<project-id>/messages:send`.

Every payload is **data-only**. Never include a `notification` block — that lets the
system tray render the message itself, which bypasses our per-message channels and
therefore our sounds.

**Message push** — high priority, must wake the device through Doze:

```json
{
  "message": {
    "token": "<device token>",
    "data": {
      "type": "msg",
      "send_id": "…",
      "msg_id": "3",
      "from_name": "Giorgos",
      "sent_at": "1755600000"
    },
    "android": { "priority": "HIGH" }
  }
}
```

**Receipt push** — normal priority, because it isn't urgent and normal priority costs
far less battery:

```json
{
  "message": {
    "token": "<sender's device token>",
    "data": { "type": "receipt", "send_id": "…", "state": "delivered", "at": "1755600004" },
    "android": { "priority": "NORMAL" }
  }
}
```

A receipt must **never** produce a notification on the sender's phone. It updates
widget state and nothing else.

---

## 6. Android app

### 6.1 Screens

Four, and two of them you see once.

1. **Enrol** — a single text box for your code. Once per phone, ever.
2. **Setup checklist** — the MIUI screen (§8). Re-verifies silently on every launch.
3. **Home** — her name, the four messages as tap-to-send rows with the mascot
   beside each, and a focal area showing the most recent send's state (§7.1).
   Unlike the widget, it keeps the final state rather than returning to idle:
   remembering is this screen's job, and a permanently lit home-screen button
   would only be noise.
4. **States guide** — the six ladder states, each shown with the widget's own
   drawable and a line explaining it.

No friend list, no recipient picker, no send counter anywhere.

### 6.2 Messages

A local constant list. Start with four:

| id | text | icon | channel id | sound |
|---|---|---|---|---|
| 1 | I love you | heart | `msg_1` | `love.ogg` |
| 2 | Thinking of you | thought bubble | `msg_2` | `thinking.ogg` |
| 3 | Miss you | waving hand | `msg_3` | `miss.ogg` |
| 4 | Call me when you can | phone | `msg_4` | `call.ogg` |

The same list must exist as an allowlist of valid ids on the server (§5.2).

**Sound sources**, all free and requiring no attribution: Pixabay sound effects,
Kenney's CC0 audio packs, or Freesound filtered to the CC0 tag — check each file
individually there, as the site mixes CC0 and CC-BY. Keep each under one second and
export as `.ogg` into `res/raw`.

### 6.3 Notification channels — the one irreversible decision

Since Android 8, **a channel's sound is fixed at creation and cannot be changed
afterwards.** Changing it requires deleting and recreating the channel, which resets
her notification settings and is visible to her.

- Create one channel per message id on first run, each with its own sound and
  importance `HIGH`.
- Adding a *new* message later is fine — it's a new channel id.
- Changing an *existing* message's sound is not. **Finalise all four sounds before
  the first install.**
- Use a unique notification id per send so rapid sends stack rather than replacing
  one another.

Request the `POST_NOTIFICATIONS` runtime permission on Android 13+, and handle her
declining it gracefully.

### 6.4 Receiving

One `FirebaseMessagingService`, branching on `data.type`:

- **`msg`** — map `msg_id` to text and channel, post the notification, then fire
  `POST /v1/receipts {send_id, state: "delivered"}` via WorkManager. Then decide
  `seen` (below).
- **`receipt`** — post nothing. Look up the pending send by `send_id` in DataStore,
  update that widget's state, and clear the entry once `seen` arrives or the window
  expires.

**Seen means she looked at the screen, not that she tapped anything.** A message
read on the lock screen and swiped away was still read, and making her open the app
to prove it reports the wrong thing. So, at the moment the notification posts:

- If the screen is on **and** the phone is unlocked, she is already looking at it —
  fire `state: "seen"` immediately. Both halves are required: a screen that lit up
  behind the keyguard is the phone reacting, not a person reading.
- Otherwise store the `send_id` and fire `seen` for everything stored on the next
  unlock, via a receiver for `ACTION_USER_PRESENT` (and `ACTION_SCREEN_ON`, the only
  signal on a phone with no keyguard).

That receiver **must be registered in code, not in the manifest**.
`ACTION_USER_PRESENT` is not on Android's implicit-broadcast exemption list, so a
manifest-declared receiver for it is never invoked on targetSdk 26+ — it looks
correct and does nothing. Registering it from `Application.onCreate` means the push
that delivers the message is what starts the listener. If the process is reclaimed
before she unlocks, no `seen` is reported: the failure is silence, never a wrong
answer.

There is no read-receipt toggle. Receipts are always sent.

The `seen` POST fires regardless of how old the send is — the receipt is a record,
and recording is uncapped. Only the sender's *tile* is time-limited, by the 20-second
window in §7.1.

### 6.5 Network calls

**Every outbound call goes through WorkManager, never inline.** Android can kill the
app or the widget host process mid-request; WorkManager retries when connectivity
returns. On MIUI this is not optional.

---

## 7. Widgets

**One widget type per message** — a separate `GlanceAppWidgetReceiver`,
`GlanceAppWidget` and `appwidget-provider` XML each, registered separately in the
manifest, so all four appear as distinct entries in the launcher's widget picker with
their own preview image and label.

Each widget:

- **2x2 cells**, `minWidth`/`minHeight` 110dp, `targetCellWidth`/`targetCellHeight` 2,
  resizing disabled.
- A single large icon filling the tile edge to edge, with the label small underneath.
  The whole tile is the tap target.
- Sends its one hardcoded message. No configuration activity, no picker, no state to
  choose — tapping it does exactly one thing.
- If not yet enrolled, renders dimmed and opens the app instead of sending.

**Tap handling:** `ActionCallback` → enqueue a WorkManager `OneTimeWorkRequest` that
makes the HTTP call.

Fire a short haptic (`HapticFeedbackConstants.CONFIRM`) on tap. This does more for
perceived responsiveness than any visual effect, because it lands before the network
call even starts.

### 7.1 Visual state ladder

The state changes *are* the animation. Each transition is a single widget update,
which is what `RemoteViews` handles well.

| State | Icon | Colour | Held for |
|---|---|---|---|
| Idle | outline | pale grey-pink | — |
| Sending | outline, dimmed | crimson, part-filled | until response |
| Sent | filled | crimson | until receipt or timeout |
| Delivered | filled | pink | until seen, or the window closes |
| Seen | filled + soft outer glow | pink + gold | 4s, then idle |
| Failed | outline + small ✗ | grey | 3s, then idle |

The app's focal area shows the same six states from the same artwork, but
animated per pixel rather than swapped between finished pictures, and it keeps
the final state where the widget returns to idle. See the in-app redesign design
document, §4.3 and §4.5.

Android 12+ animates widget content changes on its own, which is enough to make the
bloom feel deliberate without any frame-by-frame work.

**Timeout:** if no `delivered` receipt arrives within **20 seconds**, settle on plain
"Sent" and clear the pending entry. Receipts arriving after that are dropped silently
— a heart lighting up for something sent an hour ago is confusing, not sweet.

Whatever returns the tile to idle at the end of that window must decide by asking
**what the tile is currently displaying**, not by asking whether the pending entry is
still live. The entry is written before the request and expires at exactly the same
20 seconds, so after waiting the window out that lookup can only ever answer "gone" —
a guard built on it never fires and the tile stays lit forever.

**Correlation:** store `pending_send_id → glanceId` in DataStore when a send is
dispatched. On receipt, resolve the widget via `GlanceAppWidgetManager` and update
only that one.

**The race:** her phone can acknowledge before your `POST /v1/send` returns, so a
receipt may arrive for a `send_id` the app hasn't stored yet. Buffer unmatched
receipts for a few seconds before discarding them.

---

## 8. MIUI reliability — a first-class feature

Both phones are Xiaomi, so this is not an edge case. MIUI/HyperOS has three separate
mechanisms that will each independently break delivery:

1. **Autostart is off by default** for sideloaded apps.
2. **Battery saver defaults to "Restricted"**, which suspends background work.
3. **Apps not "locked" in the recents view** get purged under memory pressure.

### The setup checklist screen

Build a screen that walks through each item with a button that jumps straight into the
right settings page:

| Item | Intent |
|---|---|
| Ignore battery optimisations | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (standard Android) |
| MIUI Autostart | `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity` |
| MIUI battery saver → No restrictions | `com.miui.powerkeeper/.ui.HiddenAppsConfigActivity` |
| Lock in recents | Manual — show an illustration; no intent exists |

**Wrap every one of these in `try`/`catch`** and fall back to the app's own settings
page. The MIUI component names differ between versions and will throw
`ActivityNotFoundException` on some builds. Never let a missing OEM activity crash the app.

The screen re-checks `isIgnoringBatteryOptimizations()` on every launch, so if a
HyperOS update silently undoes the setup you find out before she does.

### What cannot be fixed in code

If the app is ever **force-stopped** from Settings, FCM delivery stops entirely until
she opens the app again. Nothing can be done about this in code. Document it in the
README.

### Verify the ROM

Confirm both phones run a **Global or EEA ROM**, not a China ROM. China ROMs ship
without Google Play Services, and FCM does not exist there — no push, no workaround.
Check Settings → About phone; Global builds end in `.MIXM` or `.EUXM`, China builds
end in `.CN`.

---

## 9. Setup you have to do yourself

1. Create a Firebase project. Add an Android app with your package name; download
   `google-services.json` into `app/`.
2. Firebase console → Project settings → Service accounts → generate a private key
   JSON. Cloud Messaging is free on the Spark plan; no card required.
3. In the Google Cloud console, restrict the Android API key to the package name plus
   your release SHA-1.
4. Create a Cloudflare account. `npm i -g wrangler`, then `wrangler login`. The free
   Workers plan requires no card.
5. `wrangler d1 create love-button` and `wrangler kv namespace create TOKEN_CACHE`;
   paste the returned ids into `wrangler.toml`.
6. `wrangler secret put FCM_SERVICE_ACCOUNT` — paste the whole JSON as one line.
7. `wrangler secret put ENROLL_CODE_1` and `ENROLL_CODE_2` — generate with
   `openssl rand -hex 24`. Save both in a password manager; you'll need them again
   after any factory reset.
8. Set `FIREBASE_PROJECT_ID`, `PERSON_1_NAME` and `PERSON_2_NAME` as plain vars in
   `wrangler.toml`.
9. Android Studio and SDK, plus **two physical devices**. The emulator's Play
   Services can be unreliable for FCM.
10. Generate a release keystore. Back it up somewhere that isn't your laptop.

Sounds aren't needed until milestone 4.

---

## 10. Build order

Do not proceed until the current milestone is verified on real hardware.

| # | Milestone | What you can do at the end of it |
|---|---|---|
| 0 | Accounts, keys, project skeleton | — |
| 1 | Worker: `/health`, `/v1/enroll`, `/v1/send` | Trigger a push with a single `curl` |
| 2 | **Minimal app: enrol screen, one button, receiving service** | **Tap your phone, hear hers buzz** |
| 3 | MIUI checklist screen + overnight smoke test | Trust it |
| 4 | Four messages, four channels, four sounds | Hear the difference between them |
| 5 | One widget: 2x2, WorkManager send, haptic, idle/sending/sent/failed | Send from the home screen |
| 6 | Receipts: endpoint, reverse push, delivered/seen states, 20s timeout, seen-on-unlock | See it land |
| 7 | The other three widgets | — |
| 8 | Hardening: abuse ceiling, retention cron, `UNREGISTERED` cleanup, README | Publish it |

**Milestone 2 is the whole hard part.** Everything after it is decoration layered onto
something you have already watched work. Milestone 3 exists because on MIUI a loop
that works while you're staring at the phone may not work at 3am — leave it running
overnight and send one in the morning before building anything else.

### 10.1 Testing on two of your own phones, then handing over

You do not need her phone to build this. Use a second phone of your own —
an old handset, a tablet, anything running a Global-ROM Android 8 or newer —
for every milestone up to and including 7.

**There is no "unpair" step**, because there is no pairing. Enrolment codes are
reusable by design, precisely so this works:

1. Your test phone enrols with `ENROLL_CODE_2` — her code. It becomes *a device
   belonging to person 2*. Nothing anywhere records that it is or isn't her.
2. When you're ready to hand over, her phone enrols with **the same code**.
   Person 2 now has two devices.
3. `/v1/send` fans out to *all* of the recipient's device tokens, so both phones
   buzz. This is a feature, not a bug — see below.
4. Retire the test phone by opening the app on it and signing out, which calls
   `DELETE /v1/devices`. If the phone is already wiped or sold, delete the row
   directly:

```bash
wrangler d1 execute love-button --remote \
  --command "SELECT id, person, label, updated_at FROM devices"
wrangler d1 execute love-button --remote \
  --command "DELETE FROM devices WHERE id = '<the test phone id>'"
```

Set a meaningful `label` at enrolment (`"test · Redmi Note"`) so the rows are
tellable apart weeks later.

**The trap:** rotating `ENROLL_CODE_2` does **not** revoke the test phone. Its
bearer token was already issued and keeps working — rotating a code only stops
*future* enrolments. To cut off a device you must delete its row.

**Keep the test phone enrolled for the first week or two.** Every send lands on
both, so you see exactly what she sees — including whether a sound is too loud or
a notification arrives late — without having to ask her. An extra permanently
enrolled device (a bedside tablet, an old handset) is a legitimate long-term
setup, not a workaround.

**What this does not prove:** a clean run on your two phones says nothing about
hers. Every MIUI phone needs its own Autostart, battery and lock-in-recents
setup (§8), and HyperOS versions differ in where those settings live and how
aggressively they reset. The §8 checklist screen runs on her phone too, and the
overnight test of milestone 3 must be repeated once she has the app installed.
Treat her phone as an untested platform on day one, because it is.

---

## 11. Testing

- **Worker:** unit-test the pure logic — constant-time code comparison, recipient
  derivation, the receipt state machine's monotonicity, `msg_id` validation. Use
  Miniflare or `wrangler dev --local` for endpoint tests against a local D1.
- **The three invariants of §4 each get an explicit test.** They are the security
  model; a regression there is the one bug that actually matters.
- **Android:** unit-test the message-id mapping and the pending-send correlation
  store. Widget and notification behaviour is verified by hand on real hardware —
  automated testing of Glance and OEM notification behaviour costs far more than it
  returns for a two-person app.
- **The overnight test is a required gate**, not an optional check.

---

## 12. Known traps

- **Glance is not regular Compose.** Only Glance composables work in a widget. No
  `LazyColumn`, no arbitrary modifiers, no `Canvas`.
- **Widget updates are IPC** — slow and rate-limited by the launcher. Don't push more
  than a handful per interaction. This is exactly why the state ladder replaced a
  multi-frame animation.
- **The receipt can beat the send response.** See §7.1.
- **Channel sounds are immutable after creation.** See §6.3.
- **D1 has no cross-statement transactions** in the usual sense — use `batch()` where
  two writes must land together.
- **The Workers free tier gives 10ms CPU per request.** RSA signing sits close to that
  ceiling, which is the real reason the OAuth access token must be cached in KV rather
  than minted per send. Receipts double your request volume, so this matters more here
  than it would in a send-only design.
- **FCM throttles per device** at roughly a few hundred messages per minute, and
  Android drops notifications from an app posting too rapidly. Both floors sit below
  the 500/hour ceiling, so rapid-fire sends may be dropped by the platform rather than
  by us.
- **`INVALID_ARGUMENT` deletion was not self-healing — now resolved.** The Worker
  once deleted a device row when FCM returned `UNREGISTERED` *or*
  `INVALID_ARGUMENT`. The first is unambiguous; the second is not, because FCM
  returns it for a malformed *request* as readily as for a bad token — and a
  malformed request fails identically for **all** of the recipient's tokens, so
  every one of her device rows was deleted at once. Her bearer token's row went
  with them, so her app got 401 on everything and could only recover by
  re-enrolling by hand with the code from a password manager.

  Resolved at milestone 8: `isPermanentTokenFailure` now accepts `UNREGISTERED`
  only. A token that really is invalid simply keeps failing, which is a far
  cheaper failure than one that locks her out. The same reasoning already applied
  to the outer `error.status`, which has never been trusted.
- **Force-stop kills FCM** until the app is reopened. Unfixable in code; document it.
- **MIUI component names vary by version.** Always `try`/`catch` around OEM intents.

---

## 13. Concepts worth looking up later

Roughly in the order you'll meet them. Each is a general idea this project happens to
use, not a quirk of this project.

**Server side**
- *Bearer tokens* — why a random string in a header is enough to prove identity, and
  why the server stores only its hash
- *Constant-time comparison* — how naive string comparison leaks secrets through timing
- *JWT and RS256* — what a signed token is; why the Worker signs one to get another
- *OAuth 2.0 service accounts* — machine-to-machine auth without a human logging in
- *Edge compute and cold starts* — why a Worker has a 10ms CPU budget at all
- *Idempotency* — why `/v1/receipts` must tolerate being called twice

**Android**
- *Doze and App Standby* — how Android decides to stop your app running
- *High-priority FCM* — the exemption that lets a message punch through Doze
- *Notification channels* — the Android 8 model that moved control from app to user
- *`PendingIntent`* — handing another process permission to act as you
- *WorkManager* — durable background work that survives process death
- *RemoteViews and IPC* — why widgets are so much more constrained than normal UI

**General**
- *Threat modelling* — §4 is one; the question is always "who is the attacker?"
- *Defence in depth* — the rate limit exists even though the codes are already
  unguessable
- *YAGNI* — this spec is mostly a record of what was cut and why
