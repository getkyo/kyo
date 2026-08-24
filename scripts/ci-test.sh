#!/usr/bin/env bash
set -uo pipefail
#
# The single definition of "build and test platform P".
#
# Usage:
#   ci-test.sh <platform> <action>
#   ci-test.sh --self-test
#
# <platform>  one of JVM, JS, Native, Wasm.
# <action>    one of test, testDiff, compile, link.
#
# JVM, JS, and Wasm run as separate sbt processes per phase (compile-main,
# compile-test, the run pass at the primary Scala version, then the Scala 2.x
# cross pass) so the driver never holds the whole compile heap while the test
# phase forks.
#
# Native runs a pool of fresh drivers over one module plan:
#
#   1. plan       one sbt process writes the selected modules to a file
#   2. pre-link   each NATIVE_HEAVY module the plan selects, alone in its driver
#   3. link pool  the plan in batches of NATIVE_LINK_BATCH modules
#   4. test pool  the plan in batches of NATIVE_TEST_BATCH modules
#   5. cross      the Scala 2.x cross-build modules
#
# Every process runs at pinned Scala versions, so the link pool and the test pool
# hit each other's zinc and nativeLink caches instead of rebuilding the plan
# twice. A crash-retry loop wraps each test batch and the cross pass, tolerating
# libunwind shutdown hangs and mid-RPC errno-104 resets but never a pass that
# stopped short of its module list (see DONE_MARKER). The strategy is derived
# from the platform; no caller selects it.
#
# Reads CI, SBT_TASK_LIMIT, JAVA_OPTS, JVM_OPTS, NATIVE_HEAVY, NATIVE_LINK_CPUS,
# NATIVE_LINK_BATCH, and NATIVE_TEST_BATCH from the environment; mutates none of
# them (the nativeLink invocations append -XX:ActiveProcessorCount per invocation
# when NATIVE_LINK_CPUS is set). The caller (a CI workflow, or build.sh --env
# podman-ci) owns the environment, so this one runner is correct in every
# environment.

PLATFORMS="JVM JS Native Wasm"
ACTIONS="test testDiff compile link"

usage() {
    echo "Usage: ci-test.sh <platform> <action>" >&2
    echo "  <platform>  one of: $PLATFORMS" >&2
    echo "  <action>    one of: $ACTIONS" >&2
}

contains_word() {
    local word="$1" list="$2" item
    for item in $list; do
        [ "$item" = "$word" ] && return 0
    done
    return 1
}

