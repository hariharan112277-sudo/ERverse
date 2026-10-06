#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Downloads the two Java jars ERverse needs at run time.
#   sqlite-jdbc          - JDBC driver for the DatabaseManager persistence layer
#   junit-platform-console - runs the JUnit 5 suite without a build tool
#
# They are not committed to git (see .gitignore) so the repository stays small.
#
#   ./scripts/fetch-deps.sh
# ---------------------------------------------------------------------------
set -euo pipefail

SQLITE_VERSION="3.46.1.3"
JUNIT_VERSION="1.11.3"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="$ROOT/lib"

mkdir -p "$LIB"

MAVEN="https://repo1.maven.org/maven2"
SQLITE_URL="$MAVEN/org/xerial/sqlite-jdbc/$SQLITE_VERSION/sqlite-jdbc-$SQLITE_VERSION.jar"
JUNIT_URL="$MAVEN/org/junit/platform/junit-platform-console-standalone/$JUNIT_VERSION/junit-platform-console-standalone-$JUNIT_VERSION.jar"

download() {
  local url="$1" target="$2"
  if [[ -f "$target" ]]; then
    echo "  already present: $(basename "$target")"
    return
  fi
  echo "  downloading $(basename "$target") ..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$target"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$url" -O "$target"
  else
    echo "ERROR: neither curl nor wget is available." >&2
    exit 1
  fi
}

echo "Fetching Java dependencies into $LIB"
download "$SQLITE_URL" "$LIB/sqlite-jdbc-$SQLITE_VERSION.jar"
download "$JUNIT_URL"  "$LIB/junit-platform-console-standalone-$JUNIT_VERSION.jar"

echo
echo "Done. SQLite persistence and the JUnit suite are now available:"
echo "  java -cp \"bin:lib/sqlite-jdbc-$SQLITE_VERSION.jar\" erverse.Main"
echo "  ./scripts/run-tests.sh"
