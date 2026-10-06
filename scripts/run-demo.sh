#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# One-command viva demo: builds the Java console app, then starts the Express
# API and dashboard.
#
#   ./scripts/run-demo.sh
#
# Terminal 1 style usage. The script prints both entry points:
#   - web dashboard on http://localhost:3000
#   - instructions for the console client
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# --- Java version guard -----------------------------------------------------
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
  echo "       Windows: set JAVA_HOME in System Properties > Environment Variables." >&2
  exit 1
fi

if [[ ! -f "lib/sqlite-jdbc-3.46.1.3.jar" ]]; then
  ./scripts/fetch-deps.sh
fi

echo "==> Compiling the Java triage engine"
mkdir -p bin
javac --release 17 -encoding UTF-8 -d bin src/erverse/*.java

if [[ ! -d node_modules ]]; then
  echo "==> Installing Node dependencies"
  npm install --no-audit --no-fund
fi

cat <<'BANNER'

=================================================================
  ERverse is ready.
=================================================================
  WEB DASHBOARD   http://localhost:3000
                  (metric cards, live queue, bed grid, roster,
                   ambulance tracker, registration modal, CSV export)

  JAVA CONSOLE    open a second terminal and run:
                    java -cp "bin:lib/sqlite-jdbc-3.46.1.3.jar" erverse.Main
                  then choose menu 13 to load the demo surge.

  TESTS           ./scripts/run-tests.sh          (27 JUnit tests)
                  node scripts/smoke-test.js      (31 REST checks)
=================================================================

BANNER

node server/app.js