# -- self-test mode (must precede argument handling) --
# Exercises the runner against a faked sbt that records every invocation to a
# call log and the JAVA_OPTS it inherited to a heap log, so each case asserts
# the RECORDED CALLS or the RECORDED HEAP, not just the exit code: the
# JVM/JS/Wasm phase split (compile-main, compile-test, run, cross) on full AND
# diff; the Native plan/pre-link/link-pool/test-pool/cross flow with its batch
# sizes and per-batch retry; the completion marker separating a finished pass
# from a truncated one; the platform-derived strategy; the exit-code mapping;
# and the NATIVE_LINK_CPUS cap reaching every link invocation and no other.
if [ "${1:-}" = "--self-test" ]; then
    SELF="$0"
    PASS=0; FAIL=0; TOTAL=0
    SELFDIR=$(mktemp -d)
    CALLS="$SELFDIR/calls.log"
    HEAP="$SELFDIR/heap.log"
    trap 'rm -rf "$SELFDIR"' EXIT

    # The modules the fake sbt writes when the runner asks it to plan; cases
    # reassign it to shape the pools. PASS_BODY is a complete green pass: test
    # output plus the completion marker the tolerance branches require.
    FAKE_PLAN="kyo-dataNative kyo-preludeNative"
    PASS_BODY='echo "Tests: succeeded 100, failed 0"; echo "[testKyo] completed"; exit 0'

    # Build a fake sbt whose body is $1; every call appends its full
    # argument string to CALLS and the JAVA_OPTS it inherited to HEAP, so an
    # assertion can read both the call sequence and the heap the sbt subprocess
    # saw. A --plan-file call is the planning process: it writes FAKE_PLAN to
    # the requested path and exits without reaching the body.
    make_fake_sbt() {
        {
            printf '#!/usr/bin/env bash\n'
            printf 'printf "%%s\\n" "$*" >> "%s"\n' "$CALLS"
            printf 'printf "%%s\\n" "${JAVA_OPTS:-}" >> "%s"\n' "$HEAP"
            printf 'all="$*"\n'
            printf 'if [ "${all#*--plan-file }" != "$all" ]; then\n'
            printf '    rest="${all#*--plan-file }"; path="${rest%%%% *}"\n'
            printf '    : > "$path"\n'
            printf '    for m in %s; do printf "%%s\\n" "$m" >> "$path"; done\n' "$FAKE_PLAN"
            printf '    exit 0\n'
            printf 'fi\n'
            printf '%s\n' "$1"
        } > "$SELFDIR/sbt"
        chmod +x "$SELFDIR/sbt"
    }

    # Run the real runner under the fake sbt. Sets CT_EXIT (the runner exit
    # code) and leaves the recorded calls in CALLS for the assertion. Trailing
    # VAR=value pairs enter the runner's environment.
    run_runner_env() {
        local body="$1" platform="$2" action="$3"; shift 3
        : > "$CALLS"; : > "$HEAP"
        make_fake_sbt "$body"
        env PATH="$SELFDIR:$PATH" MAX_RETRIES=2 STALE_TIMEOUT=2 POLL_INTERVAL=1 CI_MON=0 "$@" \
            "$SELF" "$platform" "$action" >/dev/null 2>&1
        CT_EXIT=$?
    }

    run_runner() {  # run_runner <platform> <action> <body>
        run_runner_env "$3" "$1" "$2"
    }

    # Assertion helpers, evaluated against CT_EXIT and CALLS.
    exit_is()      { [ "$CT_EXIT" = "$1" ]; }
    calls_count()  { [ "$(wc -l < "$CALLS" | tr -d ' ')" = "$1" ]; }
    call_nth_is()  { [ "$(sed -n "${1}p" "$CALLS")" = "$2" ]; }
    call_nth_has() { sed -n "${1}p" "$CALLS" | grep -qF -- "$2"; }
    calls_have()   { grep -qF -- "$1" "$CALLS"; }
    calls_lack()   { ! grep -qF -- "$1" "$CALLS"; }
    heap_nth_has() { sed -n "${1}p" "$HEAP" | grep -qF -- "$2"; }

    # Register a case: name + an assertion expression already evaluated by
    # the caller into $? . PASS when the caller passed 'true'.
    record() {
        TOTAL=$((TOTAL+1))
        if [ "$1" = "ok" ]; then
            echo "  PASS: $2"; PASS=$((PASS+1))
        else
            echo "  FAIL: $2"; FAIL=$((FAIL+1))
        fi
    }

    echo "Running ci-test.sh self-tests..."

    # 1. JVM phase-split: four ordered processes on a full run.
    run_runner JVM test 'exit 0'
    if calls_count 4 \
       && call_nth_is 1 "testKyo --phase compile-main --scala 3 --all JVM" \
       && call_nth_is 2 "testKyo --phase compile-test --scala 3 --all JVM" \
       && call_nth_is 3 "testKyo --scala 3 --all JVM" \
       && call_nth_is 4 "testKyo --cross --all JVM" && exit_is 0
    then record ok "JVM phase-split: four ordered processes (full)"
    else record no "JVM phase-split: four ordered processes (full)"; fi

    # 2. JS and Wasm take the same four-process split.
    run_runner JS test 'exit 0'
    js_ok=no
    if calls_count 4 && call_nth_is 3 "testKyo --scala 3 --all JS" \
       && call_nth_is 4 "testKyo --cross --all JS" && exit_is 0; then js_ok=yes; fi
    run_runner Wasm test 'exit 0'
    if [ "$js_ok" = yes ] && calls_count 4 && call_nth_is 3 "testKyo --scala 3 --all Wasm" \
       && call_nth_is 4 "testKyo --cross --all Wasm" && exit_is 0
    then record ok "JS and Wasm take the same four-process split"
    else record no "JS and Wasm take the same four-process split"; fi

    # 3. Phase-split fails fast on a compile-main failure.
    run_runner JVM test 'exit 1'
    if calls_count 1 && call_nth_is 1 "testKyo --phase compile-main --scala 3 --all JVM" && exit_is 1
    then record ok "phase-split fails fast on compile-main failure"
    else record no "phase-split fails fast on compile-main failure"; fi

    # 4. testDiff omits --all but keeps the same four processes.
    run_runner JVM testDiff 'exit 0'
    if calls_count 4 \
       && call_nth_is 1 "testKyo --phase compile-main --scala 3  JVM" \
       && call_nth_is 3 "testKyo --scala 3  JVM" \
       && call_nth_is 4 "testKyo --cross  JVM" && exit_is 0
    then record ok "testDiff omits --all but keeps the four processes"
    else record no "testDiff omits --all but keeps the four processes"; fi

    # 5. compile action runs only the compile phases (no run pass).
    run_runner JVM compile 'exit 0'
    if calls_count 3 && call_nth_is 3 "testKyo --phase compile-test --cross  JVM" \
       && calls_lack "testKyo --scala 3  JVM" && calls_lack "testKyo --cross  JVM" && exit_is 0
    then record ok "compile action runs only the compile phases"
    else record no "compile action runs only the compile phases"; fi

    # 6. The cross pass never runs when the primary run pass fails.
    run_runner JVM test 'if [[ "$*" == *--phase* ]]; then exit 0; fi
