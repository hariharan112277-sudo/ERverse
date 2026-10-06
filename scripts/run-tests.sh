#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Builds the Java core and runs the full JUnit 5 suite (27 tests).
#
#   ./scripts/run-tests.sh
#
# Covers TC-01..TC-04 plus the Week 4 concurrency regression and the JDBC
# persistence round-trip documented in docs/VERIFICATION.md.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# --- Java version guard -----------------------------------------------------
# Without this, an old JDK produces only "error: release version 17 not supported",
# which does not tell you what to do about it.
if ! command -v javac >/dev/null 2>&1; then
  echo "ERROR: javac was not found on PATH." >&2
  echo "       ERverse needs JDK 17 or newer: https://adoptium.net/temurin/releases/?version=17" >&2
  exit 1
fi
JAVAC_VERSION="$(javac -version 2>&1 | awk '{print $2}')"
JAVAC_MAJOR="${JAVAC_VERSION%%.*}"
if [[ "$JAVAC_MAJOR" == "1" ]]; then
  JAVAC_MAJOR="$(printf '%s' "$JAVAC_VERSION" | cut -d. -f2)"
fi
if (( JAVAC_MAJOR < 17 )); then
  echo "ERROR: ERverse needs JDK 17 or newer, but javac $JAVAC_VERSION is on your PATH." >&2
  echo "       Fix it with, for example:" >&2
  echo "         export JAVA_HOME=/path/to/jdk-21" >&2
  echo "         export PATH=\"\$JAVA_HOME/bin:\$PATH\"" >&2
  echo "       (Windows: set JAVA_HOME in System Properties > Environment Variables.)" >&2
  exit 1
fi

SQLITE_JAR="lib/sqlite-jdbc-3.46.1.3.jar"
JUNIT_JAR="lib/junit-platform-console-standalone-1.11.3.jar"

if [[ ! -f "$SQLITE_JAR" || ! -f "$JUNIT_JAR" ]]; then
  echo "Dependencies missing - running scripts/fetch-deps.sh first."
  ./scripts/fetch-deps.sh
fi

echo "==> Compiling src/erverse/*.java"
rm -rf bin testbin
mkdir -p bin testbin
javac --release 17 -Xlint:all -encoding UTF-8 -d bin src/erverse/*.java

echo "==> Compiling tests"
javac --release 17 -encoding UTF-8 -cp "bin:$JUNIT_JAR" -d testbin test/erverse/*.java

echo "==> Running JUnit 5 suite"
java -jar "$JUNIT_JAR" execute \
  --class-path "bin:testbin:$SQLITE_JAR" \
  --scan-class-path testbin \
  --details=tree

echo
echo "==> JUnit run complete. For the HTTP layer:"
echo "    node server/app.js          # terminal 1"
echo "    node scripts/smoke-test.js  # terminal 2"
