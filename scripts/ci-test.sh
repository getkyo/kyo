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
# JVM, JS, and Wasm run as three separate sbt processes (compile-main, then
# compile-test, then run) so the driver never holds the whole compile heap while
# the test phase forks. Native runs single-process: the aggregate nativeLink is
# linked once upfront, then a crash-retry loop tolerates libunwind shutdown hangs
# and mid-RPC errno-104 resets. The strategy is derived from the platform; no
# caller selects it.
#
# Reads CI, SBT_TASK_LIMIT, JAVA_OPTS, and JVM_OPTS from the environment; sets
# none of them. The caller (a CI workflow, or build.sh --env podman-ci) owns the
# environment, so this one runner is correct in every environment.

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
# JVM/JS/Wasm three-phase split (compile-main, compile-test, run) on full AND
# diff; the Native single-process aggregate-link path with no --phase; the
# platform-derived strategy; the exit-code mapping; and the JS-only 14G driver
# heap bump against the shared 12G ceiling.
if [ "${1:-}" = "--self-test" ]; then
    SELF="$0"
    PASS=0; FAIL=0; TOTAL=0
    SELFDIR=$(mktemp -d)
    CALLS="$SELFDIR/calls.log"
    HEAP="$SELFDIR/heap.log"
    trap 'rm -rf "$SELFDIR"' EXIT

    # Build a fake sbt whose body is $1; every call appends its full
    # argument string to CALLS and the JAVA_OPTS it inherited to HEAP, so an
    # assertion can read both the call sequence and the heap the sbt subprocess
    # saw.
    make_fake_sbt() {
        {
            printf '#!/usr/bin/env bash\n'
            printf 'printf "%%s\\n" "$*" >> "%s"\n' "$CALLS"
            printf 'printf "%%s\\n" "${JAVA_OPTS:-}" >> "%s"\n' "$HEAP"
            printf '%s\n' "$1"
        } > "$SELFDIR/sbt"
        chmod +x "$SELFDIR/sbt"
    }

    # Run the real runner under the fake sbt. Sets CT_EXIT (the runner exit
    # code) and leaves the recorded calls in CALLS for the assertion.
    run_runner() {
        local platform="$1" action="$2" body="$3"
        : > "$CALLS"; : > "$HEAP"
        make_fake_sbt "$body"
        PATH="$SELFDIR:$PATH" MAX_RETRIES=2 STALE_TIMEOUT=2 POLL_INTERVAL=1 CI_MON=0 \
            "$SELF" "$platform" "$action" >/dev/null 2>&1
        CT_EXIT=$?
    }

    # Same as run_runner but with the CI heap env the workflow exports (the
    # shared 12G ceiling for JAVA_OPTS and JVM_OPTS), so the heap assertions
    # observe the JS-only bump against the real inherited opts.
    CI_OPTS='-Xmx12G -Xss10M -XX:+UseG1GC -XX:MaxMetaspaceSize=2G -XX:ReservedCodeCacheSize=256M -Dfile.encoding=UTF-8'
    run_runner_ci() {
        local platform="$1" action="$2" body="$3"
        : > "$CALLS"; : > "$HEAP"
        make_fake_sbt "$body"
        PATH="$SELFDIR:$PATH" MAX_RETRIES=2 STALE_TIMEOUT=2 POLL_INTERVAL=1 CI_MON=0 \
            JAVA_OPTS="$CI_OPTS" JVM_OPTS="$CI_OPTS" \
            "$SELF" "$platform" "$action" >/dev/null 2>&1
        CT_EXIT=$?
    }

    # Assertion helpers, evaluated against CT_EXIT and CALLS.
    exit_is()      { [ "$CT_EXIT" = "$1" ]; }
    calls_count()  { [ "$(wc -l < "$CALLS" | tr -d ' ')" = "$1" ]; }
    call_nth_is()  { [ "$(sed -n "${1}p" "$CALLS")" = "$2" ]; }
    calls_have()   { grep -qF -- "$1" "$CALLS"; }
    calls_lack()   { ! grep -qF -- "$1" "$CALLS"; }
    heap_saw()     { grep -qF -- "$1" "$HEAP"; }
    heap_never()   { ! grep -qF -- "$1" "$HEAP"; }

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

    # 1. JVM phase-split: three ordered processes on a full run.
    run_runner JVM test 'exit 0'
    if calls_count 3 \
       && call_nth_is 1 "testKyo --phase compile-main --all JVM" \
       && call_nth_is 2 "testKyo --phase compile-test --all JVM" \
       && call_nth_is 3 "testKyo --all JVM" && exit_is 0
    then record ok "JVM phase-split: three ordered processes (full)"
    else record no "JVM phase-split: three ordered processes (full)"; fi

    # 2. JS and Wasm take the same three-process split.
    run_runner JS test 'exit 0'
    js_ok=no
    if calls_count 3 && call_nth_is 3 "testKyo --all JS" && exit_is 0; then js_ok=yes; fi
    run_runner Wasm test 'exit 0'
    if [ "$js_ok" = yes ] && calls_count 3 && call_nth_is 3 "testKyo --all Wasm" && exit_is 0
    then record ok "JS and Wasm take the same three-process split"
    else record no "JS and Wasm take the same three-process split"; fi

    # 3. Phase-split fails fast on a compile-main failure.
    run_runner JVM test 'exit 1'
    if calls_count 1 && call_nth_is 1 "testKyo --phase compile-main --all JVM" && exit_is 1
    then record ok "phase-split fails fast on compile-main failure"
    else record no "phase-split fails fast on compile-main failure"; fi

    # 4. testDiff omits --all but still splits into three processes.
    run_runner JVM testDiff 'exit 0'
    if calls_count 3 \
       && call_nth_is 1 "testKyo --phase compile-main  JVM" \
       && call_nth_is 3 "testKyo  JVM" && exit_is 0
    then record ok "testDiff omits --all but still splits into three processes"
    else record no "testDiff omits --all but still splits into three processes"; fi

    # 5. compile action runs only the two compile phases (no run).
    run_runner JVM compile 'exit 0'
    if calls_count 2 && calls_lack "testKyo --all JVM" && exit_is 0
    then record ok "compile action runs only the two compile phases"
    else record no "compile action runs only the two compile phases"; fi

    # 6. Native links upfront before any test process.
    run_runner Native test 'echo "Tests: succeeded 100, failed 0"; exit 0'
    if call_nth_is 1 "kyoNative/Test/nativeLink" && calls_have "testKyo --all Native" && exit_is 0
    then record ok "Native links upfront before any test process"
    else record no "Native links upfront before any test process"; fi

    # 7. Native never receives a --phase argument (strategy derived from platform).
    run_runner Native test 'echo "Tests: succeeded 100, failed 0"; exit 0'
    if calls_lack "--phase compile-main" && calls_lack "--phase compile-test"
    then record ok "Native never receives a --phase argument"
    else record no "Native never receives a --phase argument"; fi

    # 8. Native link failure exits 1 before any test runs.
    run_runner Native test 'if [ "$*" = "kyoNative/Test/nativeLink" ]; then exit 3; fi; echo "Tests: succeeded 1, failed 0"; exit 0'
    if calls_count 1 && calls_lack "testKyo" && exit_is 1
    then record ok "Native link failure exits 1 before any test runs"
    else record no "Native link failure exits 1 before any test runs"; fi

    # 9-20: Native crash-retry / check_log scenarios.
    # For these the fake sbt's link call must pass, so the body branches on $*.
    nat() {  # nat <name> <expected-exit> <run-body>
        run_runner Native test "if [ \"\$*\" = \"kyoNative/Test/nativeLink\" ]; then exit 0; fi; $3"
        if exit_is "$2"; then record ok "$1"; else record no "$1"; fi
    }
    nat "clean Native pass exits 0"                 0 'echo "Tests: succeeded 100, failed 0"; exit 0'
    nat "real Native test failures exit 1"          1 'echo "Tests: succeeded 90, failed 3"; exit 1'
    nat "Native crash after a clean pass tolerated" 0 'echo "Tests: succeeded 100, failed 0"; exit 137'
    nat "Native kill before any tests exits 1"      1 'exit 137'
    nat "Native hang after pass tolerated"          0 'echo "Tests: succeeded 163, failed 0"; sleep 600'
    nat "Native hang after a failure exits 1"       1 'echo "Tests: succeeded 90, failed 2"; sleep 600'
    nat "Native hang with no output exits 1"        1 'sleep 600'
    nat "Native multi-suite all-pass exits 0"       0 'echo "Tests: succeeded 64, failed 0"
