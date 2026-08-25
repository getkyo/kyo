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
# the test phase forks. Native takes the same compile-upfront split and then links
# each module exactly once, inside its own test session: it compiles main and test
# (no link), runs the heavy modules isolated in a full-heap, CPU-capped driver via
# `testKyo --only`, then runs the rest in the capped aggregate driver via
# `testKyo --exclude`, under a crash-retry loop that tolerates libunwind shutdown
# hangs and mid-RPC errno-104 resets. Linking must stay inside the test session and
# must not be split into an upfront link: Scala Native's cross-invocation build-skip
# is unreliable (scala-native #2514), so an upfront link in a separate sbt process
# gets relinked from scratch by the test session, and the `native-settings` work-dir
# prune (guarding #1821 disk pressure) has by then deleted the codegen cache, making
# that second link a full re-codegen. One in-session link per module sidesteps both.
# The strategy is derived from the platform; no caller selects it.
#
# Reads CI, SBT_TASK_LIMIT, JAVA_OPTS, JVM_OPTS, NATIVE_HEAVY, and
# NATIVE_LINK_CPUS from the environment; mutates none of them (the heavy --only
# session appends -XX:ActiveProcessorCount when NATIVE_LINK_CPUS is set). The
# caller (a CI workflow, or build.sh --env podman-ci) owns the environment, so this
# one runner is correct in every environment.

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
# diff; the Native compile-upfront then single-link path (two compile phases, no
# nativeLink, the heavy module isolated via --only ahead of the --exclude
# aggregate); the platform-derived strategy; the exit-code mapping; and the
# NATIVE_LINK_CPUS cap reaching the isolated heavy session but not the compiles
# or the aggregate run.
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
        PATH="$SELFDIR:$PATH" MAX_RETRIES=2 STALE_TIMEOUT=2 POLL_INTERVAL=1 CI_MON=0 RESOLVE_BACKOFF=0 \
            "$SELF" "$platform" "$action" >/dev/null 2>&1
        CT_EXIT=$?
    }

    # Assertion helpers, evaluated against CT_EXIT and CALLS.
    exit_is()      { [ "$CT_EXIT" = "$1" ]; }
    calls_count()  { [ "$(wc -l < "$CALLS" | tr -d ' ')" = "$1" ]; }
    call_nth_is()  { [ "$(sed -n "${1}p" "$CALLS")" = "$2" ]; }
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

    # 1. JVM phase-split: three ordered processes on a full run.
    run_runner JVM test 'exit 0'
    if calls_count 3 \
       && call_nth_is 1 "testKyo --phase compile-main --all JVM" \
       && call_nth_is 2 "testKyo --phase compile-test --all JVM" \
       && call_nth_is 3 "testKyo --all JVM" && exit_is 0
    then record ok "JVM phase-split: three ordered processes (full)"
    else record no "JVM phase-split: three ordered processes (full)"; fi

    # 2. JS and Wasm take the same three-process split. Their run phase (call 3) carries the out-of-JVM
    # driver heap cap; the two compile phases do not.
    run_runner JS test 'exit 0'
    js_ok=no
    if calls_count 3 && call_nth_is 3 "-J-Xmx6G testKyo --all JS" && exit_is 0; then js_ok=yes; fi
    run_runner Wasm test 'exit 0'
    if [ "$js_ok" = yes ] && calls_count 3 && call_nth_is 3 "-J-Xmx6G testKyo --all Wasm" \
       && call_nth_is 1 "testKyo --phase compile-main --all Wasm" && exit_is 0
    then record ok "JS and Wasm take the same three-process split"
    else record no "JS and Wasm take the same three-process split"; fi

    # 2b. The run-phase heap cap is applied to the out-of-JVM targets' run process and to nothing else:
    # not the JVM run (its tests run in the driver), and not the compile phases.
    run_runner JVM test 'exit 0'
    jvm_uncapped=no
    if call_nth_is 3 "testKyo --all JVM"; then jvm_uncapped=yes; fi
    run_runner Native test 'echo "Tests: succeeded 1, failed 0"; exit 0'
    if [ "$jvm_uncapped" = yes ] \
       && calls_have "-J-Xmx6G testKyo --all Native" \
       && calls_lack "-J-Xmx6G testKyo --phase"
    then record ok "run-phase heap cap: out-of-JVM run only, never JVM or compile"
    else record no "run-phase heap cap: out-of-JVM run only, never JVM or compile"; fi

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

    # 6. Native compiles main+test upfront, then runs once, with no upfront link.
    run_runner Native test 'case "$*" in *--phase*) exit 0;; esac; echo "Tests: succeeded 100, failed 0"; exit 0'
    if calls_count 3 \
       && call_nth_is 1 "testKyo --phase compile-main --all Native" \
       && call_nth_is 2 "testKyo --phase compile-test --all Native" \
       && call_nth_is 3 "-J-Xmx6G testKyo --all Native" \
       && calls_lack "nativeLink" && exit_is 0
    then record ok "Native compiles upfront then runs once, no upfront link"
    else record no "Native compiles upfront then runs once, no upfront link"; fi

    # 7. Native fails fast on a compile-main failure, before linking or running anything.
    run_runner Native test 'case "$*" in *"--phase compile-main"*) exit 1;; esac; echo "Tests: succeeded 1, failed 0"; exit 0'
    if calls_count 1 && call_nth_is 1 "testKyo --phase compile-main --all Native" && exit_is 1
    then record ok "Native fails fast on a compile-main failure"
    else record no "Native fails fast on a compile-main failure"; fi

    # 8. NATIVE_HEAVY runs the heavy module isolated via --only, then the rest via --exclude; no link.
    : > "$CALLS"; : > "$HEAP"; make_fake_sbt 'case "$*" in *--phase*) exit 0;; esac; echo "Tests: succeeded 100, failed 0"; exit 0'
    PATH="$SELFDIR:$PATH" MAX_RETRIES=2 STALE_TIMEOUT=2 POLL_INTERVAL=1 CI_MON=0 \
        NATIVE_HEAVY="kyo-schema-tests" "$SELF" Native test >/dev/null 2>&1
    CT_EXIT=$?
    if calls_count 4 \
       && call_nth_is 3 "testKyo --only kyo-schema-tests --all Native" \
       && call_nth_is 4 "-J-Xmx6G testKyo --exclude kyo-schema-tests --all Native" \
       && calls_lack "nativeLink" && exit_is 0
    then record ok "NATIVE_HEAVY runs the heavy module via --only, then the rest via --exclude"
    else record no "NATIVE_HEAVY runs the heavy module via --only, then the rest via --exclude"; fi

    # 8a. NATIVE_LINK_CPUS caps the isolated heavy --only session, never the compiles or the aggregate run.
    : > "$CALLS"; : > "$HEAP"; make_fake_sbt 'case "$*" in *--phase*) exit 0;; esac; echo "Tests: succeeded 100, failed 0"; exit 0'
    PATH="$SELFDIR:$PATH" MAX_RETRIES=2 STALE_TIMEOUT=2 POLL_INTERVAL=1 CI_MON=0 \
        NATIVE_HEAVY="kyo-schema-tests" NATIVE_LINK_CPUS=2 "$SELF" Native test >/dev/null 2>&1
    CT_EXIT=$?
    if ! heap_nth_has 1 "-XX:ActiveProcessorCount=2" && ! heap_nth_has 2 "-XX:ActiveProcessorCount=2" \
       && heap_nth_has 3 "-XX:ActiveProcessorCount=2" && ! heap_nth_has 4 "-XX:ActiveProcessorCount=2" \
       && exit_is 0
    then record ok "NATIVE_LINK_CPUS caps the heavy --only session, never the compiles or aggregate"
    else record no "NATIVE_LINK_CPUS caps the heavy --only session, never the compiles or aggregate"; fi

    # 8b. A heavy --only failure aborts before the --exclude aggregate run.
    : > "$CALLS"; : > "$HEAP"
    make_fake_sbt 'case "$*" in *--phase*) exit 0;; *--only*) echo "Tests: succeeded 5, failed 1"; exit 1;; esac; echo "Tests: succeeded 100, failed 0"; exit 0'
    PATH="$SELFDIR:$PATH" MAX_RETRIES=2 STALE_TIMEOUT=2 POLL_INTERVAL=1 CI_MON=0 \
        NATIVE_HEAVY="kyo-schema-tests" "$SELF" Native test >/dev/null 2>&1
    CT_EXIT=$?
    if calls_count 3 && call_nth_is 3 "testKyo --only kyo-schema-tests --all Native" \
       && calls_lack "--exclude" && exit_is 1
    then record ok "a heavy --only failure aborts before the --exclude aggregate run"
    else record no "a heavy --only failure aborts before the --exclude aggregate run"; fi

    # 9-20: Native crash-retry / check_log scenarios.
    # The two compile phases must pass, so the body exits 0 for any --phase call and applies the
    # scenario ($3) to the aggregate run.
    nat() {  # nat <name> <expected-exit> <run-body>
        run_runner Native test "case \"\$*\" in *--phase*) exit 0;; esac; $3"
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

    # A nonzero exit or watchdog kill with a link/optimize phase after the last passing suite is a
    # module cut short (a mid-run link OOM is exit 137), not a post-suite shutdown crash: it fails.
    nat "Native link OOM (exit 137) after a suite passes is a failure" 1 'echo "Tests: succeeded 64, failed 0"
echo "[info] Generating intermediate code (5000 ms)"; exit 137'
    nat "Native watchdog kill mid-optimize after a suite is a failure" 1 'echo "Tests: succeeded 64, failed 0"
echo "[info] Optimizing (debug mode) (4000 ms)"; sleep 600'

    # An intermittent native teardown crash (SIGABRT 134 after a passing suite, the known kyo-core
    # finalizer-on-interrupt issue) is retried; a persistent one still fails after MAX_RETRIES.
    nat "Native teardown SIGABRT after a passing suite is retried then passes" 0 'if [ ! -f "'"$SELFDIR"'/abrt" ]; then touch "'"$SELFDIR"'/abrt"
echo "Tests: succeeded 99, failed 0"
echo "[warn] Process /x/kyo-jsonrpc-test finished with non-zero value 134 (0x86)"; exit 134
else rm -f "'"$SELFDIR"'/abrt"; echo "Tests: succeeded 99, failed 0"; exit 0; fi'
    rm -f "$SELFDIR/abrt"
    nat "Native persistent teardown SIGABRT fails after retries" 1 'echo "Tests: succeeded 99, failed 0"
echo "[warn] Process /x/kyo-jsonrpc-test finished with non-zero value 134 (0x86)"; exit 134'

    # scala-native reports the same crash in raw-signal form too (SIGSEGV 11, not shell-encoded 139): the
    # concurrent-unwind crash is retried when intermittent and still fails after MAX_RETRIES when persistent.
    rm -f "$SELFDIR/segv"
    nat "Native SIGSEGV (raw signal 11) after a passing suite is retried then passes" 0 'if [ ! -f "'"$SELFDIR"'/segv" ]; then touch "'"$SELFDIR"'/segv"
echo "Tests: succeeded 75, failed 0"
echo "[warn] Process /x/kyo-schema-yaml-test finished with non-zero value 11 (0xb)"; exit 11
else rm -f "'"$SELFDIR"'/segv"; echo "Tests: succeeded 75, failed 0"; exit 0; fi'
    rm -f "$SELFDIR/segv"
    nat "Native persistent SIGSEGV (raw signal 11) fails after retries" 1 'echo "Tests: succeeded 75, failed 0"
echo "[warn] Process /x/kyo-schema-yaml-test finished with non-zero value 11 (0xb)"; exit 11'

    # Anchor guard: a longer value that merely contains a signal digit (116 contains 11) must NOT be read
    # as SIGSEGV and pulled into the retry path. With the anchor it falls through to the post-suite
    # tolerance branch (exit 0); without the anchor it would be retried and, being persistent, fail as 1.
    nat "Native exit 116 (contains 11 but is not SIGSEGV) is not retried as a crash" 0 'echo "Tests: succeeded 75, failed 0"
echo "[warn] Process /x/kyo-schema-yaml-test finished with non-zero value 116 (0x74)"; exit 116'

    # scala-native #4992 module-init null: a concurrent first-touch reader reads a null module instance,
    # surfacing as "null cannot be cast to <type>". Intermittent, so retried like the signal crash above;
    # a persistent occurrence still fails after MAX_RETRIES.
    rm -f "$SELFDIR/nullcast"
    nat "Native module-init null (null cannot be cast) after a passing suite is retried then passes" 0 'if [ ! -f "'"$SELFDIR"'/nullcast" ]; then touch "'"$SELFDIR"'/nullcast"
echo "Tests: succeeded 74, failed 1"
echo "  - reads scalar primitives directly from event values *** FAILED ***"
echo "java.lang.ClassCastException: null cannot be cast to scala.math.BigInt"; exit 1
else rm -f "'"$SELFDIR"'/nullcast"; echo "Tests: succeeded 75, failed 0"; exit 0; fi'
    rm -f "$SELFDIR/nullcast"
    nat "Native persistent module-init null fails after retries" 1 'echo "Tests: succeeded 74, failed 1"
echo "java.lang.ClassCastException: null cannot be cast to scala.math.BigInt"; exit 1'

    # Narrowness guard: a genuine type mismatch reads "<Type> cannot be cast to <Other>" (never "null"), so
    # it must NOT be pulled into the module-init-null retry; it stays a hard failure.
    nat "Native real ClassCastException (not null) stays a failure" 1 'echo "Tests: succeeded 74, failed 1"
echo "  - t *** FAILED ***"
echo "java.lang.ClassCastException: class kyo.Foo cannot be cast to class kyo.Bar"; exit 1'

    # Transient resolution error (Central 403/5xx) on a compile phase is retried with backoff and passes on
    # the retry. Exercised via the JVM phase-split; the same wrapper guards every platform.
    rm -f "$SELFDIR/resolv"
    run_runner JVM test 'case "$*" in *"--phase compile-main"*)
if [ ! -f "'"$SELFDIR"'/resolv" ]; then touch "'"$SELFDIR"'/resolv"
echo "[error] Error downloading org.scala-native:nativelib_native0.5_2.13:0.5.12"
echo "[error]   forbidden: https://repo1.maven.org/maven2/org/scala-native/nativelib.pom"; exit 1; fi ;;
esac
echo "Tests: succeeded 100, failed 0"; exit 0'
    rm -f "$SELFDIR/resolv"
    if exit_is 0 && [ "$(grep -c -- '--phase compile-main' "$CALLS")" = 2 ]
    then record ok "transient resolution 403 on compile-main is retried then passes"
    else record no "transient resolution 403 on compile-main is retried then passes"; fi

    # Persistent resolution failure still fails after the retries (MAX_RETRIES=2 in the self-test).
    run_runner JVM test 'case "$*" in *"--phase compile-main"*)
echo "[error] Error downloading org.scala-native:nativelib_native0.5_2.13:0.5.12"
echo "[error]   forbidden: https://repo1.maven.org/maven2/org/scala-native/nativelib.pom"; exit 1 ;;
esac
echo "Tests: succeeded 100, failed 0"; exit 0'
    if exit_is 1 && [ "$(grep -c -- '--phase compile-main' "$CALLS")" = 2 ]
    then record ok "persistent resolution failure fails after retries"
    else record no "persistent resolution failure fails after retries"; fi

    # Narrowness guard: a real compile error carries no resolution signature, so it is NOT retried and
    # fails fast on the first attempt.
    run_runner JVM test 'case "$*" in *"--phase compile-main"*)
echo "[error] ./kyo/Foo.scala:10:5: type mismatch; found Int required String"; exit 1 ;;
esac
echo "Tests: succeeded 100, failed 0"; exit 0'
    if exit_is 1 && [ "$(grep -c -- '--phase compile-main' "$CALLS")" = 1 ]
    then record ok "a real compile error is not retried (no resolution signature)"
    else record no "a real compile error is not retried (no resolution signature)"; fi

    # A native crash-retry re-runs through testKyo --quick: attempt 1 is the full run, the retry appends
    # --quick so only the tests sbt did not record as passed (the crashed suites) re-run.
    rm -f "$SELFDIR/qk"
    run_runner Native test 'case "$*" in *--phase*) exit 0;; esac
if [ ! -f "'"$SELFDIR"'/qk" ]; then touch "'"$SELFDIR"'/qk"
echo "Tests: succeeded 99, failed 0"
echo "[warn] Process /x/kyo-schema-yaml-test finished with non-zero value 134 (0x86)"; exit 134
else rm -f "'"$SELFDIR"'/qk"; echo "Tests: succeeded 99, failed 0"; exit 0; fi'
    rm -f "$SELFDIR/qk"
    if exit_is 0 && [ "$(grep -c -- '--quick' "$CALLS")" = 1 ] && tail -1 "$CALLS" | grep -q -- '--quick'
    then record ok "native crash-retry re-runs through testKyo --quick; attempt 1 is the full run"
    else record no "native crash-retry re-runs through testKyo --quick; attempt 1 is the full run"; fi

    # 21-22: argument validation exits 2 before any sbt.
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
    [ "$FAIL" -eq 0 ] && [ "$TOTAL" -eq 39 ]
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
# Backoff base in seconds between dependency-resolution retries, scaled by attempt number. The self-test
# overrides it to 0 so the retry path runs instantly.
RESOLVE_BACKOFF=${RESOLVE_BACKOFF:-20}

# Space-separated module names (e.g. "kyo-schema-tests", which links every serialization format
# into one binary) whose whole-program native-link optimize peak needs an isolated, full-heap sbt
# driver. Each runs its link+test in its own process via `testKyo --only`, keeping that optimize off
# the aggregate driver, which is heap-capped for fork headroom (run_phase_heap, below) and would OOM
# under it. Measured before the format-module split: the then-monolithic kyo-schema alone peaked
# ~7.7G RSS in a clean process vs ~9.9G stacked on an accumulated aggregate driver, which OOM-killed
# the capped Native driver. The aggregate run excludes these via `testKyo --exclude`, so each is
# linked and run exactly once. Empty by default; the CI workflow sets it for the Native target.
NATIVE_HEAVY="${NATIVE_HEAVY:-}"

# When non-empty, the isolated heavy-module native-link drivers run with
# -XX:ActiveProcessorCount=$NATIVE_LINK_CPUS: the heavy `testKyo --only` link+test session (test path)
# and the standalone pre-links (link action). The scala-native toolchain sizes its optimizer pool and
# its concurrent clang forks from availableProcessors, and that fork fleet stacked on the driver heap
# is what overcommits the 16GB CI runners. The compile phases and the aggregate run keep every CPU.
NATIVE_LINK_CPUS="${NATIVE_LINK_CPUS:-}"

log() { echo "=== [ci-test] $(date '+%H:%M:%S') $* ==="; }

# A transient Maven Central error (403/429/5xx) during resolution fails an sbt phase before any build
# output. Retry with backoff on that signature; a real compile error or unresolvable version carries no
# such marker (or reproduces every attempt) and still fails. tee keeps output streaming for the console
# and native watchdog. Compile and link route through here; the run phase retries a no-Tests failure in
# check_log.
sbt_resolve_retry() {
    local attempt=1 rc tmp
    tmp="$(mktemp)"
    while :; do
        sbt "$@" 2>&1 | tee "$tmp"
        rc=${PIPESTATUS[0]}
        if [ "$rc" -eq 0 ]; then rm -f "$tmp"; return 0; fi
        if [ "$attempt" -lt "$MAX_RETRIES" ] &&
            grep -qE 'Error downloading|[Ff]orbidden: https?://|Server returned HTTP response code: (403|429|50[0-9])|download error' "$tmp"; then
            log "transient dependency-resolution failure (attempt $attempt/$MAX_RETRIES): retrying in $((attempt * RESOLVE_BACKOFF))s"
            sleep "$((attempt * RESOLVE_BACKOFF))"
            attempt=$((attempt + 1))
            continue
        fi
        rm -f "$tmp"; return "$rc"
    done
}

# sbt for a standalone native link invocation (the link action's pre-links and aggregate link):
# applies the NATIVE_LINK_CPUS cap when set. The flag is added via the invocation's environment;
# .jvmopts only overrides flags it duplicates, so a flag that appears only here always reaches the JVM.
# Routes through sbt_resolve_retry so a transient resolution failure at link time is retried too.
link_sbt() {
    if [ -n "$NATIVE_LINK_CPUS" ]; then
        JAVA_OPTS="${JAVA_OPTS:-} -XX:ActiveProcessorCount=$NATIVE_LINK_CPUS" \
        JVM_OPTS="${JVM_OPTS:-} -XX:ActiveProcessorCount=$NATIVE_LINK_CPUS" \
            sbt_resolve_retry "$@"
    else
        sbt_resolve_retry "$@"
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

# Run-phase driver heap cap for the out-of-JVM targets. JS/Wasm run in Node and Native runs the linked
# binary, so the run-phase driver holds no test heap, yet .jvmopts pins -Xmx12G for the compile phases. On
# a 16GB runner a 12GB run-phase driver (measured 9-11GB RSS) leaves under 1GB for the Node/Wasm runtime
# plus podman, so kyo-pod container suites hit `sh: Cannot fork` (EAGAIN) and OOM kills. Cap it for those
# targets; JVM and the compile/heavy-link phases keep the full heap (they are the heap-heavy ones).
# `-J-Xmx` is appended after .jvmopts so it wins. Overridable via RUN_HEAP_CAP.
RUN_HEAP_CAP="${RUN_HEAP_CAP:-6G}"
run_phase_heap() {
    [ "$PLATFORM" = JVM ] && return 0
    printf -- '-J-Xmx%s' "$RUN_HEAP_CAP"
}

# -- JVM / JS / Wasm: three-process phase-split, fail-fast --
run_phase_split() {
    local arg; arg=$(run_arg)
    case "$ACTION" in
        compile)
            sbt_resolve_retry "testKyo --phase compile-main $arg $PLATFORM" || return $?
            sbt_resolve_retry "testKyo --phase compile-test $arg $PLATFORM" || return $?
            return 0
            ;;
        link)
            log "link is a no-op for $PLATFORM (link happens in the run phase)"
            return 0
            ;;
        *)
            sbt_resolve_retry "testKyo --phase compile-main $arg $PLATFORM" || return $?
            sbt_resolve_retry "testKyo --phase compile-test $arg $PLATFORM" || return $?
            sbt $(run_phase_heap) "testKyo $arg $PLATFORM" || return $?
            return 0
            ;;
    esac
}

