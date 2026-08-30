#!/usr/bin/env bash
# Task 10 — the overnight gate.
#
# Tests BOTH phones as receivers. The sending is done by this machine, not by
# either handset, so neither phone has to be woken, unlocked or opened. That
# matters: a phone whose app was killed overnight revives the instant you touch
# it, which would hide the exact failure this gate exists to catch.
#
#   Once:      ./scripts/overnight-check.sh prime
#   Any night: ./scripts/overnight-check.sh
#
# `prime` mints two SEND-ONLY devices, one per person, and caches their bearer
# tokens. It needs neither phone — not on adb, not awake, not present.
#
# It used to read each phone's own token off the handset with `run-as`. That
# stopped working at 1.0: a release APK is not debuggable, so `run-as` refuses,
# and it should refuse — the alternative is shipping a publicly downloadable
# build whose private data any adb session can read. A send-only device is the
# replacement. It holds a NULL fcm_token, so /v1/send skips it entirely and it
# can never steal a push from a real phone; it exists only to send AS a person.
#
# The two rows it creates are invisible from the phones, so they are labelled.
# To see or remove them:
#   wrangler d1 execute love-button --remote \
#     --command "SELECT id, person, label FROM devices"

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${LB_TOKEN_CACHE:-$HOME/secrets/love-button-tokens.env}"
# No phone serials any more: this script never touches a handset. That is the
# point of it — a phone you connect to is a phone you have woken.
BASE_URL="$(sed -n 's/^apiBaseUrl=//p' "$ROOT/local.properties")"

mint() {  # $1 = enrolment code — mints one send-only device, prints its token
  curl -sS -X POST "$BASE_URL/v1/enroll" \
    -H 'Content-Type: application/json' \
    -d "{\"code\":\"$1\",\"label\":\"overnight-check · laptop\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("auth_token",""))'
}

if [ "${1:-}" = "prime" ]; then
  BUNDLE="$ROOT/lovie-secrets.tar.gz.gpg"
  [ -f "$BUNDLE" ] || { echo "FAIL: no $BUNDLE to read the enrolment codes from"; exit 1; }

  echo "Reading the two enrolment codes from the encrypted bundle..."
  ENV_TEXT="$(gpg --decrypt "$BUNDLE" 2>/dev/null | tar -xzO worker-secrets.env)" \
    || { echo "FAIL: could not open $BUNDLE"; exit 1; }
  CODE_1="$(printf '%s\n' "$ENV_TEXT" | sed -n 's/^ENROLL_CODE_1=//p')"
  CODE_2="$(printf '%s\n' "$ENV_TEXT" | sed -n 's/^ENROLL_CODE_2=//p')"
  unset ENV_TEXT
  [ -n "$CODE_1" ] && [ -n "$CODE_2" ] || {
    echo "FAIL: the bundle's worker-secrets.env has no enrolment codes in it"; exit 1; }

  ta="$(mint "$CODE_1")"; tb="$(mint "$CODE_2")"
  unset CODE_1 CODE_2
  [ -n "$ta" ] || { echo "FAIL: could not enrol a send-only device for person 1"; exit 1; }
  [ -n "$tb" ] || { echo "FAIL: could not enrol a send-only device for person 2"; exit 1; }

  mkdir -p "$(dirname "$CACHE")"
  umask 077
  printf 'TOKEN_A=%s\nTOKEN_B=%s\n' "$ta" "$tb" > "$CACHE"
  echo "cached both tokens -> $CACHE (mode 600, outside the repo)"
  echo
  echo "These are send-only devices: they hold no FCM token, so they receive"
  echo "nothing and cannot take a push from either phone. Priming again mints"
  echo "two more — delete the old rows if you do."
  exit 0
fi

if [ -f "$CACHE" ]; then . "$CACHE"; else
  echo "FAIL: no token cache at $CACHE."
  echo "      Run './scripts/overnight-check.sh prime' once — it needs neither phone."
  exit 1
fi
[ -n "${TOKEN_A:-}" ] && [ -n "${TOKEN_B:-}" ] || { echo "FAIL: missing tokens"; exit 1; }

send() {  # $1 = label, $2 = bearer token, $3 = msg id
  printf '\n== %s\n' "$1"
  curl -sS -w '\nHTTP %{http_code}\n' -X POST "$BASE_URL/v1/send" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $2" \
    -d "{\"msg_id\":$3}"
}

send "A -> B   (does HER phone still receive?)" "$TOKEN_A" "${1:-1}"
sleep 3
send "B -> A   (does YOUR phone still receive?)" "$TOKEN_B" "${1:-1}"

cat <<'ASK'

== Both phones should have buzzed, without either being unlocked first.
   Either one staying silent is a failed night for THAT phone.

If one stayed silent, the rows below say which layer is at fault:
ASK

q() { ( cd "$ROOT/server" && npx --no-install wrangler d1 execute love-button --remote --json \
        --command "$1" 2>/dev/null \
      | python3 -c 'import sys,json;[print(" ",r) for r in json.load(sys.stdin)[0]["results"]]' ); }

echo
echo "-- last 4 sends (a row here means the Worker did its job; the phone is at fault)"
q "SELECT from_person, to_person, msg_id, sent_at FROM sends ORDER BY sent_at DESC LIMIT 4"
echo
echo "-- devices still enrolled (a MISSING row means FCM called that token dead and"
echo "   the Worker deleted it — that phone must enrol again)"
q "SELECT person, label, updated_at FROM devices ORDER BY person"
