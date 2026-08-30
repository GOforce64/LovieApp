# Love Button

An Android app for exactly two people. Tap a widget on your home screen, her
phone buzzes with a distinct sound. That's the whole product.

Four fixed messages, each with its own widget, its own pixel icon and its own
notification sound. The sender's widget lights up when the message is delivered,
and again when she opens it. There is no message list, no chat, no counter, and
no way to add a third person — the database enforces that last one with a
`CHECK (person IN (1,2))` constraint rather than a convention.

**[`love-button-spec.md`](love-button-spec.md) is the real document.** It explains
what was built and, more usefully, what was deliberately not built and why. This
file is just the front door.

## How it works

Phones cannot reach each other directly — neither has a fixed address and both
spend most of their lives asleep. So:

```
Phone A ──HTTPS + bearer token──> Cloudflare Worker ──> FCM ──> Phone B
   ▲                                    │                          │
   └────── receipt push ────────────────┴──── receipt POST ────────┘
                                        │
                              D1 (devices, sends)
```

The push carries `msg_id: 3`, never the words. The receiving app maps the number
to text, icon and sound locally, so the actual messages never transit Google's
servers, and adding a fifth is a change to the app alone.

The Worker exists because sending through FCM needs a Google service account
private key, and that key can push to any device in the project. It lives in
exactly one place: a Cloudflare Worker secret. Never in this repo, never in the
APK.

- **Client:** Kotlin, Compose (screens), Glance (widgets), WorkManager, DataStore
- **Server:** TypeScript, Hono, Cloudflare Workers, D1, KV — [`server/`](server/)
- **Cost:** zero. Free tiers only, no card.

## Known limits, honestly

- **Force-stopping the app kills FCM delivery** until it is opened again. This
  cannot be fixed in code — it is how Android works. If messages stop arriving,
  that is the first thing to check.
- **Both phones are Xiaomi**, where autostart is off by default for sideloaded
  apps and battery saver defaults to "Restricted". The app's Delivery setup
  screen walks through each setting and re-checks what it can on every launch.
- **China ROMs have no Google Play Services**, so FCM does not exist there. Both
  phones must be on a Global or EEA build.
- A message tapped with no signal is **abandoned after 20 seconds**, not queued.
  The bubble goes grey and you send again if you still mean it.

## Building it

You need a Firebase project, a Cloudflare account, and two physical Android
phones. [`docs/MANUAL-SETUP.md`](docs/MANUAL-SETUP.md) walks through everything
that needs a browser login or a phone in your hand.

```bash
git config core.hooksPath .githooks   # refuses to commit keys; do this first
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
cd server && npm install && npx vitest run
```

Two files are needed and are not in this repo:

- `app/google-services.json` — from the Firebase console.
  [`app/google-services.json.example`](app/google-services.json.example) shows its
  shape. The real one ships inside the APK anyway, so it is not strictly secret;
  keeping it out stops anyone cloning a working client against the project by
  accident.
- `local.properties` — needs `apiBaseUrl=https://love-button.<subdomain>.workers.dev`.
  The build fails without it on purpose, rather than producing an APK that
  installs cleanly and then fails at enrolment.

## Publishing safely

This repo is public, which is only safe because of §4 of the spec. The short
version: the service account key is a Worker secret; enrolment codes are Worker
secrets; only SHA-256 hashes of device tokens are stored, so a database leak
yields no working credentials; and `/v1/send` takes a message id and nothing
else, so there is no field in which to name a victim.

The pre-commit hook in `.githooks/` refuses any commit containing a private key
block or a service account JSON, and refuses the keystore, `keystore.properties`,
`local.properties`, `.dev.vars` and `google-services.json` by name. Enable it
once per clone with the `git config` line above — git does not do it for you.

**The release keystore is not in this repo and must never be.** Losing it means
the app can never be updated in place on her phone again; it would have to be
uninstalled and re-enrolled.
