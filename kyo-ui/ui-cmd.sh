#!/bin/bash
# Send a command to the interactive UI session and wait for result.
# Reads session name from kyo-ui/sessions/.current
# Usage: ./kyo-ui/ui-cmd.sh <command>
# Examples:
#   ./kyo-ui/ui-cmd.sh render demo
#   ./kyo-ui/ui-cmd.sh jfx-click .button
#   ./kyo-ui/ui-cmd.sh jfx-text .counter-value
#   ./kyo-ui/ui-cmd.sh screenshot demo-initial
#   ./kyo-ui/ui-cmd.sh stop

CURRENT="$(cat "$(dirname "$0")/sessions/.current" 2>/dev/null)"
if [ -z "$CURRENT" ]; then
  echo "ERROR: No active session. Run start-session.sh first."
  exit 1
fi
DIR="$(dirname "$0")/sessions/$CURRENT"
CMD="$DIR/cmd.txt"
RES="$DIR/result.txt"

echo "" > "$RES"
echo "$*" > "$CMD"

for i in $(seq 1 60); do
  r=$(cat "$RES" 2>/dev/null)
  if [ -n "$r" ]; then
    echo "$r"
    exit 0
  fi
  sleep 0.5
done
echo "TIMEOUT"
exit 1
