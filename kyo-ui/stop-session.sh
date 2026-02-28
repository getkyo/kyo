#!/bin/bash
# Stop the current interactive UI session.
# Kills all InteractiveSession processes and clears the active session marker.
# Usage: ./kyo-ui/stop-session.sh

CURRENT="$(cat "$(dirname "$0")/sessions/.current" 2>/dev/null)"
if [ -z "$CURRENT" ]; then
  echo "No active session."
  exit 0
fi

pkill -9 -f "demo.InteractiveSession" 2>/dev/null
sleep 2

REMAINING=$(pgrep -f "demo.InteractiveSession" | wc -l | tr -d ' ')
if [ "$REMAINING" -gt 0 ]; then
  echo "ERROR: $REMAINING processes still running"
  exit 1
fi

echo "Session '$CURRENT' stopped."