# -- Native: compile upfront, then link+run each module once (heavy isolated), under crash-retry --
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

# 0 pass, 1 real failure, 2 no test output. Called only after a nonzero exit or a watchdog kill.
check_log() {
    if crashed_native_runner; then
        log "native test runner crashed mid-RPC (errno 104): retrying"
        return 2
    fi
    # scala-native #4992: a concurrent first-touch reader can read a module's instance field before it is
    # written (null), surfacing as "null cannot be cast to <type>". Retry like a signal crash: intermittent
    # upstream race, not a kyo defect. Narrow so a real mismatch ("<Type> cannot be cast to <Other>", never
    # "null") is never masked; a deterministic hit still fails after MAX_RETRIES. Checked before the FAILED
    # returns because the null arrives as an ordinary test failure, not a crash.
    if grep -qE "null cannot be cast to" "$LOG"; then
        log "native module-init null observed (scala-native #4992): retrying"; return 2
    fi
    if grep -qE "Tests:.*failed [1-9]" "$LOG"; then
        log "tests FAILED (real test failures detected)"; return 1
    fi
    if grep -qE "\*\*\* FAILED \*\*\*" "$LOG"; then
        log "tests FAILED (individual test failures detected)"; return 1
    fi
    # A native binary that exits on a crash SIGNAL with no test failures above is an intermittent crash to
    # retry (scala-native's DWARF unwinder null-derefs during a concurrent stack walk). scala-native reports
    # the signal two ways: raw (SIGABRT 6, SIGBUS 7, SIGSEGV 11) or shell-encoded 128+signal (134, 135, 139);
    # match both. The trailing anchor stops a longer value (116, 1394) partial-matching an alternative. OOM
    # (SIGKILL 9/137) is excluded so it stays a hard failure via the mid-run scan below. A deterministic
    # crash still fails after MAX_RETRIES.
    if grep -qE "finished with non-zero value (6|7|11|134|135|139)([^0-9]|$)" "$LOG"; then
        log "native test binary crashed on a signal (not OOM): retrying"; return 2
    fi
    if grep -qE "Tests:" "$LOG"; then
        # At least one suite passed and none failed, yet the process still died (nonzero exit or a
        # watchdog kill). That is tolerable only as a shutdown crash/hang AFTER the last suite, with
        # nothing left running. Each module now links inside the run, so a later module's compile,
        # link, or optimize, or a driver OOM-kill mid-optimize (exit 137), after an earlier module's
        # Tests: line means real work was cut short. Scan the region after the last Tests: line for any
        # work-in-progress marker and fail the run when one is present, on BOTH the watchdog and the
        # self-exit paths. Bare "[error]" is deliberately excluded so a genuine post-suite shutdown
        # crash stays tolerated.
        last_test_line=$(grep -nE "Tests:" "$LOG" | tail -1 | cut -d: -f1)
        post_test=""
        if [ -n "$last_test_line" ]; then
            post_test=$(tail -n +$((last_test_line + 1)) "$LOG" \
                | grep -E "compiling [0-9]+ Scala source|Linking \(|Linking native code|Discovered [0-9]+ classes|Optimizing|Generating intermediate code|Compiling to native code|Produced [0-9]+ (LLVM IR )?files|Test / nativeLink|^\[info\] [A-Z][a-zA-Z]+(Test|Suite):" \
                | head -1)
        fi
        if [ -n "$post_test" ]; then
            log "process died with work in progress after the last suite: $post_test"; return 1
        fi
        if [ "$watchdog_killed" -eq 1 ]; then
            log "watchdog killed after the final Tests: line: tolerating shutdown hang"
        else
            log "0 test failures and nothing in progress after the last suite: tolerating non-zero exit"
        fi
        return 0
    fi
    return 2
}

