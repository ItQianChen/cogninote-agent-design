#!/usr/bin/env bash
set -euo pipefail

SKIP_TESTS=false
JDK_HOME_ARG=""
SIGN_BUILD=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-tests)
      SKIP_TESTS=true
      shift
      ;;
    --sign)
      SIGN_BUILD=true
      shift
      ;;
    --jdk-home)
      JDK_HOME_ARG="${2:-}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -n "$JDK_HOME_ARG" ]]; then
  export JDK_HOME="$JDK_HOME_ARG"
fi

bash "$SCRIPT_DIR/verify-desktop-toolchain-macos.sh"

cd "$PROJECT_ROOT"

if [[ "$SKIP_TESTS" != "true" ]]; then
  mvn test
fi

bash "$SCRIPT_DIR/build-desktop-backend-macos.sh" --skip-tests

TAURI_DIR="$PROJECT_ROOT/cogniNote-agent-front/src-tauri"
TAURI_CONFIG="$TAURI_DIR/tauri.conf.json"
TAURI_MACOS_CONFIG="$TAURI_DIR/tauri.macos.conf.json"
TAURI_WINDOWS_CONFIG_BACKUP="$(mktemp "${TMPDIR:-/tmp}/cogninote-tauri-conf.XXXXXX.json")"

if [[ ! -f "$TAURI_MACOS_CONFIG" ]]; then
  echo "macOS Tauri config not found: $TAURI_MACOS_CONFIG" >&2
  exit 1
fi

cp "$TAURI_CONFIG" "$TAURI_WINDOWS_CONFIG_BACKUP"
restore_tauri_config() {
  if [[ -f "$TAURI_WINDOWS_CONFIG_BACKUP" ]]; then
    cp "$TAURI_WINDOWS_CONFIG_BACKUP" "$TAURI_CONFIG"
    rm -f "$TAURI_WINDOWS_CONFIG_BACKUP"
  fi
}
trap restore_tauri_config EXIT

# Tauri's --config option merges with tauri.conf.json. For a truly separate
# macOS bundle config, temporarily swap the active config during the build and
# restore the Windows config immediately afterwards.
cp "$TAURI_MACOS_CONFIG" "$TAURI_CONFIG"
if [[ "$SIGN_BUILD" == "true" ]]; then
  if [[ -z "${APPLE_SIGNING_IDENTITY:-}" ]]; then
    echo "APPLE_SIGNING_IDENTITY is required for signed macOS builds." >&2
    exit 1
  fi

  # The checked-in macOS config stays secret-free. During signed CI builds, write
  # the Developer ID identity only into the temporary active Tauri config that is
  # restored at process exit.
  node - "$TAURI_CONFIG" <<'NODE'
const fs = require('fs');
const configPath = process.argv[2];
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
config.bundle ??= {};
config.bundle.macOS ??= {};
config.bundle.macOS.signingIdentity = process.env.APPLE_SIGNING_IDENTITY;
if (process.env.APPLE_PROVIDER_SHORT_NAME) {
  config.bundle.macOS.providerShortName = process.env.APPLE_PROVIDER_SHORT_NAME;
}
fs.writeFileSync(configPath, `${JSON.stringify(config, null, 2)}\n`);
NODE

  npm --prefix cogniNote-agent-front run desktop:build:macos:signed
else
  npm --prefix cogniNote-agent-front run desktop:build:macos
fi

echo
echo "macOS desktop build finished. Check cogniNote-agent-front/src-tauri/target/release/bundle/."
