#!/usr/bin/env bash
# Capture the JVM crash native stack from AeronTransportTest on Windows (probe branch only).
set -x
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"
here="$(cd "$(dirname "$0")" && pwd)/.."
cd "$here"

# Run only the crashing suite; ignore the failure, we want the hs_err.
sbt 'kyo-aeronJVM/testOnly kyo.AeronTransportTest' 2>&1 | tail -25 || true

echo "===== HS_ERR NATIVE STACK ====="
found=0
for f in $(find . -name "hs_err_pid*.log" 2>/dev/null); do
    found=1
    echo "----- $f -----"
    # The crash summary, the failing instruction/registers context, and the native + Java frames
    # of the crashing thread are the diagnostic core; print generously.
    sed -n '1,40p' "$f"
    echo "...[Current thread + stack]..."
    awk '/^Current thread /{p=1} /^siginfo/{s=1} /Native frames:/{n=1} /^Java frames:/{j=1} (p||s||n||j){print} /^Registers:/{p=0} /^\[error_reporting\]/{n=0}' "$f" | sed -n '1,90p'
done
[ "$found" = 1 ] || echo "NO hs_err file produced (no crash?)"
echo "===== DONE ====="
