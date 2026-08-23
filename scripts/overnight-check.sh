#!/usr/bin/env bash
# Task 10 — the overnight gate.
#
# Run this FIRST THING, before unlocking or opening anything on either phone.
# It sends as phone A without launching the app there, because opening an app
# is exactly the thing the test is trying not to do: a phone that was killed
# overnight comes back to life the moment you touch it, which would hide the
# failure this gate exists to catch.
#
# Requires phone A reachable over adb (leave it on the cable overnight).

set -euo pipefail

PHONE_A="${PHONE_A:-923262ff}"
BASE_URL="$(sed -n 's/^apiBaseUrl=//p' "$(dirname "$0")/../local.properties")"
MSG_ID="${1:-1}"

echo "== phone A reachable?"
adb -s "$PHONE_A" get-state >/dev/null || { echo "FAIL: phone A not on adb"; exit 1; }

echo "== reading phone A's auth token (never printed)"
TOKEN="$(adb -s "$PHONE_A" shell run-as com.lovebutton.app \
    cat files/datastore/love_button.preferences_pb 2>/dev/null \
  | strings | tr -d '\r' | grep -oE '[0-9a-f]{64}' | head -1)"
[ -n "$TOKEN" ] || { echo "FAIL: no auth token — is phone A still enrolled?"; exit 1; }

echo "== sending msg_id=$MSG_ID"
curl -sS -w '\nHTTP %{http_code}\n' -X POST "$BASE_URL/v1/send" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"msg_id\":$MSG_ID}"

echo
echo "== did phone B buzz? If yes, the night passed. If no, read on."
echo
echo "-- last 3 sends recorded by the Worker (a row here means the server did its job)"
( cd "$(dirname "$0")/../server" && npx --no-install wrangler d1 execute love-button --remote --json \
    --command "SELECT from_person, to_person, msg_id, sent_at FROM sends ORDER BY sent_at DESC LIMIT 3" \
    2>/dev/null | python3 -c 'import sys,json;[print(" ",r) for r in json.load(sys.stdin)[0]["results"]]' )

echo
echo "-- devices still enrolled (a MISSING row means FCM called the token dead"
echo "   and the Worker deleted it — that phone must enrol again)"
( cd "$(dirname "$0")/../server" && npx --no-install wrangler d1 execute love-button --remote --json \
    --command "SELECT person, label, updated_at FROM devices ORDER BY person" \
    2>/dev/null | python3 -c 'import sys,json;[print(" ",r) for r in json.load(sys.stdin)[0]["results"]]' )