echo "Tests: succeeded 45, failed 0"
echo "Tests: succeeded 163, failed 0"; exit 0'
    nat "Native multi-suite one failure exits 1"    1 'echo "Tests: succeeded 64, failed 0"
echo "Tests: succeeded 45, failed 2"; exit 1'
    nat "Native kill mid-compile after pass exits 1" 1 'echo "Tests: succeeded 64, failed 0"
echo "[info] compiling 39 Scala sources to /target/test-classes ..."; sleep 600'
    nat "Native errno-104 mid-RPC retried then passes" 0 'if [ ! -f "'"$SELFDIR"'/rpc" ]; then touch "'"$SELFDIR"'/rpc"
echo "  - t *** FAILED *** (15 seconds)"
echo "Exception in thread \"main\" java.net.SocketException: read failed, errno: 104"
echo "    at scala.scalanative.testinterface.NativeRPC.loop(Unknown Source)"; exit 1
else rm -f "'"$SELFDIR"'/rpc"; echo "Tests: succeeded 100, failed 0"; exit 0; fi'
    rm -f "$SELFDIR/rpc"
    nat "Native FAILED without rpc crash stays a failure" 1 'echo "  - t *** FAILED *** (15 seconds)"
echo "Exception in thread \"main\" java.lang.RuntimeException: oops"; exit 1'

    # 21-22: argument validation exits 2 before any sbt.
    run_runner Frob test 'exit 0'
    if exit_is 2 && calls_count 0; then record ok "unknown platform exits 2 before any sbt"
    else record no "unknown platform exits 2 before any sbt"; fi
    run_runner JVM frob 'exit 0'
    if exit_is 2 && calls_count 0; then record ok "unknown action exits 2 before any sbt"
    else record no "unknown action exits 2 before any sbt"; fi

    # 23. JS bumps the inherited 12G driver heap to 14G for every sbt call.
    run_runner_ci JS test 'exit 0'
    if heap_saw "-Xmx14G" && heap_never "-Xmx12G" && exit_is 0
    then record ok "JS bumps the inherited driver heap to 14G"
    else record no "JS bumps the inherited driver heap to 14G"; fi

    # 24. JVM leaves the inherited 12G driver heap unchanged.
    run_runner_ci JVM test 'exit 0'
    if heap_saw "-Xmx12G" && heap_never "-Xmx14G" && exit_is 0
    then record ok "JVM leaves the inherited driver heap at 12G"
    else record no "JVM leaves the inherited driver heap at 12G"; fi

    # Negative control: a deliberately wrong expectation MUST flip FAIL,
    # proving the harness is not vacuous. Not counted in the scenario total.
    run_runner JVM test 'exit 0'
    if calls_count 99; then echo "  SELFTEST-BUG: negative control passed"; FAIL=$((FAIL+1)); fi

    echo ""
    echo "Results: $PASS/$TOTAL passed, $FAIL failed"
    [ "$FAIL" -eq 0 ] && [ "$TOTAL" -eq 24 ]
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

