#!/usr/bin/env bash
# Task 10 — the overnight gate.
#
# Tests BOTH phones as receivers. The sending is done by this machine, not by
# either handset, so neither phone has to be woken, unlocked or opened. That
# matters: a phone whose app was killed overnight revives the instant you touch
# it, which would hide the exact failure this gate exists to catch.
#
#   Tonight:   ./scripts/overnight-check.sh prime
#   Morning:   ./scripts/overnight-check.sh
#
# `prime` caches both auth tokens while the phones are still on adb, so the
# morning run needs no adb at all — phone B is on wireless debugging and will
# very likely have dropped off by then.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${LB_TOKEN_CACHE:-$HOME/secrets/love-button-tokens.env}"
PHONE_A="${PHONE_A:-923262ff}"
PHONE_B="${PHONE_B:-192.168.10.30:39751}"
BASE_URL="$(sed -n 's/^apiBaseUrl=//p' "$ROOT/local.properties")"

read_token() {  # $1 = adb serial
  adb -s "$1" shell run-as com.lovebutton.app \
      cat files/datastore/love_button.preferences_pb 2>/dev/null \
    | strings | tr -d '\r' | grep -oE '[0-9a-f]{64}' | head -1
}

if [ "${1:-}" = "prime" ]; then
  mkdir -p "$(dirname "$CACHE")"
  ta="$(read_token "$PHONE_A")"; tb="$(read_token "$PHONE_B")"
  [ -n "$ta" ] || { echo "FAIL: no token on phone A — still enrolled?"; exit 1; }
  [ -n "$tb" ] || { echo "FAIL: no token on phone B — still enrolled?"; exit 1; }
  umask 077
  printf 'TOKEN_A=%s\nTOKEN_B=%s\n' "$ta" "$tb" > "$CACHE"
  echo "cached both tokens -> $CACHE (mode 600, outside the repo)"
  echo "leave the phones alone now. run this script with no arguments in the morning."
  exit 0
fi

if [ -f "$CACHE" ]; then . "$CACHE"; else
  echo "no token cache; falling back to adb (did you forget 'prime'?)"
  TOKEN_A="$(read_token "$PHONE_A")"; TOKEN_B="$(read_token "$PHONE_B")"
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