if [[ "$*" == *"--scala 3"* ]]; then exit 1; fi
exit 0'
    if calls_count 3 && calls_lack "--cross" && exit_is 1
    then record ok "run phase fails fast before the cross process"
    else record no "run phase fails fast before the cross process"; fi

    # 7. Native plans first, then links the plan in batches before any test process.
    run_runner Native test "$PASS_BODY"
    if calls_count 4 \
       && call_nth_has 1 "testKyo --dry-run --plan-file" && call_nth_has 1 "--scala 3 --all Native" \
       && call_nth_is 2 "testKyo --phase link --scala 3 --modules kyo-dataNative,kyo-preludeNative Native" \
       && call_nth_is 3 "testKyo --scala 3 --modules kyo-dataNative,kyo-preludeNative Native" \
       && call_nth_is 4 "testKyo --cross --all Native" && exit_is 0
    then record ok "Native plans, then links the plan before any test process"
    else record no "Native plans, then links the plan before any test process"; fi

    # 8. Native never receives a compile phase (strategy derived from platform).
    run_runner Native test "$PASS_BODY"
    if calls_lack "--phase compile-main" && calls_lack "--phase compile-test"
    then record ok "Native never receives a compile phase argument"
    else record no "Native never receives a compile phase argument"; fi

    # 9. Native link failure exits 1 before any test runs.
    run_runner Native test 'if [[ "$*" == *"--phase link"* ]]; then exit 3; fi
'"$PASS_BODY"
    if calls_count 2 && calls_lack "testKyo --scala 3 --modules" && calls_lack "--cross" && exit_is 1
    then record ok "Native link failure exits 1 before any test runs"
    else record no "Native link failure exits 1 before any test runs"; fi

    # 10. NATIVE_HEAVY pre-links a planned heavy module in its own process before the link pool.
    FAKE_PLAN="kyo-schema-testsNative kyo-dataNative"
    run_runner_env "$PASS_BODY" Native test NATIVE_HEAVY="kyo-schema-tests"
    if call_nth_has 1 "--plan-file" \
       && call_nth_is 2 "kyo-schema-testsNative/Test/nativeLink" \
       && call_nth_is 3 "testKyo --phase link --scala 3 --modules kyo-schema-testsNative,kyo-dataNative Native" \
       && exit_is 0
    then record ok "NATIVE_HEAVY pre-links a planned heavy module before the link pool"
    else record no "NATIVE_HEAVY pre-links a planned heavy module before the link pool"; fi

    # 11. NATIVE_LINK_CPUS caps the pre-link and every link batch, and nothing else.
    run_runner_env "$PASS_BODY" Native test NATIVE_HEAVY="kyo-schema-tests" NATIVE_LINK_CPUS=2
    if ! heap_nth_has 1 "-XX:ActiveProcessorCount=2" \
       && heap_nth_has 2 "-XX:ActiveProcessorCount=2" \
       && heap_nth_has 3 "-XX:ActiveProcessorCount=2" \
       && ! heap_nth_has 4 "-XX:ActiveProcessorCount=2" \
       && ! heap_nth_has 5 "-XX:ActiveProcessorCount=2" && exit_is 0
    then record ok "NATIVE_LINK_CPUS caps link invocations, never plan, test, or cross"
    else record no "NATIVE_LINK_CPUS caps link invocations, never plan, test, or cross"; fi

    # 12. A heavy pre-link failure aborts before the link pool and before any tests.
    run_runner_env 'if [[ "$*" == *"kyo-schema-testsNative/Test/nativeLink"* ]]; then exit 3; fi