# The Scala.js link runs in the sbt driver heap, and the full JS test-class
# compile OOMs at the shared 12G ceiling. Raise the driver to 14G for JS only:
# each platform runs on its own runner, so JVM/Wasm stay at 12G (green there)
# and Native stays at 12G because its forked LLVM/podman/chrome alongside a 14G
# heap would overcommit the 16G box (exit 143). This mutates the inherited opts
# rather than hardcoding a second string, so the only ceiling is the caller's
# 12G. When JAVA_OPTS is unset/empty (direct mode without the CI env) the
# substitution is a no-op and the launcher's .jvmopts governs, which is correct
# for the unconstrained dev box.
if [ "$PLATFORM" = "JS" ]; then
    export JAVA_OPTS="${JAVA_OPTS:-}"; export JAVA_OPTS="${JAVA_OPTS//-Xmx12G/-Xmx14G}"
    export JVM_OPTS="${JVM_OPTS:-}"; export JVM_OPTS="${JVM_OPTS//-Xmx12G/-Xmx14G}"
fi

MAX_RETRIES=${MAX_RETRIES:-3}
STALE_TIMEOUT=${STALE_TIMEOUT:-600}
POLL_INTERVAL=${POLL_INTERVAL:-10}

log() { echo "=== [ci-test] $(date '+%H:%M:%S') $* ==="; }

