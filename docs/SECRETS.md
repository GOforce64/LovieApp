# Secrets and machine setup

This repository is **public**. Nothing in it is confidential, and nothing
confidential has ever been committed to it — verified across all history, not just
the current tree.

This file is the inventory of everything that is *not* in the repo, and what you need
to do to get a second machine building and deploying. It deliberately contains **no
secret values**. It is safe to read, safe to publish, and safe to link people to.

---

## 1. What is actually secret

Four things. Only the first three are true secrets; the fourth is listed because
losing it is just as annoying.

| # | Secret | Lives in | Blast radius if leaked |
|---|---|---|---|
| 1 | `FCM_SERVICE_ACCOUNT` | Cloudflare Worker secret | **High.** A Google service-account key that can send push notifications as this Firebase project. Revoke and rotate immediately. |
| 2 | `ENROLL_CODE_1` | Cloudflare Worker secret | **High.** Anyone holding it can enrol a device as Hubby and send messages as him. |
| 3 | `ENROLL_CODE_2` | Cloudflare Worker secret | **High.** Same, for Wifey. |
| 4 | `app/google-services.json` | Local file, gitignored | **Low.** It ships inside the APK, so it is not really confidential; the API key is restricted to this package name. It is kept out of the repo by choice, not by necessity. |

### The one thing that will catch you out

**Cloudflare Worker secrets are write-only.** `wrangler secret list` returns names
only — there is no command, flag or dashboard page that reads a value back. That is
Cloudflare working as designed, not a missing feature.

So a backup script *cannot* export secrets 1–3 from Cloudflare. They must be captured
from their original source, once, and kept somewhere you control. That is exactly what
`scripts/secrets-backup.sh` is for.

---

## 2. What is NOT secret, despite looking like it

Do not waste effort hiding these. They are all already public in `server/wrangler.toml`
and they are all fine there:

| Value | Why it is safe |
|---|---|
| D1 `database_id` | An identifier. Useless without an authenticated Cloudflare account. |
| KV namespace `id` | Same. |
| `FIREBASE_PROJECT_ID` | Public by design; it appears in every client app. |
| `PERSON_1_NAME` / `PERSON_2_NAME` | "Hubby" and "Wifey". |
| Worker URL | Public endpoint. Its routes are protected by bearer tokens, not obscurity. |

Device bearer tokens are **not** in this list because they are not configuration —
they live in the D1 `devices` table and in each phone's own app-private DataStore.
See §5.

---

## 3. Resuming on a new machine

```bash
git clone https://github.com/GOforce64/LovieApp.git
cd LovieApp
./scripts/secrets-restore.sh ~/path/to/lovie-secrets.tar.gz
```

That script puts `app/google-services.json` back and pushes all three Worker secrets
with `wrangler secret put`. Everything below is what it does, in case you are doing it
by hand or the script is unavailable.

### 3.1 Prerequisites, in order

1. **JDK 21 or newer.** Robolectric runs the unit tests against SDK 36 jars, which are
   Java 21 bytecode. An older JDK fails in the test task, not the build.
2. **Android SDK**, with `local.properties` pointing at it. Android Studio writes this
   file itself on first open; it is gitignored and machine-specific, so never copy it
   between machines.
3. **Node and npm**, then `cd server && npm install`.
4. **Cloudflare access**: `npx wrangler login`. This is browser-interactive — run it
   yourself with `! npx wrangler login` rather than expecting an agent to do it.

### 3.2 Restore the secrets

```bash
# 0. Unpack the bundle if you are doing this by hand
tar -xzf lovie-secrets.tar.gz            # members: SECRETS.md, worker-secrets.env,
                                         #          google-services.json

# 1. Firebase Android config
cp google-services.json app/google-services.json

# 2. The three Worker secrets (each prompts for the value on stdin)
cd server
npx wrangler secret put FCM_SERVICE_ACCOUNT   # paste the whole service-account JSON
npx wrangler secret put ENROLL_CODE_1
npx wrangler secret put ENROLL_CODE_2
```