'"$PASS_BODY" Native test NATIVE_HEAVY="kyo-schema-tests"
    if calls_count 2 && call_nth_is 2 "kyo-schema-testsNative/Test/nativeLink" \
       && calls_lack "--phase link" && calls_lack "testKyo --scala 3 --modules" && exit_is 1
    then record ok "NATIVE_HEAVY pre-link failure aborts before the link pool and tests"
    else record no "NATIVE_HEAVY pre-link failure aborts before the link pool and tests"; fi

    # 13. A NATIVE_HEAVY module the plan does not select is never pre-linked.
    FAKE_PLAN="kyo-dataNative kyo-preludeNative"
    run_runner_env "$PASS_BODY" Native test NATIVE_HEAVY="kyo-schema-tests"
    if calls_lack "kyo-schema-testsNative/Test/nativeLink" \
       && call_nth_is 2 "testKyo --phase link --scala 3 --modules kyo-dataNative,kyo-preludeNative Native" \
       && exit_is 0
    then record ok "an unplanned NATIVE_HEAVY module is not pre-linked"
    else record no "an unplanned NATIVE_HEAVY module is not pre-linked"; fi

    # 14. An empty plan links and tests nothing, and still runs the cross pass.
    FAKE_PLAN=""
    run_runner Native test "$PASS_BODY"
    if calls_count 2 && call_nth_is 2 "testKyo --cross --all Native" && exit_is 0
    then record ok "an empty plan skips both pools and still runs the cross pass"
    else record no "an empty plan skips both pools and still runs the cross pass"; fi

    # 15. NATIVE_LINK_BATCH partitions the plan into ordered link processes.
    FAKE_PLAN="m1Native m2Native m3Native m4Native m5Native m6Native m7Native"
    run_runner_env 'exit 0' Native link NATIVE_LINK_BATCH=3
    if calls_count 4 \
       && call_nth_is 2 "testKyo --phase link --scala 3 --modules m1Native,m2Native,m3Native Native" \
       && call_nth_is 3 "testKyo --phase link --scala 3 --modules m4Native,m5Native,m6Native Native" \
       && call_nth_is 4 "testKyo --phase link --scala 3 --modules m7Native Native" && exit_is 0
    then record ok "NATIVE_LINK_BATCH partitions the plan into ordered link processes"
    else record no "NATIVE_LINK_BATCH partitions the plan into ordered link processes"; fi

    # 16. NATIVE_TEST_BATCH partitions the test pool, and a crash retries only its own batch.
    rm -f "$SELFDIR/crash"
    run_runner_env 'if [[ "$*" == *"--phase link"* ]]; then exit 0; fi
if [[ "$*" == *"m4Native,m5Native,m6Native"* ]] && [ ! -f "'"$SELFDIR"'/crash" ]; then
    touch "'"$SELFDIR"'/crash"; exit 137
fi
'"$PASS_BODY" Native test NATIVE_TEST_BATCH=3
    if calls_count 7 \
       && call_nth_is 3 "testKyo --scala 3 --modules m1Native,m2Native,m3Native Native" \
       && call_nth_is 4 "testKyo --scala 3 --modules m4Native,m5Native,m6Native Native" \
       && call_nth_is 5 "testKyo --scala 3 --modules m4Native,m5Native,m6Native Native" \
       && call_nth_is 6 "testKyo --scala 3 --modules m7Native Native" \
       && call_nth_is 7 "testKyo --cross --all Native" && exit_is 0
    then record ok "a crashed test batch is retried alone, the other batches run once"
    else record no "a crashed test batch is retried alone, the other batches run once"; fi

    # 17. A clean Tests: tail without the completion marker is a truncated pass, not a green one.
    FAKE_PLAN="kyo-dataNative"
    run_runner Native test 'if [[ "$*" == *"--phase link"* ]]; then exit 0; fi
