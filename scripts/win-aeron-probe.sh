#!/usr/bin/env bash
# Capture the JVM crash Java-frame stack from AeronTransportTest on Windows (probe branch only).
set -x
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"
here="$(cd "$(dirname "$0")" && pwd)/.."
cd "$here"

sbt 'kyo-aeronJVM/testOnly kyo.AeronTransportTest' 2>&1 | tail -8 || true

echo "===== HS_ERR JAVA FRAMES ====="
found=0
for f in $(find . -name "hs_err_pid*.log" 2>/dev/null); do
    found=1
    echo "----- $f -----"
    # The crash summary line and the Problematic frame.
    grep -aE "EXCEPTION_ACCESS|Problematic frame|jint_disjoint|siginfo|Current thread" "$f" | head -8
    echo "...[JAVA FRAMES of the crashing thread]..."
    # Print the block that starts at "Java frames:" up to the next blank line.
    awk '/^Java frames:/{p=1} p{print} p&&/^[[:space:]]*$/{c++; if(c>=1 && NR>1) exit}' "$f" | head -60
    echo "...[aeron / conductor thread states]..."
    grep -aE "aeron|conductor|driver-conductor|client-conductor|_thread_in_native|MediaDriver" "$f" | head -25
done
[ "$found" = 1 ] || echo "NO hs_err file produced (no crash?)"
echo "===== DONE ====="
