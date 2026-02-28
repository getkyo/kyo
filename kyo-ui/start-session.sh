#!/bin/bash
# Start the interactive UI session (JFX + Browser)
# Usage: ./kyo-ui/start-session.sh <session-name>
# Creates kyo-ui/sessions/<session-name>/ with cmd.txt, result.txt, screenshots
set -e
SESSION="${1:?Usage: start-session.sh <session-name>}"
cd "$(dirname "$0")/.."
mkdir -p "kyo-ui/sessions/$SESSION"
echo "$SESSION" > kyo-ui/sessions/.current
sbt "kyo-ui/runMain demo.InteractiveSession $SESSION"
