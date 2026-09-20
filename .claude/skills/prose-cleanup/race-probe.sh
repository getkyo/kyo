#!/usr/bin/env bash
# Do the tools answer the same under concurrency as they do alone?
#
#   race-probe.sh <script> <file> [file ...]
#
# Takes a serial baseline per file, then runs all the files at once for several rounds and compares
# each result against its baseline. Any divergence means the script is sharing scratch state between
# invocations, so one agent in a wave is reading another's data.
#
# Run this before dispatching a wave. A guard validated serially and deployed concurrently is not a
# validated guard: a shared scratch path made verify.sh answer wrongly on two thirds of concurrent
# runs, in both directions, which would have passed stolen lines with a green result.
set -u
SCRIPT="${1:?usage: race-probe.sh <script> <file> [file ...]}"; shift
ROUNDS="${ROUNDS:-12}"
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
FILES=("$@")
key() { echo "$1" | tr / _; }

echo "baseline (serial)..."
for f in "${FILES[@]}"; do bash "$SCRIPT" "$f" > "$WORK/base.$(key "$f")" 2>&1; done

mismatch=0
for r in $(seq 1 "$ROUNDS"); do
    for f in "${FILES[@]}"; do bash "$SCRIPT" "$f" > "$WORK/run.$r.$(key "$f")" 2>&1 & done
    wait
    for f in "${FILES[@]}"; do
        k=$(key "$f")
        cmp -s "$WORK/base.$k" "$WORK/run.$r.$k" || {
            mismatch=$((mismatch+1))
            [ "$mismatch" -le 3 ] && echo "  MISMATCH round $r: $f"
        }
    done
done

echo
echo "concurrent invocations: $((ROUNDS * ${#FILES[@]}))"
if [ "$mismatch" -eq 0 ]; then
    echo "PASS: stable under concurrency"
else
    echo "FAIL: $mismatch diverging results, the script shares state between invocations"
    exit 1
fi
