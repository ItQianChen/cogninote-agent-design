#!/usr/bin/env bash
set -euo pipefail

SKIP_TESTS=false
JDK_HOME_ARG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-tests)
      SKIP_TESTS=true
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

JAR_NAME="cogninote-agent-design.jar"
JAR_PATH="$PROJECT_ROOT/target/$JAR_NAME"
DESKTOP_BACKEND_DIR="$PROJECT_ROOT/target/desktop-macos/backend"
BACKEND_IMAGE_DIR="$DESKTOP_BACKEND_DIR/CogniNoteBackend.app"
JPACKAGE_INPUT_DIR="$PROJECT_ROOT/target/desktop-macos/jpackage-input"

if [[ "$SKIP_TESTS" == "true" ]]; then
  mvn -Pwith-frontend package -DskipTests
else
  mvn -Pwith-frontend package
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Backend Jar was not generated: $JAR_PATH" >&2
  exit 1
fi

rm -rf "$BACKEND_IMAGE_DIR" "$JPACKAGE_INPUT_DIR"
mkdir -p "$DESKTOP_BACKEND_DIR" "$JPACKAGE_INPUT_DIR"
cp "$JAR_PATH" "$JPACKAGE_INPUT_DIR/$JAR_NAME"

"$JAVA_HOME/bin/jpackage" \
  --type app-image \
  --name CogniNoteBackend \
  --input "$JPACKAGE_INPUT_DIR" \
  --main-jar "$JAR_NAME" \
  --dest "$DESKTOP_BACKEND_DIR" \
  --java-options "--enable-native-access=ALL-UNNAMED"

BACKEND_LAUNCHER="$BACKEND_IMAGE_DIR/Contents/MacOS/CogniNoteBackend"
if [[ ! -x "$BACKEND_LAUNCHER" ]]; then
  echo "jpackage did not generate backend launcher: $BACKEND_LAUNCHER" >&2
  exit 1
fi

echo "macOS backend app-image generated: $BACKEND_IMAGE_DIR"
