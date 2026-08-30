#!/usr/bin/env bash
#
# Restores a bundle made by secrets-backup.sh onto a fresh clone: puts
# app/google-services.json back and pushes the three Worker secrets to
# Cloudflare.
#
# Requires `npx wrangler login` to have been run first — that step is
# browser-interactive and cannot be scripted.
#
# Usage:
#   ./scripts/secrets-restore.sh lovie-secrets.tar.gz
#
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "usage: $0 <lovie-secrets.tar.gz>" >&2
    exit 2
fi

BUNDLE="$1"
[ -f "$BUNDLE" ] || { echo "no such bundle: $BUNDLE" >&2; exit 2; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

tar -xzf "$BUNDLE" -C "$STAGE"

# --- 1. Firebase Android config -------------------------------------------
if [ -f "$STAGE/google-services.json" ]; then
    if [ -f "$REPO_ROOT/app/google-services.json" ]; then
        echo "app/google-services.json already exists — leaving it alone."
    else
        cp "$STAGE/google-services.json" "$REPO_ROOT/app/google-services.json"
        echo "Restored app/google-services.json"
    fi
else
    echo "WARNING: bundle has no google-services.json; the Android build will fail."
fi

# --- 2. Worker secrets ----------------------------------------------------
# The release signing key. Restored before anything else needs it, and never
# over an existing one: a keystore already sitting here is the live signing
# identity, and replacing it with an older copy would silently make every future
# build uninstallable over what is on her phone.
for f in love-button-release.jks keystore.properties; do
    if [ -f "$STAGE/$f" ]; then
        if [ -f "$REPO_ROOT/$f" ]; then
            echo "$f already exists — leaving it alone."
        else
            cp "$STAGE/$f" "$REPO_ROOT/$f"
            chmod 600 "$REPO_ROOT/$f"
            echo "Restored $f"
        fi
    else
        echo "WARNING: bundle has no $f; you will not be able to build an update"
        echo "         that installs over the copy already on her phone."
    fi
done

ENV_FILE="$STAGE/worker-secrets.env"
[ -f "$ENV_FILE" ] || { echo "bundle has no worker-secrets.env" >&2; exit 1; }

if ! npx --prefix "$REPO_ROOT/server" wrangler whoami >/dev/null 2>&1; then
    echo
    echo "Not logged in to Cloudflare. Run this yourself first, then re-run:"
    echo "  ! npx wrangler login"
    exit 1
fi

pushed=0
for KEY in FCM_SERVICE_ACCOUNT ENROLL_CODE_1 ENROLL_CODE_2; do
    # Read the raw value without letting the shell expand or log it.
    VALUE="$(grep -E "^${KEY}=" "$ENV_FILE" | head -1 | cut -d= -f2-)"
    if [ -z "$VALUE" ]; then
        echo "  [skip] $KEY is blank in the bundle"
        continue
    fi
    # Piped on stdin so the value never appears in argv or shell history.
    printf '%s' "$VALUE" | (cd "$REPO_ROOT/server" && npx wrangler secret put "$KEY") >/dev/null
    echo "  [put]  $KEY"
    pushed=$((pushed + 1))
done

echo
echo "Pushed $pushed secret(s). Verifying names against Cloudflare:"
(cd "$REPO_ROOT/server" && npx wrangler secret list)

cat <<'NEXT'

Next:
  cd server && npm install && npm test        # expect 67 passed
  ./gradlew :app:testDebugUnitTest            # expect 40 tests, 0 failures

A green suite does NOT prove the secrets are correct — the tests never call
Firebase. The real check is enrolling a phone and sending one message.
NEXT