echo "Tests: succeeded 100, failed 0"; exit 1'
    if calls_count 4 \
       && call_nth_is 3 "testKyo --scala 3 --modules kyo-dataNative Native" \
       && call_nth_is 4 "testKyo --scala 3 --modules kyo-dataNative Native" \
       && calls_lack "--cross" && exit_is 1
    then record ok "a clean tail without the completion marker is retried, then fails"
    else record no "a clean tail without the completion marker is retried, then fails"; fi

    # 18-29: Native crash-retry / check_log scenarios.
    # For these the fake sbt's link calls must pass, so the body branches on $*.
    FAKE_PLAN="kyo-dataNative kyo-preludeNative"
    nat() {  # nat <name> <expected-exit> <run-body>
        run_runner Native test "if [[ \"\$*\" == *'--phase link'* ]]; then exit 0; fi
$3"
        if exit_is "$2"; then record ok "$1"; else record no "$1"; fi
    }
    nat "clean Native pass exits 0"                 0 "$PASS_BODY"
    nat "real Native test failures exit 1"          1 'echo "Tests: succeeded 90, failed 3"; exit 1'
    nat "Native crash after a complete pass tolerated" 0 'echo "Tests: succeeded 100, failed 0"
echo "[testKyo] completed"; exit 137'
    nat "Native kill before any tests exits 1"      1 'exit 137'
    nat "Native hang after the completion marker tolerated" 0 'echo "Tests: succeeded 163, failed 0"
echo "[testKyo] completed"; sleep 600'
    nat "Native hang after a failure exits 1"       1 'echo "Tests: succeeded 90, failed 2"; sleep 600'
    nat "Native hang with no output exits 1"        1 'sleep 600'
    nat "Native multi-suite all-pass exits 0"       0 'echo "Tests: succeeded 64, failed 0"
echo "Tests: succeeded 45, failed 0"
echo "Tests: succeeded 163, failed 0"
echo "[testKyo] completed"; exit 0'
    nat "Native multi-suite one failure exits 1"    1 'echo "Tests: succeeded 64, failed 0"
echo "Tests: succeeded 45, failed 2"; exit 1'
    nat "Native kill mid-compile after pass exits 1" 1 'echo "Tests: succeeded 64, failed 0"
echo "[info] compiling 39 Scala sources to /target/test-classes ..."; sleep 600'
    nat "Native errno-104 mid-RPC retried then passes" 0 'if [ ! -f "'"$SELFDIR"'/rpc" ]; then touch "'"$SELFDIR"'/rpc"
echo "  - t *** FAILED *** (15 seconds)"
echo "Exception in thread \"main\" java.net.SocketException: read failed, errno: 104"
echo "    at scala.scalanative.testinterface.NativeRPC.loop(Unknown Source)"; exit 1
else rm -f "'"$SELFDIR"'/rpc"; echo "Tests: succeeded 100, failed 0"; echo "[testKyo] completed"; exit 0; fi'
    rm -f "$SELFDIR/rpc"
    nat "Native FAILED without rpc crash stays a failure" 1 'echo "  - t *** FAILED *** (15 seconds)"
echo "Exception in thread \"main\" java.lang.RuntimeException: oops"; exit 1'

    # 30-31: argument validation exits 2 before any sbt.
    run_runner Frob test 'exit 0'
    if exit_is 2 && calls_count 0; then record ok "unknown platform exits 2 before any sbt"
    else record no "unknown platform exits 2 before any sbt"; fi
    run_runner JVM frob 'exit 0'
    if exit_is 2 && calls_count 0; then record ok "unknown action exits 2 before any sbt"
    else record no "unknown action exits 2 before any sbt"; fi

    # Negative control: a deliberately wrong expectation MUST flip FAIL,
    # proving the harness is not vacuous. Not counted in the scenario total.
    run_runner JVM test 'exit 0'
    if calls_count 99; then echo "  SELFTEST-BUG: negative control passed"; FAIL=$((FAIL+1)); fi

    echo ""
    echo "Results: $PASS/$TOTAL passed, $FAIL failed"
    [ "$FAIL" -eq 0 ] && [ "$TOTAL" -eq 31 ]
    exit $?
fi

# -- argument validation (runs first, before any sbt) --
PLATFORM="${1:-}"
ACTION="${2:-}"
if [ -z "$PLATFORM" ] || [ -z "$ACTION" ]; then
    usage; exit 2
fi
if ! contains_word "$PLATFORM" "$PLATFORMS"; then
    echo "ci-test.sh: unknown platform '$PLATFORM'" >&2; usage; exit 2
fi
if ! contains_word "$ACTION" "$ACTIONS"; then
    echo "ci-test.sh: unknown action '$ACTION'" >&2; usage; exit 2
fi

MAX_RETRIES=${MAX_RETRIES:-3}
STALE_TIMEOUT=${STALE_TIMEOUT:-600}
POLL_INTERVAL=${POLL_INTERVAL:-10}

