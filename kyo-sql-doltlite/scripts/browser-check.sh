#!/usr/bin/env bash
#
# Runs the DoltLite WebAssembly engine in a real browser and reports what it did: that the module loads and runs
# there at all, and that a database can outlive the page. The pages call wasm.exports directly, the way
# SqliteWasmFacade does, rather than loading the linked Scala.js output.
#
# Each page reports by fetching /done?report=..., which lands in the server's access log. Reading the DOM back
# instead would mean waiting for Chrome to exit, and Chrome does not exit on its virtual-time budget while a page
# holds a WebAssembly module.
#
# Needs a Chrome (set CHROME to override discovery) and a network fetch of the npm package, so it is not wired
# into sbt. Run it when the transport or the pinned engine version changes.
#
# Usage: kyo-sql-doltlite/scripts/browser-check.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$HERE/browser-check"
PORT="${PORT:-8731}"
# Generous relative to the few seconds a page needs, since a cold Chrome start can be slow.
DEADLINE="${DEADLINE:-120}"

# Must match the version staged for the native transport, so the browser runs the same engine.
VERSION="0.50.10"

CHROME="${CHROME:-}"
if [ -z "$CHROME" ]; then
  for candidate in \
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    "$(command -v google-chrome || true)" \
    "$(command -v chromium || true)"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then CHROME="$candidate"; break; fi
  done
fi
if [ -z "$CHROME" ]; then
  echo "No Chrome found. Set CHROME to an executable." >&2
  exit 1
fi

cd "$WORK"
if [ ! -d "node_modules/@dolthub/doltlite-wasm" ]; then
  echo "installing @dolthub/doltlite-wasm@$VERSION ..."
  npm install "@dolthub/doltlite-wasm@$VERSION" --no-audit --no-fund --silent
fi

: > access.log
# A custom server: it sends the cross-origin isolation headers OPFS needs, pins the .mjs and .wasm MIME
# types a browser insists on for modules, and defeats conditional requests so a warm cache cannot answer
# 304 and starve the page of its .wasm.
python3 serve.py "$PORT" > access.log 2>&1 &
SERVER=$!
# The profile is this script's own, so the cleanup cannot reach a browser the user is running.
trap 'kill $SERVER 2>/dev/null || true; pkill -f "$WORK/chrome-profile" 2>/dev/null || true' EXIT
sleep 1

# Loads one page, waits for its /done report to appear in the access log, and prints the report it carried.
run_page() {
  local page="$1" marker="$2"
  local before
  before="$(grep -ac 'GET /done?report=' access.log 2>/dev/null || true)"
  before="${before:-0}"
  # --headless=new and NO --dump-dom. The old mode needs --dump-dom to render at all, and that dumps at the load
  # event and exits, so a page whose work is asynchronous reports PENDING forever. New headless keeps running
  # like a real browser; the loop below kills it once its report lands in the access log.
  "$CHROME" --headless=new --disable-gpu --no-sandbox \
    --user-data-dir="$WORK/chrome-profile" \
    --disable-background-timer-throttling --disable-renderer-backgrounding \
    "http://127.0.0.1:$PORT/$page?run=$marker" >/dev/null 2>&1 &
  local pid=$!
  local waited=0
  # Waits for the report COUNT to grow rather than truncating the log between pages. Truncating leaves the server
  # writing at its old offset, which pads the file with NUL bytes and makes grep treat it as binary.
  while [ "$waited" -lt "$DEADLINE" ]; do
    local now
    now="$(grep -ac 'GET /done?report=' access.log 2>/dev/null || true)"
    if [ "${now:-0}" -gt "$before" ]; then break; fi
    sleep 1
    waited=$((waited + 1))
  done
  kill "$pid" 2>/dev/null || true
  pkill -f "$WORK/chrome-profile" 2>/dev/null || true
  local raw
  raw="$(grep -ao 'GET /done?report=[^ ]*' access.log | tail -1 | sed 's|GET /done?report=||')"
  python3 -c "import sys,urllib.parse; print(urllib.parse.unquote_plus(sys.argv[1]))" "${raw:-}" 2>/dev/null || echo ""
}

STATUS=0

ENGINE="$(run_page page.html engine)"
echo "engine:      ${ENGINE:-<no report>}"
case "$ENGINE" in *"RESULT OK"*) ;; *) STATUS=1 ;; esac

# Reported separately: a build with no OPFS VFS says UNAVAILABLE, which is not a failure of the engine check.
PERSIST="$(run_page page-opfs.html persist)"
echo "persistence: ${PERSIST:-<no report>}"
case "$PERSIST" in
  *"RESULT OK"*)          ;;
  *"RESULT UNAVAILABLE"*) echo "browser-check: persistence not exercised; the report above says why" ;;
  *)                      STATUS=1 ;;
esac

if [ "$STATUS" -eq 0 ]; then echo "browser-check: OK"; else echo "browser-check: FAILED" >&2; fi
exit "$STATUS"