# run-arg: full run sends --all, diff run sends nothing (testKyo diffs vs
# origin/main). compile/link map to the dedicated phases below.
run_arg() {
    case "$ACTION" in
        test) echo "--all" ;;
        *)    echo "" ;;
    esac
}

# -- JVM / JS / Wasm: three-process phase-split, fail-fast --
run_phase_split() {
    local arg; arg=$(run_arg)
    case "$ACTION" in
        compile)
            sbt "testKyo --phase compile-main $arg $PLATFORM" || return $?
            sbt "testKyo --phase compile-test $arg $PLATFORM" || return $?
            return 0
            ;;
        link)
            log "link is a no-op for $PLATFORM (link happens in the run phase)"
            return 0
            ;;
        *)
            sbt "testKyo --phase compile-main $arg $PLATFORM" || return $?
            sbt "testKyo --phase compile-test $arg $PLATFORM" || return $?
            sbt "testKyo $arg $PLATFORM" || return $?
            return 0
            ;;
    esac
}

# -- Native: upfront aggregate link, then crash-retry (single process) --
LOG=""
tail_pid=""
watchdog_killed=0
native_cleanup() { rm -f "$LOG"; [ -n "$tail_pid" ] && kill "$tail_pid" 2>/dev/null; }

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

# 0 pass, 1 real failure, 2 no test output.
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
    if grep -qE "Tests:" "$LOG"; then
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
            log "watchdog killed after final Tests: line: tolerating shutdown hang"
            return 0
        fi
        log "0 test failures: tolerating non-zero exit"; return 0
    fi
    return 2
}

run_native() {
    if [ "$ACTION" = "compile" ]; then
        sbt "testKyo --phase compile-main Native" || return $?
        sbt "testKyo --phase compile-test Native" || return $?
        return 0
    fi
    log "linking native test binaries: sbt kyoNative/Test/nativeLink"
    sbt "kyoNative/Test/nativeLink"
    link_exit=$?
    if [ "$link_exit" -ne 0 ]; then
        log "native linking failed (exit $link_exit)"; return 1
    fi
    [ "$ACTION" = "link" ] && { log "link complete"; return 0; }

    local arg; arg=$(run_arg)
    LOG=$(mktemp)
    trap native_cleanup EXIT
    local attempt
    for attempt in $(seq 1 "$MAX_RETRIES"); do
        log "attempt $attempt/$MAX_RETRIES running: sbt testKyo $arg Native"
        : > "$LOG"; watchdog_killed=0
        if command -v setsid >/dev/null 2>&1; then
            setsid sbt "testKyo $arg Native" >> "$LOG" 2>&1 &
        else
            sbt "testKyo $arg Native" >> "$LOG" 2>&1 &
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
        if [ "$exit_code" -eq 0 ]; then log "tests passed"; return 0; fi
        check_log; rc=$?
        [ "$rc" -le 1 ] && return "$rc"
        log "no test output: retrying..."
    done
    log "FAILED: no test output after $MAX_RETRIES attempts"
    return 1
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