# Space-separated module names (e.g. "kyo-schema-tests", which links every serialization format
# into one binary) whose SOLO native-link optimize peak needs an isolated, fresh-heap sbt driver.
# Each is linked first in its own process, ahead of the link pool, so its whole-program optimize
# never runs in a driver whose preceding modules have already filled the 2G metaspace. Measured
# before the format-module split: the then-monolithic kyo-schema alone peaked ~7.7G RSS in a clean
# process vs ~9.9G in an accumulated driver (the delta is that accumulation), which OOM-killed the
# 8G-capped Native driver. nativeLink is disk-cached per project, so the link pool skips a module
# already linked here, and a module the plan does not select is not pre-linked at all. Empty by
# default; the CI workflow sets it for the Native target.
NATIVE_HEAVY="${NATIVE_HEAVY:-}"

# When non-empty, the nativeLink sbt invocations run with -XX:ActiveProcessorCount=$NATIVE_LINK_CPUS.
# The scala-native toolchain sizes its optimizer pool and its concurrent clang forks from
# availableProcessors, and the fork fleet stacked on top of the driver heap is what overcommits the
# 16GB CI runners. Scoped to the link invocations only; compile and the test run keep every CPU.
NATIVE_LINK_CPUS="${NATIVE_LINK_CPUS:-}"

# Modules per sbt process in the Native link and test pools. Each batch is a fresh driver, so no
# driver carries more than this many modules of heap and metaspace; the accumulation delta above
# builds up around the tenth module of a shared driver. Empty or 0 runs the whole plan in one
# process, which is the shape a local run wants; the CI workflow sets both for the Native target.
NATIVE_LINK_BATCH="${NATIVE_LINK_BATCH:-}"
NATIVE_TEST_BATCH="${NATIVE_TEST_BATCH:-}"

log() { echo "=== [ci-test] $(date '+%H:%M:%S') $* ==="; }

# sbt for a nativeLink invocation: applies the NATIVE_LINK_CPUS cap when set. The flag is added
# via the invocation's environment; .jvmopts only overrides flags it duplicates, so a flag that
# appears only here always reaches the JVM.
link_sbt() {
    if [ -n "$NATIVE_LINK_CPUS" ]; then
        JAVA_OPTS="${JAVA_OPTS:-} -XX:ActiveProcessorCount=$NATIVE_LINK_CPUS" \
        JVM_OPTS="${JVM_OPTS:-} -XX:ActiveProcessorCount=$NATIVE_LINK_CPUS" \
            sbt "$@"
    else
        sbt "$@"
    fi
}

# run-arg: full run sends --all, diff run sends nothing (testKyo diffs vs
# origin/main). compile/link map to the dedicated phases below.
run_arg() {
    case "$ACTION" in
        test) echo "--all" ;;
        *)    echo "" ;;
    esac
}

# -- JVM / JS / Wasm: phase-split processes, fail-fast --
# Each process pins its Scala versions: --scala 3 runs the primary version with no version switch,
# --cross runs the Scala 2.x passes. A process that did both would restore the primary version
# before its queued tasks ran, which is how the 2.13 pass used to test the Scala 3 build.
run_phase_split() {
    local arg; arg=$(run_arg)
    case "$ACTION" in
        compile)
            sbt "testKyo --phase compile-main --scala 3 $arg $PLATFORM" || return $?
            sbt "testKyo --phase compile-test --scala 3 $arg $PLATFORM" || return $?
            sbt "testKyo --phase compile-test --cross $arg $PLATFORM" || return $?
            return 0
            ;;
        link)
            log "link is a no-op for $PLATFORM (link happens in the run phase)"
            return 0
            ;;
        *)
            sbt "testKyo --phase compile-main --scala 3 $arg $PLATFORM" || return $?
            sbt "testKyo --phase compile-test --scala 3 $arg $PLATFORM" || return $?
            sbt "testKyo --scala 3 $arg $PLATFORM" || return $?
            sbt "testKyo --cross $arg $PLATFORM" || return $?
            return 0
            ;;
    esac
}

# -- Native: plan, link pool, test pool, cross pass --
LOG=""
tail_pid=""
watchdog_killed=0

# The modules the run selects, computed once by the planning process and partitioned by both pools,
# so link and test operate on the identical list and each batch's membership is in the runner log.
PLAN="${RUNNER_TEMP:-/tmp}/kyo-native-plan.$$"

# The line testKyo chains after each pass's tasks (project/TestKyo.scala). A pass that ends without
# it stopped short of its module list.
DONE_MARKER="[testKyo] completed"

