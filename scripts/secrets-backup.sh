#!/usr/bin/env bash
#
# Bundles everything this project needs that is NOT in the repo into one
# encrypted-at-rest-by-you tarball, so a second machine can be brought up from a
# clone plus this file.
#
# The important constraint: Cloudflare Worker secrets are WRITE-ONLY. `wrangler
# secret list` returns names, never values, and no flag changes that. So this
# script cannot read FCM_SERVICE_ACCOUNT or the enrolment codes out of
# Cloudflare — the first run writes you a template to fill in by hand, once,
# from the original sources. See docs/SECRETS.md §4 for where those come from.
#
# Usage:
#   ./scripts/secrets-backup.sh [output.tar.gz]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$REPO_ROOT/lovie-secrets.tar.gz}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

echo "Staging secrets from $REPO_ROOT"
echo

# --- 1. Files that exist locally and can simply be copied ------------------
copied=0
if [ -f "$REPO_ROOT/app/google-services.json" ]; then
    cp "$REPO_ROOT/app/google-services.json" "$STAGE/google-services.json"
    echo "  [copied]  app/google-services.json"
    copied=$((copied + 1))
else
    echo "  [MISSING] app/google-services.json — re-download it from the Firebase"
    echo "            Console before this backup is complete (docs/SECRETS.md §4)."
fi

# local.properties is deliberately NOT backed up: it is an absolute path to this
# machine's Android SDK and is actively wrong on any other machine.

# The release keystore is the one secret here that cannot be regenerated. Every
# other value in this bundle can be reissued from its source — a new service
# account key, new enrolment codes, a fresh google-services.json. This one
# cannot: Android refuses an install whose signature changed, so losing it means
# the app on her phone can never be updated again, only uninstalled and
# re-enrolled. It is backed up first for that reason (spec §4.1).
for f in love-button-release.jks keystore.properties; do
    if [ -f "$REPO_ROOT/$f" ]; then
        cp "$REPO_ROOT/$f" "$STAGE/$f"
        echo "  [copied]  $f"
        copied=$((copied + 1))
    else
        echo "  [MISSING] $f — the release signing key. Without it this bundle"
        echo "            cannot rebuild an installable update."
    fi
done

# --- 2. Worker secrets, which Cloudflare will not hand back ----------------
# Reuse the values from a previous backup if one is sitting next to the output,
# so re-running this does not mean re-pasting a service-account key every time.
# Precedence matters. An edited file sitting in the working directory is the
# user having just filled in the blanks, and must win over the stale copy still
# inside the previous tarball — reading the tarball first would silently discard
# the edit they were told to make.
PREV_ENV=""
if [ -f "$REPO_ROOT/worker-secrets.env" ]; then
    cp "$REPO_ROOT/worker-secrets.env" "$STAGE/worker-secrets.env"
    PREV_ENV="edited"
    echo "  [folded]  worker-secrets.env from the repo root"
elif [ -f "./worker-secrets.env" ]; then
    cp "./worker-secrets.env" "$STAGE/worker-secrets.env"
    PREV_ENV="edited"
    echo "  [folded]  worker-secrets.env from $(pwd)"
elif [ -f "$OUT" ] && tar -tzf "$OUT" 2>/dev/null | grep -q "worker-secrets.env"; then
    # Wildcard so this also reads bundles written before the ./ prefix was fixed.
    tar -xzf "$OUT" -C "$STAGE" --wildcards --no-anchored 'worker-secrets.env' 2>/dev/null \
        && PREV_ENV="bundle"
    echo "  [reused]  worker-secrets.env (from the existing $(basename "$OUT"))"
elif [ -f "$OUT.gpg" ]; then
    # The encrypted bundle is the copy that is actually KEPT — the plaintext one
    # is shredded after every run, so on any machine that has done this properly
    # it is the only source left. Reading only the plaintext tarball is how a
    # complete backup silently degrades: a stale or blank plaintext copy sitting
    # in the working directory wins, the three Worker secrets come out empty, and
    # the freshly written bundle can no longer bring up a machine. Prompts for
    # the passphrase, which is the correct cost.
    echo "  [reused]  worker-secrets.env from $(basename "$OUT.gpg") — passphrase needed"
    if gpg --decrypt "$OUT.gpg" 2>/dev/null \
        | tar -xz -C "$STAGE" --wildcards --no-anchored 'worker-secrets.env' 2>/dev/null; then
        PREV_ENV="gpg"
    else
        echo "  [WARNING] could not read $(basename "$OUT.gpg"); falling back to a blank template"
    fi