### 3.3 Verify before trusting it

```bash
cd server && npm test                      # expect 67 passed across 10 files
cd .. && ./gradlew :app:testDebugUnitTest  # expect 40 tests, 0 failures
npx wrangler secret list --cwd server      # expect exactly the three names above
```

A green test run does **not** prove the secrets are right — the suites do not call
Firebase. The real check is enrolling a phone and sending one message.

### 3.4 What the bundle holds

| Member | What it is | Recoverable without it? |
|---|---|---|
| `worker-secrets.env` | The three Cloudflare secrets | Only by regenerating — see §4 |
| `google-services.json` | Firebase Android config | Yes, re-download from the Console |
| `device-tokens.env` | The two phones' bearer tokens | Yes, by re-enrolling |
| `SECRETS.md` | This file, so the runbook travels with the bundle | — |

### 3.5 Encrypt it before it leaves this machine

The tarball is `chmod 600` and gitignored, which protects it here and nowhere
else. Encrypt it before putting it in cloud storage, and prefer a password
manager attachment over a plain file share:

```bash
gpg --symmetric --cipher-algo AES256 lovie-secrets.tar.gz   # prompts for a passphrase
shred -u lovie-secrets.tar.gz                               # only after verifying the .gpg opens
```

Verify before deleting the plaintext — decrypt it once and check the member list:

```bash
gpg --decrypt lovie-secrets.tar.gz.gpg | tar -tz
```

Put the passphrase in your password manager. A backup you cannot open is not a
backup.

---

## 4. If you lost the backup entirely

Nothing here is unrecoverable. None of it is a one-time-issue value.

| Secret | How to get a working one again |
|---|---|
| `FCM_SERVICE_ACCOUNT` | Firebase Console → Project settings → Service accounts → **Generate new private key**. Downloads a fresh JSON. Old keys keep working until you revoke them, so rotate deliberately. |
| `ENROLL_CODE_1` / `ENROLL_CODE_2` | You choose these. Pick new ones and `wrangler secret put` them. **Both phones must re-enrol afterwards**, because the old codes stop working. |
| `app/google-services.json` | Firebase Console → Project settings → Your apps → Android app → **google-services.json**. Re-downloadable at any time. |
| `local.properties` | Delete it and reopen the project in Android Studio. |

---

## 5. Data, which is a different problem

The D1 database holds the `devices` table — the bearer tokens each phone
authenticates with — and the `sends` table. That is **live data, not configuration**,
and no amount of repo hygiene backs it up.

```bash
cd server
npx wrangler d1 export love-button --remote --output ../lovie-d1-backup.sql
```

Losing D1 does not lose access to anything permanently: both phones simply re-enrol
with the enrolment codes and receive new tokens. The message history is the part that
would actually be gone.

`lovie-d1-backup.sql` is gitignored. It contains live bearer tokens — treat that file
exactly as carefully as the secrets in §1.

---

## 6. Audit trail

Checked on 2026-08-26, against full history rather than the working tree:

- No file matching `google-services.json`, `.dev.vars`, `.env`, `*.jks`, `*.keystore`,
  `local.properties`, or `service-account*.json` has ever been added in any commit on
  any branch. The only match is `app/google-services.json.example`, which is a
  placeholder template.
- No Google API key (`AIza…`) appears anywhere in history.
- The only long hex strings committed are gradle cache paths, git commit SHAs, the
  KV namespace id, and `ba7816bf…`, which is the published SHA-256 test vector for
  the string `"abc"` in `server/test/crypto.test.ts`.
- `.gitignore` is tracked and covers all four items in §1.

If you ever suspect a leak, the fastest honest check is:

```bash
git log --all --pretty=format: --name-only --diff-filter=A | sort -u \
  | grep -Ei "google-services|\.dev\.vars|\.env|keystore|\.jks|local\.properties|service-account"
```

Anything other than the `.example` line is a finding.
