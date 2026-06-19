#!/usr/bin/env bash
# test-batched.sh: run a platform's full test suite in dependency-aware, size-balanced batches, each
# in its OWN sbt process.
#
# Why batches in separate processes: sbt compiles in-process (the `fork` setting only forks run/test
# execution, never the Scala compiler), and G1 does not uncommit an idle driver heap, so the ONLY
# thing that returns the accumulated compile heap to the OS is process exit. A single sbt session that
# compiles every module's main+test (today's `testKyo --all JVM`) carries the whole codebase's compile
# state at once and over-commits the 16GB CI runner (exit 143 / runner shutdown). Splitting the work
# into batches that each run in a fresh JVM bounds the peak compile load per process.
#
# The batch set is computed by the `testBatchPlan` sbt command (see project/TestBatchPlan.scala):
# enumerate the platform's test projects, weight each by test-source LOC, pack under KYO_BATCH_BUDGET
# respecting test->test dependency order, and assert every project is placed (new modules can never be
# silently skipped). This script is the outer driver that runs each emitted batch as its own sbt.
#
# Usage: ./scripts/test-batched.sh [PLATFORM]   (PLATFORM defaults to JVM)
set -uo pipefail

PLATFORM="${1:-JVM}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PLAN="target/test-batches-${PLATFORM}.txt"
SCALA2_PLAN="target/test-batches-${PLATFORM}-scala2.txt"

log() { echo "=== [test-batched] $(date '+%H:%M:%S') $* ==="; }
run_sbt() { log "sbt $*"; sbt -batch "$@"; }

# 1. Compute the batch plan (writes $PLAN and $SCALA2_PLAN).
log "planning ${PLATFORM} batches"
run_sbt "testBatchPlan ${PLATFORM}" || { log "planning FAILED"; exit 1; }
[ -s "$PLAN" ] || { log "no plan written to $PLAN"; exit 1; }

# 2. Shared pre-pass, once: all main (satisfies every test->compile edge) plus the kyo-test framework's
#    Test output, which withKyoTest pulls into every module's test classpath (the one universal
#    test->test-style prerequisite). Done here so no individual batch has to carry it.
log "pre-pass: all main + test framework"
run_sbt "kyo${PLATFORM}/Compile/compile" "kyo-test-runner${PLATFORM}/Test/compile" \
    || { log "pre-pass FAILED"; exit 1; }

# 3. Run each batch in a fresh sbt process; collect failures, do not stop early (we want every result).
failed=()
batchno=0
while IFS= read -r line; do
    [ -z "$line" ] && continue
    batchno=$((batchno + 1))
    args=()
    for proj in $line; do args+=("${proj}/test"); done
    log "batch ${batchno}: ${args[*]}"
    if ! run_sbt "${args[@]}"; then
        log "batch ${batchno} FAILED"
        failed+=("$batchno")
    fi
done < "$PLAN"

# 4. Non-primary Scala versions (small, un-batched) via testKyo, which handles the ++ version switch
#    and the reduced module set for each.
if [ -s "$SCALA2_PLAN" ]; then
    while IFS= read -r ver; do
        [ -z "$ver" ] && continue
        if ! run_sbt "testKyo --all --scala ${ver} ${PLATFORM}"; then
            log "scala ${ver} pass FAILED"
            failed+=("scala-${ver}")
        fi
    done < "$SCALA2_PLAN"
fi

if [ ${#failed[@]} -ne 0 ]; then
    log "FAILED: ${failed[*]}"
    exit 1
fi
log "all ${batchno} batches + non-primary scala passes succeeded"
