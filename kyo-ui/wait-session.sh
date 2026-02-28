#!/bin/bash
# Wait for the interactive session to be ready (polls result.txt for READY)
# Reads session name from kyo-ui/sessions/.current
CURRENT="$(cat "$(dirname "$0")/sessions/.current" 2>/dev/null)"
if [ -z "$CURRENT" ]; then
  echo "ERROR: No active session. Run start-session.sh first."
  exit 1
fi
RES="$(dirname "$0")/sessions/$CURRENT/result.txt"
for i in $(seq 1 60); do
  if grep -q "READY" "$RES" 2>/dev/null; then
    echo "Session '$CURRENT' ready!"
    exit 0
  fi
  sleep 1
done
echo "TIMEOUT: session '$CURRENT' did not become ready in 60s"
exit 1