# Run one sbt invocation ("$@") under the stale-output watchdog and native crash-retry loop: a hung
# process (no output for STALE_TIMEOUT) is killed and retried, a mid-RPC errno-104 reset is retried, a
# clean pass or a real test failure returns immediately. Returns 0 (pass) or 1 (real failure, or no test
# output after MAX_RETRIES). The caller sets the driver heap (a leading -J-Xmx arg) and any
# NATIVE_LINK_CPUS cap via the environment; this loop just runs whatever sbt command it is handed.
run_native_retry() {
    LOG=$(mktemp)
    trap native_cleanup EXIT
    local attempt
    for attempt in $(seq 1 "$MAX_RETRIES"); do
        # A retry re-runs through testKyo --quick so only the tests sbt did not record as passed run
        # again: a crashed native suite is left unrecorded and re-runs, while every module that already
        # passed is skipped, keeping the reroll off the whole module set the first attempt cleared (a
        # per-process-startup crash rerolled across ~50 modules never converges). The testKyo command is
        # the final positional arg; an optional -J-Xmx heap arg precedes it.
        local -a cmd=("$@")
        if [ "$attempt" -ge 2 ]; then
            local li=$(( ${#cmd[@]} - 1 ))
            case "${cmd[$li]}" in *testKyo*) cmd[$li]="${cmd[$li]} --quick" ;; esac
        fi
        log "attempt $attempt/$MAX_RETRIES running: sbt ${cmd[*]}"
        : > "$LOG"; watchdog_killed=0
        if command -v setsid >/dev/null 2>&1; then
            setsid sbt "${cmd[@]}" >> "$LOG" 2>&1 &
        else
            sbt "${cmd[@]}" >> "$LOG" 2>&1 &
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

run_native() {
    local arg; arg=$(run_arg)

    # compile: compile main and test only, no link, no run.
    if [ "$ACTION" = "compile" ]; then
        sbt_resolve_retry "testKyo --phase compile-main $arg Native" || return $?
        sbt_resolve_retry "testKyo --phase compile-test $arg Native" || return $?
        return 0
    fi

    # link: compile + a standalone aggregate link, no run. This is the local/standalone
    # link-validation action (build.sh); CI's Native path is the 'test' action below, which links
    # inside the test session instead. NATIVE_HEAVY still gets its own isolated driver here.
    if [ "$ACTION" = "link" ]; then
        for heavy in $NATIVE_HEAVY; do
            log "pre-linking heavy native module in an isolated driver: sbt ${heavy}Native/Test/nativeLink"
            link_sbt "${heavy}Native/Test/nativeLink" || { log "native pre-link of $heavy failed"; return 1; }
        done
        log "linking native test binaries: sbt kyoNative/Test/nativeLink"
        link_sbt "kyoNative/Test/nativeLink" || { log "native linking failed"; return 1; }
        log "link complete"; return 0
    fi

    # test / testDiff: single-link. Compile upfront (no link) so a compile error fails fast, then run
    # the heavy modules isolated and the rest in the aggregate. Each module links exactly once, inside
    # its test session (see the file header on scala-native #2514 / #1822).
    sbt_resolve_retry "testKyo --phase compile-main $arg Native" || return $?
    sbt_resolve_retry "testKyo --phase compile-test $arg Native" || return $?

    # Comma-separated base names for the --only / --exclude split (NATIVE_HEAVY is space-separated).
    local heavy_csv; heavy_csv=$(printf '%s' "$NATIVE_HEAVY" | tr -s ' ' ',' | sed 's/^,//; s/,$//')
    if [ -n "$heavy_csv" ]; then
        # Heavy modules: link+run in their own full-heap driver (the .jvmopts 12G, uncapped) so the
        # whole-program optimize does not stack on the aggregate's accumulated heap, CPU-capped so the
        # clang fork fleet does not overcommit the runner. --only is diff-gated by testKyo, so in a diff
        # run the heavy runs only when it is actually affected.
        log "linking+running heavy native modules isolated: sbt testKyo --only $heavy_csv $arg Native"
        (
            if [ -n "$NATIVE_LINK_CPUS" ]; then
                export JAVA_OPTS="${JAVA_OPTS:-} -XX:ActiveProcessorCount=$NATIVE_LINK_CPUS"
                export JVM_OPTS="${JVM_OPTS:-} -XX:ActiveProcessorCount=$NATIVE_LINK_CPUS"
            fi
            run_native_retry "testKyo --only $heavy_csv $arg Native"
        ) || return $?
        # The rest: the run-phase heap cap keeps headroom for the podman/chrome forks the container and
        # browser modules spawn; every heavy module is excluded, already run above.
        log "linking+running remaining native modules: sbt testKyo --exclude $heavy_csv $arg Native"
        run_native_retry $(run_phase_heap) "testKyo --exclude $heavy_csv $arg Native"
    else
        run_native_retry $(run_phase_heap) "testKyo $arg Native"
    fi
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
