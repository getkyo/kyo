#!/bin/bash
# Take web + JavaFX screenshots for all registered UIs into the current session.
# Reads session name from kyo-ui/sessions/.current
# Usage: ./kyo-ui/take-screenshots.sh [ui1 ui2 ...]
# If no args, screenshots all UIs.

set -e
cd "$(dirname "$0")/.."

CURRENT="$(cat kyo-ui/sessions/.current 2>/dev/null)"
if [ -z "$CURRENT" ]; then
  echo "ERROR: No active session. Run start-session.sh first."
  exit 1
fi
SESSION_DIR="kyo-ui/sessions/$CURRENT"
mkdir -p "$SESSION_DIR/static"

ALL_UIS=$(sed -n 's/.*"\([a-z]*\)"[[:space:]]*->.*/\1/p' kyo-ui/jvm/src/main/scala/demo/JavaFxScreenshot.scala | sort)

if [ $# -gt 0 ]; then
    UIS="$@"
else
    UIS="$ALL_UIS"
fi

echo "Session: $CURRENT"
echo "Output:  $SESSION_DIR/static/"
echo "UIs:     $UIS"
echo ""

for ui in $UIS; do
    echo "=== $ui ==="
    sbt -no-colors "kyo-ui/runMain demo.JavaFxScreenshot $(pwd)/$SESSION_DIR $ui" 2>&1 | grep -E "Done:|error|Exception" || true
    echo ""
done

echo "All done. Screenshots in $SESSION_DIR/static/"
