# What you have to do yourself

Everything in this file needs a browser login, a phone in your hand, or a password
an agent cannot type. Nothing here can be automated away.

Work top to bottom. Each block ends with a check you can run to prove it worked
before moving on — if the check fails, stop there rather than continuing.

---

## Where the project actually stands

| Piece | State |
|---|---|
| Worker (`server/`) | Code complete, 54 tests passing **locally**. Never deployed. |
| Android app (`app/`) | Tasks 1–5 done, 13 tests passing, APK builds. Enrolment screen works. |
| Firebase project | **Does not exist** |
| Cloudflare D1 + KV | **Do not exist** — `wrangler.toml` holds `00000000-…` placeholders |
| GitHub backup | Block A below |
| The buzz (Tasks 6–10) | Blocked until every block below is green |

The two plans stop where they stop for one reason: from Task 6 onward the app talks
to a live Worker and two real phones. That's this file.

---

## Block A — GitHub backup

**Why first:** everything below produces values you'll paste into files. Back up the
clean state before you start editing it.

- [ ] **A1. Install the GitHub CLI**

```bash
sudo pacman -S github-cli
```

- [ ] **A2. Log in** — opens a browser, asks you to paste a one-time code.

```bash
gh auth login
```

Choose: GitHub.com → HTTPS → authenticate with browser.

- [ ] **A3. Create the repo and push** (an agent can run this once A2 is done)

```bash
cd ~/Projects/LovieApp
gh repo create LovieApp --public --source=. --remote=origin --push
git push -u origin main feat/worker-core feat/android-buzz-loop
```

**Check:** `gh repo view --web` opens the repo and you can see `server/src/index.ts`.

**What must never appear there:** `app/google-services.json`, `local.properties`,
any `service-account*.json`, any `.dev.vars`. All five patterns are already in
`.gitignore` and verified untracked. Re-check after Block B, when the real
`google-services.json` lands on disk:

```bash
git status --short          # google-services.json must NOT be listed
```

---

## Block B — Firebase

Free (Spark plan). No card.

- [x] **B1. Create the project** — `lovieapp-b4068` — <https://console.firebase.google.com>

Any name. Google Analytics: off (nothing uses it).

- [x] **B2. Add an Android app**

Package name must be exactly:

```
com.lovebutton.app
```

Getting this wrong is silent — the app builds and then FCM never delivers.
Skip the SHA-1 field; nothing here uses Google sign-in.

- [x] **B3. Download `google-services.json`** — in place, gitignored

Put it at `app/google-services.json` (the repo has a `.example` next to it showing
the shape). It is gitignored.

- [x] **B4. Generate the service account key** — at `~/secrets/love-button-sa.json` (mode 600), project_id verified against wrangler.toml

Project settings → Service accounts → **Generate new private key**.

Save the JSON **outside this repo** — e.g. `~/secrets/love-button-sa.json`. Open it
and note the `project_id` value; you need it twice below.

**Check:**

```bash
ls -l app/google-services.json && git check-ignore -v app/google-services.json
```

Both must succeed — the file exists *and* git is ignoring it.

---

## Block C — Cloudflare

Free plan. No card.

- [x] **C1. Create a Cloudflare account** — <https://dash.cloudflare.com/sign-up>

- [x] **C2. Install and authenticate wrangler**

```bash
npm install -g wrangler
wrangler login
```

- [x] **C3. Create the database and the KV namespace** — done: D1 `2bf232d6-…` (WEUR), KV `939528e7…`

```bash
wrangler d1 create love-button
wrangler kv namespace create TOKEN_CACHE
```

Copy the `database_id` and the KV `id` out of the output. **Both.**

- [x] **C4. Paste them into `server/wrangler.toml`** — done, committed `6351f83`. `PERSON_2_NAME` still reads "Her".

Replace the two `00000000-0000-0000-0000-000000000000` placeholders, and set
`FIREBASE_PROJECT_ID` to the `project_id` from B4 — it must match that JSON
**exactly**, or every push is rejected by Google with an unhelpful error.

While you're in the file, decide what `PERSON_2_NAME` should say. It's currently
`"Her"`, and it's the name the app shows on her paired screen.

These three values are **not secrets** — they're safe in a public repo.

- [x] **C5. Generate the two enrolment codes** — 48 hex chars each, in the password manager

```bash
openssl rand -hex 24   # ENROLL_CODE_1 — yours
openssl rand -hex 24   # ENROLL_CODE_2 — hers
```

**Put both in a password manager now.** The server stores only a hash. If you lose
them you cannot re-enrol a phone after a factory reset without redeploying new ones.

- [x] **C6. Set the three secrets** — all three confirmed via `wrangler secret list`

```bash
cd server
wrangler secret put ENROLL_CODE_1     # paste code 1
wrangler secret put ENROLL_CODE_2     # paste code 2
cat ~/secrets/love-button-sa.json | tr -d '\n' | wrangler secret put FCM_SERVICE_ACCOUNT
```

The `tr -d '\n'` matters — the JSON has to arrive as one line.

- [x] **C7. Create the schema on the live database** — done: `devices` and `sends` live on Cloudflare