native_cleanup() { rm -f "$LOG" "$PLAN"; [ -n "$tail_pid" ] && kill "$tail_pid" 2>/dev/null; }

# Partition the plan into comma-separated module lists of at most $1 modules, one batch per line,
# preserving plan order. An empty or non-positive size puts every module in one batch; an empty
# plan yields no batches at all.
plan_batches() {
    grep -v '^[[:space:]]*$' "$PLAN" | awk -v n="${1:-0}" '
        { mods[NR] = $0 }
        END {
            if (NR == 0) exit
            if (n <= 0) n = NR
            for (i = 1; i <= NR; i += n) {
                line = mods[i]
                for (j = i + 1; j < i + n && j <= NR; j++) line = line "," mods[j]
                print line
            }
        }'
}

file_size() { wc -c < "$1" 2>/dev/null | tr -d ' '; }

kill_tree() {
    local pid=$1 sig=${2:-TERM} child children
    kill -"$sig" -- -"$pid" 2>/dev/null
    children=$(pgrep -P "$pid" 2>/dev/null) || true
    for child in $children; do kill_tree "$child" "$sig"; done
    kill -"$sig" "$pid" 2>/dev/null
}

# The native test binary losing its RPC link (errno 104 ECONNRESET paired
# with the NativeRPC.loop frame) means preceding FAILED lines came from tests
# talking to a server the runner could no longer reach; retry rather than
# trust them.
crashed_native_runner() {
    grep -qE 'java\.net\.SocketException: read failed, errno: 104' "$LOG" \
        && grep -qE 'scala\.scalanative\.testinterface\.NativeRPC' "$LOG"
}

# 0 pass, 1 real failure, 2 no verdict (retry).
check_log() {
    if crashed_native_runner; then
        log "native test runner crashed mid-RPC (errno 104): retrying"
        return 2
    fi
    if grep -qE "Tests:.*failed [1-9]" "$LOG"; then
        log "tests FAILED (real test failures detected)"; return 1
    fi
    if grep -qE "\*\*\* FAILED \*\*\*" "$LOG"; then
        log "tests FAILED (individual test failures detected)"; return 1
    fi
    if ! grep -qE "Tests:" "$LOG"; then
        return 2
    fi
    if [ "$watchdog_killed" -eq 1 ]; then
        last_test_line=$(grep -nE "Tests:" "$LOG" | tail -1 | cut -d: -f1)
        if [ -n "$last_test_line" ]; then
            post_test=$(tail -n +$((last_test_line + 1)) "$LOG" \
                | grep -E "compiling [0-9]+ Scala source|Linking native code|^\[info\] [A-Z][a-zA-Z]+(Test|Suite):" \
                | head -1)
            if [ -n "$post_test" ]; then
                log "watchdog killed mid-run: $post_test"; return 1
            fi
        fi
    fi
    # A clean `Tests:` tail only says "nothing has failed yet": it is identical whether the pass
    # ended or died at a module boundary with modules still queued. The marker is what separates
    # the two, so without it the pass is truncated and gets retried, not tolerated.
    if ! grep -qF -- "$DONE_MARKER" "$LOG"; then
        log "clean tests but no '$DONE_MARKER': pass did not run to completion"; return 2
    fi
    if [ "$watchdog_killed" -eq 1 ]; then
        log "watchdog killed after final Tests: line: tolerating shutdown hang"; return 0
    fi
    log "0 test failures: tolerating non-zero exit"; return 0
}