fi

if [ -n "$PREV_ENV" ]; then
    :
else
    cat > "$STAGE/worker-secrets.env" <<'TEMPLATE'
# Cloudflare Worker secrets for love-button.
#
# Cloudflare cannot export these, so fill them in ONCE from the original source
# and keep this bundle somewhere you control. docs/SECRETS.md §4 says where each
# one comes from if you no longer have it.
#
# FCM_SERVICE_ACCOUNT must be the ENTIRE service-account JSON on a single line.
# Get it as one line with:  jq -c . < service-account.json

FCM_SERVICE_ACCOUNT=
ENROLL_CODE_1=
ENROLL_CODE_2=
TEMPLATE
    echo "  [template] worker-secrets.env — you must fill this in by hand"
fi

# Warn about blanks without ever echoing a value.
missing=""
while IFS='=' read -r key value; do
    case "$key" in
        FCM_SERVICE_ACCOUNT|ENROLL_CODE_1|ENROLL_CODE_2)
            [ -z "$value" ] && missing="$missing $key"
            ;;
    esac
done < <(grep -E '^[A-Z0-9_]+=' "$STAGE/worker-secrets.env" || true)

# --- 3. Device bearer tokens (optional) ------------------------------------
# Not configuration — these are issued by /v1/enroll and recoverable by simply
# re-enrolling. They are carried anyway because they are what the spec's curl
# examples need, and re-enrolling to get one back is a chore. Same precedence
# rule as above: a loose edited file beats the copy inside the old bundle.
if [ -f "$REPO_ROOT/device-tokens.env" ]; then
    cp "$REPO_ROOT/device-tokens.env" "$STAGE/device-tokens.env"
    echo "  [folded]  device-tokens.env from the repo root"
elif [ -f "$OUT" ] && tar -tzf "$OUT" 2>/dev/null | grep -q "device-tokens.env"; then
    tar -xzf "$OUT" -C "$STAGE" --wildcards --no-anchored 'device-tokens.env' 2>/dev/null \
        && echo "  [reused]  device-tokens.env (from the existing $(basename "$OUT"))"
elif [ -f "$OUT.gpg" ]; then
    gpg --decrypt "$OUT.gpg" 2>/dev/null \
        | tar -xz -C "$STAGE" --wildcards --no-anchored 'device-tokens.env' 2>/dev/null \
        && echo "  [reused]  device-tokens.env (from $(basename "$OUT.gpg"))"
fi

cp "$REPO_ROOT/docs/SECRETS.md" "$STAGE/SECRETS.md" 2>/dev/null || true

# Explicit basenames rather than ".", so members are stored as
# `worker-secrets.env` and not `./worker-secrets.env`. With the ./ prefix,
# `tar -xzf bundle worker-secrets.env` fails with "Not found in archive" —
# which is precisely the command this script tells you to run.
MEMBERS=()
# Explicit list rather than the whole staging dir, so a stray file cannot be
# swept in. Anything added to the staging step above must be named here too.
for f in SECRETS.md worker-secrets.env google-services.json device-tokens.env \
         love-button-release.jks keystore.properties; do
    [ -f "$STAGE/$f" ] && MEMBERS+=("$f")
done
tar -czf "$OUT" -C "$STAGE" "${MEMBERS[@]}"
chmod 600 "$OUT"

echo
echo "Wrote $OUT (chmod 600)"
echo

if [ -n "$missing" ]; then
    echo "INCOMPLETE — these are still blank:$missing"
    echo
    echo "  1. cd $REPO_ROOT && tar -xzf $OUT worker-secrets.env"
    echo "  2. fill in the blanks in ./worker-secrets.env"
    echo "  3. re-run this script — it folds that file in and then you can delete it"
    echo "     (worker-secrets.env is gitignored, but do not leave it lying around)"
    echo
    echo "This bundle will NOT bring up a new machine until they are filled."
    exit 1
fi

echo "Complete. All three Worker secrets are present."
if [ "$PREV_ENV" = "edited" ]; then
    echo
    echo "Now delete the loose copy you filled in — the tarball has it:"
    echo "  shred -u worker-secrets.env 2>/dev/null || rm -f worker-secrets.env"
fi
echo "Restore on another machine with:"
echo "  ./scripts/secrets-restore.sh $(basename "$OUT")"