```bash
npm run migrate:remote
```

This has never been run. The tables exist only in the test runner's memory today.

- [x] **C8a. Subdomain** — resolved, but not the way the plan expected

The account subdomain is `<your-email-local-part>`, and Cloudflare exposes no way
to change it (the API replies "Account already has an associated subdomain", and
the dashboard shows only the Worker's own routes). Since this repo is public, the
URL is kept **out of git** instead — see D3.

- [x] **C8. Deploy** — live, `/health` returns `{"ok":true}`; 401 on a bogus bearer, 403 on a wrong code

```bash
npm run deploy
```

Write down the URL it prints: `https://love-button.<subdomain>.workers.dev`.

**Check:**

```bash
curl https://love-button.<subdomain>.workers.dev/health
```

Expected: `{"ok":true}`. Anything else — stop and fix before Block D.

---

## Block D — Phones

- [x] **D1. Confirm both phones are Global/EEA ROM, not China ROM** — confirmed 2026-08-23: both EEA.

Settings → About phone → build number. Global builds end `.MIXM` or `.EUXM`;
China builds end `.CN`. **A China ROM has no Play Services and FCM cannot work
there at all** — no push, no workaround, project over. Check this before anything
else on the phones.

- [ ] **D2. Developer options and USB debugging — both phones (HyperOS)**

HyperOS renamed the tap target. It is **OS version**, not "MIUI version".

1. **Settings → About phone → tap "OS version" seven times** → "You are now a developer".
2. **Settings → Additional settings → Developer options** — that is where Xiaomi hides it, not under System.
3. Turn on **USB debugging**.
4. Turn on **Install via USB**. This is the one that fights back:
   - It requires being **signed into a Mi account** on the phone.
   - A **newly created** Mi account can be gated for up to **96 hours**. Nothing
     works around this. Create the accounts first if you have not.
   - Some regions additionally want a **SIM inserted** and mobile data on for a
     moment while the toggle is confirmed.
5. Turn on **USB debugging (Security settings)** if present — separate from plain
   USB debugging, and some install paths need it.
6. Plug the phone in and set the USB mode to **File Transfer / MTP**. On Xiaomi,
   "Charging only" silently blocks adb — the device simply never appears.
7. On the phone, accept **"Allow USB debugging?"** and tick **"Always allow from
   this computer"**. Without the tick you re-accept on every reconnect.

**Yes — plug both phones into the PC at once.** `adb` handles multiple devices
fine; it only needs disambiguating when you act on one:

```bash
adb devices -l                 # both serials
adb -s <serial> install app/build/outputs/apk/debug/app-debug.apk
```

Without `-s`, adb refuses with "more than one device" rather than guessing.

### If "Install via USB" demands a SIM card

Hit on phone 2, 2026-08-24. HyperOS validates the toggle against Xiaomi's servers
and refuses without a SIM present. In order of preference:

1. **Wireless debugging** — Developer options → Wireless debugging → "Pair device
   with pairing code", then `adb pair <ip>:<pairPort>` and `adb connect
   <ip>:<mainPort>`. Different code path from USB installs, so the toggle does not
   apply. Gives full adb. Note the pairing port and the connect port differ.
2. **Any SIM** — the check is presence, not an active plan. An expired SIM works.
   The toggle usually stays on after the SIM is removed.
3. **Sideload by hand** — enough for the second phone, which per Task 6 Step 9 only
   needs the app *installed and enrolled*; sends come from curl and verification is
   by eye. Copy the APK over MTP, tap it in Files, allow "Install unknown apps".

**Not recommended:** the region-change-to-India trick. It unlocks the toggle but
moves an EEA device off EEA privacy defaults — disproportionate when 1 and 3 work.


- [x] **D3. Point the app at the Worker** — done, via `local.properties`

`API_BASE_URL` is read from `local.properties` (gitignored) rather than committed,
because the URL embeds a personal email handle and this repo is public. The line:

```properties
apiBaseUrl=https://love-button.<your-subdomain>.workers.dev
```

A missing value fails the build with that exact instruction rather than silently
producing an APK that installs and then cannot reach anything.

- [ ] **D4. Verify adb sees both**

```bash
adb devices
```

**Check:** two devices listed, neither saying `unauthorized` (accept the RSA prompt
on the phone screen if so).

---

## Then what

With A–D green, the remaining plan tasks unblock and an agent can build them:

| Task | What it is |
|---|---|
| 6 | Receiving pushes, notification channels, the buzz — **milestone 2** |
| 7 | Home screen send button |
| 8 | MIUI setup checklist screen (autostart, battery, lock in recents) |
| 9 | End-to-end from the app |
| 10 | **The overnight test** — a spec-mandated gate, not optional |

Task 6 Step 5 also needs both phones enrolled, one with each code from C5.

---

## Two things nothing can fix in code

1. **Force-stopping the app from Settings kills FCM delivery entirely** until she
   opens the app again. This is Android, not a bug in this project. Don't force-stop
   it, and tell her not to.
2. **Losing the enrolment codes** means re-deploying new ones. Password manager, C5.