# One sbt invocation under the stale-output watchdog: retries while check_log reports no verdict,
# so a crash costs one batch rather than the whole test phase. 0 pass, 1 failure.
run_watched() {
    local label="$1" cmd="$2" attempt
    for attempt in $(seq 1 "$MAX_RETRIES"); do
        log "attempt $attempt/$MAX_RETRIES $label: sbt $cmd"
        : > "$LOG"; watchdog_killed=0
        if command -v setsid >/dev/null 2>&1; then
            setsid sbt "$cmd" >> "$LOG" 2>&1 &
        else
            sbt "$cmd" >> "$LOG" 2>&1 &
        fi
        sbt_pid=$!
        tail -f "$LOG" 2>/dev/null & tail_pid=$!
        last_size=$(file_size "$LOG"); stale_seconds=0
        while kill -0 "$sbt_pid" 2>/dev/null; do
            sleep "$POLL_INTERVAL"
            current_size=$(file_size "$LOG")
            if [ "$current_size" = "$last_size" ]; then
                stale_seconds=$((stale_seconds + POLL_INTERVAL))
                if [ "$stale_seconds" -ge "$STALE_TIMEOUT" ]; then
                    log "no output for ${STALE_TIMEOUT}s: killing hung process"
                    watchdog_killed=1
                    kill_tree "$sbt_pid" TERM; sleep 3
                    kill_tree "$sbt_pid" KILL; sleep 2
                    break
                fi
            else
                stale_seconds=0; last_size=$current_size
            fi
        done
        wait "$sbt_pid" 2>/dev/null; exit_code=$?
        kill "$tail_pid" 2>/dev/null; wait "$tail_pid" 2>/dev/null; tail_pid=""
        if [ "$exit_code" -eq 0 ]; then log "$label passed"; return 0; fi
        check_log; rc=$?
        [ "$rc" -le 1 ] && return "$rc"
        log "no verdict from $label: retrying..."
    done
    log "FAILED: $label produced no verdict after $MAX_RETRIES attempts"
    return 1
}

run_native() {
    local arg; arg=$(run_arg)
    if [ "$ACTION" = "compile" ]; then
        sbt "testKyo --phase compile-main --scala 3 $arg Native" || return $?
        sbt "testKyo --phase compile-test --scala 3 $arg Native" || return $?
        sbt "testKyo --phase compile-test --cross $arg Native" || return $?
        return 0
    fi

    trap native_cleanup EXIT

    log "planning native modules: sbt testKyo --dry-run --plan-file $PLAN --scala 3 $arg Native"
    sbt "testKyo --dry-run --plan-file $PLAN --scala 3 $arg Native" \
        || { log "native planning failed"; return 1; }
    if [ ! -f "$PLAN" ]; then
        log "native planning wrote no plan file ($PLAN)"; return 1
    fi
    log "plan: $(tr '\n' ' ' < "$PLAN")"

    for heavy in $NATIVE_HEAVY; do
        if grep -qxF -- "${heavy}Native" "$PLAN"; then
            log "pre-linking heavy native module in an isolated driver: sbt ${heavy}Native/Test/nativeLink"
            link_sbt "${heavy}Native/Test/nativeLink" || { log "native pre-link of $heavy failed"; return 1; }
        fi
    done

    local batch
    for batch in $(plan_batches "$NATIVE_LINK_BATCH"); do
        log "linking native test binaries: sbt testKyo --phase link --scala 3 --modules $batch Native"
        link_sbt "testKyo --phase link --scala 3 --modules $batch Native" \
            || { log "native linking failed for $batch"; return 1; }
    done
    if [ "$ACTION" = "link" ]; then
        log "link complete"; return 0
    fi

    LOG=$(mktemp)
    for batch in $(plan_batches "$NATIVE_TEST_BATCH"); do
        run_watched "test batch $batch" "testKyo --scala 3 --modules $batch Native" || return 1
    done
    # The cross-build modules select themselves per Scala 2.x version, so they are outside the plan
    # and outside both pools.
    run_watched "cross pass" "testKyo --cross $arg Native" || return 1
    return 0
}

# -- strategy derivation: the platform decides, never the caller --
# The resource monitor wraps the whole run: it reports the kyo scheduler snapshot (cross-platform) and,
# where available, an OS headline (/proc on Linux, vm_stat on macOS; scheduler-only otherwise).
# The scheduler (JVM + Native) writes its compact top line to sched_file via the topStatusFile sink;
# Native reads the flag from the environment (no -D) and forked JVMs inherit it, so exporting it here
# reaches both. ci-monitor.sh self-gates (CI_MON=0 disables); a no-op where nothing can be sampled.
monitor="$(cd "$(dirname "$0")" 2>/dev/null && pwd)/ci-monitor.sh"
sched_file="${RUNNER_TEMP:-/tmp}/kyo-sched-$PLATFORM.status"
rm -f "$sched_file"
export KYO_SCHEDULER_TOPSTATUSFILE="$sched_file"
export KYO_SCHEDULER_TOPSTATUSFILEMS=5000
KYO_SCHED_FILE="$sched_file" bash "$monitor" &
monitor_pid=$!

if [ "$PLATFORM" = "Native" ]; then
    run_native; rc=$?
else
    run_phase_split; rc=$?
fi

kill "$monitor_pid" 2>/dev/null || true
wait "$monitor_pid" 2>/dev/null || true
exit "$rc"
