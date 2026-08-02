#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <app-path> <dmg-path> <expected-version>" >&2
  exit 2
fi

APP_PATH="$1"
DMG_PATH="$2"
EXPECTED_VERSION="$3"
TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/cogninote-desktop-smoke.XXXXXX")"
MOUNT_PATH="$TEMP_ROOT/mounted"
INSTALLED_APP="$TEMP_ROOT/Applications/CogniNote.app"
BACKEND_PID=""

cleanup() {
  if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi

  # A DMG mount is read-only; remove the temporary tree only after detaching it.
  if mount | grep -Fq " on $MOUNT_PATH "; then
    detached=false
    for _ in 1 2 3; do
      if hdiutil detach "$MOUNT_PATH" -quiet; then
        detached=true
        break
      fi
      sleep 1
    done
    if [[ "$detached" != true ]] && hdiutil detach "$MOUNT_PATH" -force; then
      detached=true
    fi
    if [[ "$detached" != true ]] || mount | grep -Fq " on $MOUNT_PATH "; then
      echo "Failed to detach DMG mount; preserving smoke directory: $TEMP_ROOT" >&2
      return 1
    fi
  fi
  rm -rf "$TEMP_ROOT"
}
trap cleanup EXIT

if [[ ! -d "$APP_PATH" || ! -f "$DMG_PATH" ]]; then
  echo "macOS app or DMG artifact is missing." >&2
  exit 1
fi

BACKEND_APP="$APP_PATH/Contents/Resources/backend/CogniNoteBackend.app"
BACKEND_LAUNCHER="$BACKEND_APP/Contents/MacOS/CogniNoteBackend"
if [[ ! -x "$BACKEND_LAUNCHER" ]]; then
  echo "Bundled backend launcher is missing: $BACKEND_LAUNCHER" >&2
  exit 1
fi
if find "$APP_PATH" -type f \( -name '.env' -o -name '*.db' -o -name '*.log' \) -print -quit | grep -q .; then
  echo "macOS app contains test or user data." >&2
  exit 1
fi

mkdir -p "$MOUNT_PATH" "$(dirname "$INSTALLED_APP")" "$TEMP_ROOT/home" "$TEMP_ROOT/storage"
hdiutil attach "$DMG_PATH" -mountpoint "$MOUNT_PATH" -nobrowse -quiet
MOUNTED_APP="$(find "$MOUNT_PATH" -maxdepth 1 -name '*.app' -type d | head -n 1)"
if [[ -z "$MOUNTED_APP" ]]; then
  echo "DMG does not contain an app bundle." >&2
  exit 1
fi
ditto "$MOUNTED_APP" "$INSTALLED_APP"

PORT="$((20000 + RANDOM % 20000))"
HOME="$TEMP_ROOT/home" TMPDIR="$TEMP_ROOT" \
  "$INSTALLED_APP/Contents/Resources/backend/CogniNoteBackend.app/Contents/MacOS/CogniNoteBackend" \
  "--server.port=$PORT" \
  --server.address=127.0.0.1 \
  "--app.storage.base-dir=$TEMP_ROOT/storage" \
  "--app.storage.database-path=$TEMP_ROOT/storage/data/cogninote.db" \
  --app.desktop.enabled=false \
  >"$TEMP_ROOT/backend.stdout.log" 2>"$TEMP_ROOT/backend.stderr.log" &
BACKEND_PID="$!"

for _ in $(seq 1 240); do
  if STATUS_JSON="$(curl -fsS --max-time 2 "http://127.0.0.1:$PORT/api/system/status" 2>/dev/null)"; then
    break
  fi
  sleep 0.25
done
if [[ -z "${STATUS_JSON:-}" ]]; then
  echo "Bundled macOS backend did not become ready." >&2
  exit 1
fi
ACTUAL_VERSION="$(printf '%s' "$STATUS_JSON" | plutil -extract data.version raw -o - -)"
if [[ "$ACTUAL_VERSION" != "$EXPECTED_VERSION" ]]; then
  echo "Backend version mismatch. Expected $EXPECTED_VERSION, got $ACTUAL_VERSION." >&2
  exit 1
fi

INDEX_HTML="$(curl -fsS "http://127.0.0.1:$PORT/")"
ASSET_PATH="$(printf '%s' "$INDEX_HTML" | grep -Eo '(src|href)="/assets/[^"]+"' | head -n 1 | cut -d '"' -f 2)"
if [[ -z "$ASSET_PATH" ]]; then
  echo "Bundled frontend does not reference an /assets resource." >&2
  exit 1
fi
curl -fsS --max-time 10 "http://127.0.0.1:$PORT$ASSET_PATH" >/dev/null

kill "$BACKEND_PID"
wait "$BACKEND_PID" || true
BACKEND_PID=""
echo "macOS desktop package smoke passed for version $EXPECTED_VERSION."
