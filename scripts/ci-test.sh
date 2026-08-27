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
# the test phase forks. Each of those processes chains its Scala passes in one
# ordered command string, so one process covers the primary version and the 2.x
# cross-builds.
#
# Native runs a pool of fresh drivers over one module plan:
#
#   1. plan       one sbt process writes the selected modules to a file
#   2. pre-link   each NATIVE_HEAVY module the plan selects, alone in its driver
#   3. link pool  the plan in batches of NATIVE_LINK_BATCH modules
#   4. test pool  the plan in batches of NATIVE_TEST_BATCH modules
#   5. cross      the Scala 2.x cross-build modules
#
# Splitting the link out of the test session works only because every process in
# the pool pins its Scala version (`--scala 3`, zero `++`), so each project resolves
# to the same effective version everywhere and zinc's analysis and Scala Native's
# "Build skipped" check both carry across the process boundary. The `native-settings`
# work-dir prune (guarding #1821 disk pressure) keeps `build-checksum` and the linked
# binary, which are the entire input to that check. A version-mismatched split, which
# is what a `++` round trip used to produce, relinks from scratch instead: that is the
# shape scala-native #2514 describes.
#
# A crash-retry loop wraps each test batch and the cross pass, tolerating libunwind
# shutdown hangs and mid-RPC errno-104 resets but never a pass that stopped short of
# its module list (see DONE_MARKER), and re-running through `testKyo --quick` so a
# retry stays scoped to what did not pass. The strategy is derived from the platform;
# no caller selects it.
#
# Between Native test batches the runner sweeps leftover containers and pins the
# per-module test-worker count; both are hygiene, neither can change a verdict.
#
# Reads CI, SBT_TASK_LIMIT, JAVA_OPTS, JVM_OPTS, NATIVE_HEAVY, NATIVE_SKIP,
# NATIVE_LINK_CPUS, NATIVE_LINK_BATCH, NATIVE_TEST_BATCH, NATIVE_WORKER_MAX, and
# CONTAINER_SWEEP from the environment; mutates none of them (the nativeLink
# invocations append -XX:ActiveProcessorCount when NATIVE_LINK_CPUS is set). The
# caller (a CI workflow, or build.sh --env podman-ci) owns the environment, so
# this one runner is correct in every environment.

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
# diff; the Native plan/pre-link/link-pool/test-pool/cross flow with its batch
# sizes and per-batch retry; the completion marker separating a finished pass
# from a truncated one; NATIVE_SKIP reaching the plan and the cross pass; the
# platform-derived strategy; the exit-code mapping; the resolution-retry
# wrapper; the NATIVE_LINK_CPUS cap reaching every link invocation and no other
# process; the per-module test-worker ceiling; and the batch-boundary container
# sweep with its host-side backstop, driven by a fake podman so the case runs on
# a machine with no container runtime.
if [ "${1:-}" = "--self-test" ]; then
    SELF="$0"
    PASS=0; FAIL=0; TOTAL=0
    SELFDIR=$(mktemp -d)
    CALLS="$SELFDIR/calls.log"
    HEAP="$SELFDIR/heap.log"
    OUT="$SELFDIR/out.log"
    PODCALLS="$SELFDIR/podman.log"
    trap 'rm -rf "$SELFDIR"' EXIT

    # The modules the fake sbt writes when the runner asks it to plan; cases
    # reassign it to shape the pools. FAKE_SKIP is the comma-separated base-name
    # list the plan writer honors, so a NATIVE_SKIP case can assert that a
    # skipped module never reaches a batch. PASS_BODY is a complete green pass:
    # test output plus the completion marker the tolerance branches require.
    FAKE_PLAN="kyo-dataNative kyo-preludeNative"
    FAKE_SKIP=""
    PASS_BODY='echo "Tests: succeeded 100, failed 0"; echo "[testKyo] completed"; exit 0'

    # Build a fake sbt whose body is $1; every call appends its full
    # argument string to CALLS and the JAVA_OPTS it inherited to HEAP, so an
    # assertion can read both the call sequence and the heap the sbt subprocess
    # saw. A --plan-file call is the planning process: it writes FAKE_PLAN minus
    # anything FAKE_SKIP names to the requested path and exits without reaching
    # the body, the way testKyo applies --exclude where selection happens.
    make_fake_sbt() {
        {
            printf '#!/usr/bin/env bash\n'
            printf 'printf "%%s\\n" "$*" >> "%s"\n' "$CALLS"
            printf 'printf "%%s\\n" "${JAVA_OPTS:-}" >> "%s"\n' "$HEAP"
            printf 'all="$*"\n'
            printf 'if [ "${all#*--plan-file }" != "$all" ]; then\n'
            printf '    rest="${all#*--plan-file }"; path="${rest%%%% *}"\n'
            printf '    : > "$path"\n'
            printf '    for m in %s; do\n' "$FAKE_PLAN"
            printf '        skipped=no\n'
            printf '        for s in $(printf "%%s" "%s" | tr "," " "); do\n' "$FAKE_SKIP"
            printf '            [ "$m" = "${s}Native" ] && skipped=yes\n'
            printf '        done\n'
            printf '        [ "$skipped" = no ] && printf "%%s\\n" "$m" >> "$path"\n'
            printf '    done\n'
            printf '    exit 0\n'
            printf 'fi\n'
            printf '%s\n' "$1"
        } > "$SELFDIR/sbt"
        chmod +x "$SELFDIR/sbt"
    }

    # A fake podman for the container-sweep cases: every invocation is recorded,
    # `ps -aq` answers from a file, and `rm -af --volumes` runs $1 — which empties
    # that file for a runtime that can remove containers, and does nothing for the
    # one whose stop/kill leave the process running.
    make_fake_podman() {
        {
            printf '#!/usr/bin/env bash\n'
            printf 'printf "%%s\\n" "$*" >> "%s"\n' "$PODCALLS"
            printf 'case "$*" in\n'
            printf '    "ps -aq") cat "%s" 2>/dev/null ;;\n' "$SELFDIR/podman-ps"
            printf '    "rm -af --volumes") %s ;;\n' "$1"
            printf '    inspect*) echo 12345 ;;\n'
            printf 'esac\n'
            printf 'exit 0\n'
        } > "$SELFDIR/podman"
        chmod +x "$SELFDIR/podman"
    }

    rm -f "$SELFDIR/podman" "$SELFDIR/podman-ps"

    # Run the real runner under the fake sbt. Sets CT_EXIT (the runner exit
    # code) and leaves the recorded calls in CALLS and the runner's own output in
    # OUT for the assertion. Trailing VAR=value pairs enter the runner's
    # environment. CONTAINER_SWEEP=0 by default so a case that does not opt in
    # can never touch a real runtime on a developer machine.
    run_runner_env() {
        local body="$1" platform="$2" action="$3"; shift 3
        : > "$CALLS"; : > "$HEAP"; : > "$OUT"; : > "$PODCALLS"
        make_fake_sbt "$body"
        env PATH="$SELFDIR:$PATH" MAX_RETRIES=2 STALE_TIMEOUT=2 POLL_INTERVAL=1 CI_MON=0 RESOLVE_BACKOFF=0 \
            CONTAINER_SWEEP=0 "$@" \
            "$SELF" "$platform" "$action" > "$OUT" 2>&1
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
    out_has()      { grep -qF -- "$1" "$OUT"; }
    out_lacks()    { ! grep -qF -- "$1" "$OUT"; }
    pod_has()      { grep -qF -- "$1" "$PODCALLS"; }
    pod_count()    { [ "$(grep -cF -- "$1" "$PODCALLS")" = "$2" ]; }
    pod_empty()    { [ ! -s "$PODCALLS" ]; }

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

    # 6. Native plans first, then links the plan in batches, before any test process, and never links
    # the kyoNative aggregate (only the planned modules are linked at all).
    run_runner Native test "$PASS_BODY"
    if calls_count 4 \
       && call_nth_has 1 "testKyo --dry-run --plan-file" && call_nth_has 1 "--scala 3 --all Native" \
       && call_nth_is 2 "testKyo --phase link --scala 3 --modules kyo-dataNative,kyo-preludeNative Native" \
       && call_nth_is 3 "-J-Xmx6G testKyo --scala 3 --modules kyo-dataNative,kyo-preludeNative Native" \
       && call_nth_is 4 "-J-Xmx6G testKyo --cross --all Native" \
       && calls_lack "kyoNative/Test/nativeLink" && exit_is 0
    then record ok "Native plans, then links the plan before any test process"
    else record no "Native plans, then links the plan before any test process"; fi

    # 7. The Native test path never sends a compile phase: the plan's link batches compile what they
    # link, so a separate upfront compile would be the duplicate work the pool exists to remove.
    run_runner Native test "$PASS_BODY"
    if calls_lack "--phase compile-main" && calls_lack "--phase compile-test"
    then record ok "the Native test path never sends a compile phase"
    else record no "the Native test path never sends a compile phase"; fi

    # 8. The Native compile action is the compile phases and nothing else: no plan, no link, no run.
    run_runner Native compile 'exit 0'
    if calls_count 2 \
       && call_nth_is 1 "testKyo --phase compile-main Native" \
       && call_nth_is 2 "testKyo --phase compile-test Native" \
       && calls_lack "--plan-file" && calls_lack "--phase link" && exit_is 0
    then record ok "the Native compile action runs the two compile phases and nothing else"
    else record no "the Native compile action runs the two compile phases and nothing else"; fi

    # 9. A link-batch failure exits 1 before any test process.
    run_runner Native test 'if [[ "$*" == *"--phase link"* ]]; then exit 3; fi
'"$PASS_BODY"
    if calls_count 2 && calls_lack "testKyo --scala 3 --modules" && calls_lack "--cross" && exit_is 1
    then record ok "a Native link-batch failure exits 1 before any test process"
    else record no "a Native link-batch failure exits 1 before any test process"; fi

    # 10. The run-phase heap cap reaches the Native test batches and the cross pass and nothing else:
    # not the plan, not the link batches, and not the JVM run (its tests run in the driver).
    run_runner JVM test 'exit 0'
    jvm_uncapped=no
    if call_nth_is 3 "testKyo --all JVM"; then jvm_uncapped=yes; fi
    run_runner Native test "$PASS_BODY"
    if [ "$jvm_uncapped" = yes ] \
       && calls_have "-J-Xmx6G testKyo --scala 3 --modules" \
       && calls_have "-J-Xmx6G testKyo --cross" \
       && calls_lack "-J-Xmx6G testKyo --dry-run" \
       && calls_lack "-J-Xmx6G testKyo --phase link"
    then record ok "run-phase heap cap: Native test batches and cross only, never plan, link, or JVM"
    else record no "run-phase heap cap: Native test batches and cross only, never plan, link, or JVM"; fi

    # 11. NATIVE_HEAVY pre-links a planned heavy module in its own process before the link pool.
    FAKE_PLAN="kyo-schema-testsNative kyo-dataNative"
    run_runner_env "$PASS_BODY" Native test NATIVE_HEAVY="kyo-schema-tests"
    if call_nth_has 1 "--plan-file" \
       && call_nth_is 2 "kyo-schema-testsNative/Test/nativeLink" \
       && call_nth_is 3 "testKyo --phase link --scala 3 --modules kyo-schema-testsNative,kyo-dataNative Native" \
       && exit_is 0
    then record ok "NATIVE_HEAVY pre-links a planned heavy module before the link pool"
    else record no "NATIVE_HEAVY pre-links a planned heavy module before the link pool"; fi

    # 12. NATIVE_LINK_CPUS caps the pre-link and every link batch, and nothing else.
    run_runner_env "$PASS_BODY" Native test NATIVE_HEAVY="kyo-schema-tests" NATIVE_LINK_CPUS=2
    if ! heap_nth_has 1 "-XX:ActiveProcessorCount=2" \
       && heap_nth_has 2 "-XX:ActiveProcessorCount=2" \
       && heap_nth_has 3 "-XX:ActiveProcessorCount=2" \
       && ! heap_nth_has 4 "-XX:ActiveProcessorCount=2" \
       && ! heap_nth_has 5 "-XX:ActiveProcessorCount=2" && exit_is 0
    then record ok "NATIVE_LINK_CPUS caps link invocations, never plan, test, or cross"
    else record no "NATIVE_LINK_CPUS caps link invocations, never plan, test, or cross"; fi

    # 13. A heavy pre-link failure aborts before the link pool and before any tests.
    run_runner_env 'if [[ "$*" == *"kyo-schema-testsNative/Test/nativeLink"* ]]; then exit 3; fi
'"$PASS_BODY" Native test NATIVE_HEAVY="kyo-schema-tests"
    if calls_count 2 && call_nth_is 2 "kyo-schema-testsNative/Test/nativeLink" \
       && calls_lack "--phase link" && calls_lack "testKyo --scala 3 --modules" && exit_is 1
    then record ok "NATIVE_HEAVY pre-link failure aborts before the link pool and tests"
    else record no "NATIVE_HEAVY pre-link failure aborts before the link pool and tests"; fi

    # 14. A NATIVE_HEAVY module the plan does not select is never pre-linked.
    FAKE_PLAN="kyo-dataNative kyo-preludeNative"
    run_runner_env "$PASS_BODY" Native test NATIVE_HEAVY="kyo-schema-tests"
    if calls_lack "kyo-schema-testsNative/Test/nativeLink" \
       && call_nth_is 2 "testKyo --phase link --scala 3 --modules kyo-dataNative,kyo-preludeNative Native" \
       && exit_is 0
    then record ok "an unplanned NATIVE_HEAVY module is not pre-linked"
    else record no "an unplanned NATIVE_HEAVY module is not pre-linked"; fi

    # 15. NATIVE_SKIP reaches the two selecting invocations, the plan and the cross pass. The batches
    # consume the already-filtered plan by exact module name, so they carry no --exclude of their own.
    FAKE_PLAN="kyo-dataNative kyo-aeronNative kyo-preludeNative"
    FAKE_SKIP="kyo-aeron"
    run_runner_env "$PASS_BODY" Native test NATIVE_SKIP="kyo-aeron,kyo-sql"
    if call_nth_has 1 "--exclude kyo-aeron,kyo-sql" \
       && call_nth_is 4 "-J-Xmx6G testKyo --cross --exclude kyo-aeron,kyo-sql --all Native" \
       && calls_lack "--modules kyo-dataNative,kyo-aeronNative" \
       && calls_lack "--only" && exit_is 0
    then record ok "NATIVE_SKIP reaches the plan and the cross pass; batches carry no --exclude"
    else record no "NATIVE_SKIP reaches the plan and the cross pass; batches carry no --exclude"; fi

    # 16. A module NATIVE_SKIP names is absent from the plan, so neither pool ever sees it.
    if call_nth_is 2 "testKyo --phase link --scala 3 --modules kyo-dataNative,kyo-preludeNative Native" \
       && call_nth_is 3 "-J-Xmx6G testKyo --scala 3 --modules kyo-dataNative,kyo-preludeNative Native" \
       && calls_lack "kyo-aeronNative"
    then record ok "a skipped module is absent from the plan and from both pools"
    else record no "a skipped module is absent from the plan and from both pools"; fi
    FAKE_SKIP=""

    # 17. An empty plan links and tests nothing, and still runs the cross pass (a diff can touch only
    # cross-built modules).
    FAKE_PLAN=""
    run_runner Native test "$PASS_BODY"
    if calls_count 2 && call_nth_is 2 "-J-Xmx6G testKyo --cross --all Native" && exit_is 0
    then record ok "an empty plan skips both pools and still runs the cross pass"
    else record no "an empty plan skips both pools and still runs the cross pass"; fi

    # 18. NATIVE_LINK_BATCH partitions the plan into ordered link processes; the link action stops there.
    FAKE_PLAN="m1Native m2Native m3Native m4Native m5Native m6Native m7Native"
    run_runner_env 'exit 0' Native link NATIVE_LINK_BATCH=3
    if calls_count 4 \
       && call_nth_is 2 "testKyo --phase link --scala 3 --modules m1Native,m2Native,m3Native Native" \
       && call_nth_is 3 "testKyo --phase link --scala 3 --modules m4Native,m5Native,m6Native Native" \
       && call_nth_is 4 "testKyo --phase link --scala 3 --modules m7Native Native" && exit_is 0
    then record ok "NATIVE_LINK_BATCH partitions the plan into ordered link processes"
    else record no "NATIVE_LINK_BATCH partitions the plan into ordered link processes"; fi

    # 19. NATIVE_TEST_BATCH partitions the test pool, and a crash retries only its own batch.
    rm -f "$SELFDIR/crash"
    run_runner_env 'if [[ "$*" == *"--phase link"* ]]; then exit 0; fi
if [[ "$*" == *"m4Native,m5Native,m6Native"* ]] && [ ! -f "'"$SELFDIR"'/crash" ]; then
    touch "'"$SELFDIR"'/crash"; exit 137
fi
'"$PASS_BODY" Native test NATIVE_TEST_BATCH=3
    rm -f "$SELFDIR/crash"
    if calls_count 7 \
       && call_nth_is 3 "-J-Xmx6G testKyo --scala 3 --modules m1Native,m2Native,m3Native Native" \
       && call_nth_is 4 "-J-Xmx6G testKyo --scala 3 --modules m4Native,m5Native,m6Native Native" \
       && call_nth_is 5 "-J-Xmx6G testKyo --scala 3 --modules m4Native,m5Native,m6Native Native --quick" \
       && call_nth_is 6 "-J-Xmx6G testKyo --scala 3 --modules m7Native Native" \
       && call_nth_is 7 "-J-Xmx6G testKyo --cross --all Native" && exit_is 0
    then record ok "a crashed test batch is retried alone, the other batches run once"
    else record no "a crashed test batch is retried alone, the other batches run once"; fi

    # 20. A clean Tests: tail without the completion marker is a truncated pass, not a green one: the
    # regression guard for a Native job that started 10 of 58 modules, went quiet, and reported success.
    FAKE_PLAN="kyo-dataNative"
    run_runner Native test 'if [[ "$*" == *"--phase link"* ]]; then exit 0; fi
echo "Tests: succeeded 100, failed 0"; exit 1'
    if calls_count 4 \
       && call_nth_is 3 "-J-Xmx6G testKyo --scala 3 --modules kyo-dataNative Native" \
       && call_nth_is 4 "-J-Xmx6G testKyo --scala 3 --modules kyo-dataNative Native --quick" \
       && calls_lack "--cross" && exit_is 1
    then record ok "a clean tail without the completion marker is retried, then fails"
    else record no "a clean tail without the completion marker is retried, then fails"; fi

    # 21-42: Native crash-retry / check_log scenarios, applied to the test batches.
    # The plan and link calls must pass, so the body branches on $*.
    FAKE_PLAN="kyo-dataNative kyo-preludeNative"
    nat() {  # nat <name> <expected-exit> <run-body>
        run_runner Native test "if [[ \"\$*\" == *'--phase link'* ]]; then exit 0; fi
$3"
        if exit_is "$2"; then record ok "$1"; else record no "$1"; fi
    }
    nat "clean Native pass exits 0"                 0 "$PASS_BODY"
    nat "real Native test failures exit 1"          1 'echo "Tests: succeeded 90, failed 3"; exit 1'
    nat "Native crash after a completed pass tolerated" 0 'echo "Tests: succeeded 100, failed 0"
echo "[testKyo] completed"; exit 137'
    nat "Native kill before any tests exits 1"      1 'exit 137'
    nat "Native hang after a completed pass tolerated" 0 'echo "Tests: succeeded 163, failed 0"
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
else rm -f "'"$SELFDIR"'/abrt"; echo "Tests: succeeded 99, failed 0"; echo "[testKyo] completed"; exit 0; fi'
    rm -f "$SELFDIR/abrt"
    nat "Native persistent teardown SIGABRT fails after retries" 1 'echo "Tests: succeeded 99, failed 0"
echo "[warn] Process /x/kyo-jsonrpc-test finished with non-zero value 134 (0x86)"; exit 134'

    # scala-native reports the same crash in raw-signal form too (SIGSEGV 11, not shell-encoded 139): the
    # concurrent-unwind crash is retried when intermittent and still fails after MAX_RETRIES when persistent.
    rm -f "$SELFDIR/segv"
    nat "Native SIGSEGV (raw signal 11) after a passing suite is retried then passes" 0 'if [ ! -f "'"$SELFDIR"'/segv" ]; then touch "'"$SELFDIR"'/segv"
echo "Tests: succeeded 75, failed 0"
echo "[warn] Process /x/kyo-schema-yaml-test finished with non-zero value 11 (0xb)"; exit 11
else rm -f "'"$SELFDIR"'/segv"; echo "Tests: succeeded 75, failed 0"; echo "[testKyo] completed"; exit 0; fi'
    rm -f "$SELFDIR/segv"
    nat "Native persistent SIGSEGV (raw signal 11) fails after retries" 1 'echo "Tests: succeeded 75, failed 0"
echo "[warn] Process /x/kyo-schema-yaml-test finished with non-zero value 11 (0xb)"; exit 11'

    # Anchor guard: a longer value that merely contains a signal digit (116 contains 11) must NOT be read
    # as SIGSEGV and pulled into the retry path. With the anchor it falls through to the post-suite
    # tolerance branch (exit 0); without the anchor it would be retried and, being persistent, fail as 1.
    nat "Native exit 116 (contains 11 but is not SIGSEGV) is not retried as a crash" 0 'echo "Tests: succeeded 75, failed 0"
echo "[testKyo] completed"
echo "[warn] Process /x/kyo-schema-yaml-test finished with non-zero value 116 (0x74)"; exit 116'

    # scala-native #4992 module-init null: a concurrent first-touch reader reads a null module instance,
    # surfacing as "null cannot be cast to <type>". Intermittent, so retried like the signal crash above;
    # a persistent occurrence still fails after MAX_RETRIES.
    rm -f "$SELFDIR/nullcast"
    nat "Native module-init null (null cannot be cast) after a passing suite is retried then passes" 0 'if [ ! -f "'"$SELFDIR"'/nullcast" ]; then touch "'"$SELFDIR"'/nullcast"
echo "Tests: succeeded 74, failed 1"
echo "  - reads scalar primitives directly from event values *** FAILED ***"
echo "java.lang.ClassCastException: null cannot be cast to scala.math.BigInt"; exit 1
else rm -f "'"$SELFDIR"'/nullcast"; echo "Tests: succeeded 75, failed 0"; echo "[testKyo] completed"; exit 0; fi'
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

    # A native crash-retry re-runs its batch through testKyo --quick: attempt 1 is the full batch, the
    # retry appends --quick so only the tests sbt did not record as passing (the crashed suites) re-run.
    # The plan invocation must not pick the flag up, so the count is exactly one.
    rm -f "$SELFDIR/qk"
    run_runner Native test 'if [[ "$*" == *"--phase link"* ]]; then exit 0; fi
if [[ "$*" == *--modules* ]] && [ ! -f "'"$SELFDIR"'/qk" ]; then touch "'"$SELFDIR"'/qk"
echo "Tests: succeeded 99, failed 0"
echo "[warn] Process /x/kyo-schema-yaml-test finished with non-zero value 134 (0x86)"; exit 134
fi
echo "Tests: succeeded 99, failed 0"; echo "[testKyo] completed"; exit 0'
    rm -f "$SELFDIR/qk"
    if exit_is 0 && [ "$(grep -c -- '--quick' "$CALLS")" = 1 ] \
       && calls_have "testKyo --scala 3 --modules kyo-dataNative,kyo-preludeNative Native --quick"
    then record ok "a crashed test batch re-runs through testKyo --quick; attempt 1 is the full batch"
    else record no "a crashed test batch re-runs through testKyo --quick; attempt 1 is the full batch"; fi

    # 47-48: the per-module test-worker ceiling.
    # One scala-native runner process per SUITE is the topology that re-provisioned a module's
    # container singletons 24 times in one CI job; one per MODULE is the fixed shape.
    FAKE_PLAN="kyo-dataNative"
    run_runner Native test 'if [[ "$*" == *"--phase link"* ]]; then exit 0; fi
echo "Starting process '"'"'/t/kyo-data-test'"'"' on port '"'"'1'"'"'."
echo "Starting process '"'"'/t/kyo-data-test'"'"' on port '"'"'2'"'"'."
echo "Starting process '"'"'/t/kyo-data-test'"'"' on port '"'"'3'"'"'."
echo "Tests: succeeded 100, failed 0"; echo "[testKyo] completed"; exit 0'
    if out_has "3 native test-runner processes for 1 module(s)" \
       && out_has "::warning title=native test workers::" && exit_is 0
    then record ok "a per-suite worker count warns without failing the batch"
    else record no "a per-suite worker count warns without failing the batch"; fi

    run_runner Native test 'if [[ "$*" == *"--phase link"* ]]; then exit 0; fi
echo "Starting process '"'"'/t/kyo-data-test'"'"' on port '"'"'1'"'"'."
echo "Starting process '"'"'/t/kyo-data-test'"'"' on port '"'"'2'"'"'."
echo "Tests: succeeded 100, failed 0"; echo "[testKyo] completed"; exit 0'
    if out_lacks "::warning title=native test workers::" \
       && out_has "native test-runner processes: 2 for 1 module(s)" && exit_is 0
    then record ok "one controller plus one worker per module does not warn"
    else record no "one controller plus one worker per module does not warn"; fi

    # 49-51: the batch-boundary container sweep.
    FAKE_PLAN="kyo-dataNative"
    printf 'aaa\nbbb\n' > "$SELFDIR/podman-ps"
    make_fake_podman ': > "'"$SELFDIR"'/podman-ps"'
    run_runner_env "$PASS_BODY" Native test CONTAINER_SWEEP=1
    if pod_has "rm -af --volumes" && pod_count "rm -af --volumes" 1 \
       && ! pod_has "inspect" && exit_is 0
    then record ok "the sweep removes a batch's containers and stops there when they go"
    else record no "the sweep removes a batch's containers and stops there when they go"; fi

    # A runtime that cannot reap a container's process leaves survivors behind `rm -af`;
    # the host-side kill is what the next batch depends on.
    printf 'aaa\nbbb\n' > "$SELFDIR/podman-ps"
    make_fake_podman ':'
    run_runner_env "$PASS_BODY" Native test CONTAINER_SWEEP=1
    if pod_has "inspect --format {{.State.Pid}} aaa" \
       && pod_count "rm -af --volumes" 2 && exit_is 0
    then record ok "survivors of rm -af are killed host-side and swept again"
    else record no "survivors of rm -af are killed host-side and swept again"; fi

    printf 'aaa\n' > "$SELFDIR/podman-ps"
    make_fake_podman ': > "'"$SELFDIR"'/podman-ps"'
    run_runner_env "$PASS_BODY" Native test
    if pod_empty && exit_is 0
    then record ok "CONTAINER_SWEEP=0 touches no container runtime at all"
    else record no "CONTAINER_SWEEP=0 touches no container runtime at all"; fi
    rm -f "$SELFDIR/podman" "$SELFDIR/podman-ps"

    # 52-53: argument validation exits 2 before any sbt.
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
    [ "$FAIL" -eq 0 ] && [ "$TOTAL" -eq 53 ]
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
# into one binary) whose SOLO native-link optimize peak needs an isolated, fresh-heap sbt driver.
# Each is linked first in its own process, ahead of the link pool, so its whole-program optimize
# never runs in a driver whose preceding modules have already filled the heap and metaspace.
# Measured before the format-module split: the then-monolithic kyo-schema alone peaked ~7.7G RSS in
# a clean process vs ~9.9G in an accumulated driver (the delta is that accumulation), which
# OOM-killed the 8G-capped Native driver. nativeLink is disk-cached per project, so the link pool
# skips a module already linked here, and a module the plan does not select is not pre-linked at
# all. Empty by default; the CI workflow sets it for the Native target.
NATIVE_HEAVY="${NATIVE_HEAVY:-}"

# Space- or comma-separated base names dropped from the Native leg ENTIRELY (the app/integration tier:
# database, messaging, container, browser/UI, and interop modules whose behavior is platform-shared and
# already covered on JVM/JS). Native link is the dominant CI cost (~90s+ per module, one link each), so
# excluding these keeps the Native rows viable. Applied as `--exclude` to the two invocations that do
# their own selection, the plan and the cross pass, so an excluded module never enters the plan and is
# therefore neither linked nor run; the batches consume the plan by exact module name and need no
# filter of their own. A KEPT module that dependsOn an excluded one still compiles that dependency's
# main on demand, so the build never breaks; only the excluded module's own tests stop running
# natively. Empty by default (so the self-test and any standalone run keep the full set); the CI
# workflow sets it for the Native target.
NATIVE_SKIP="${NATIVE_SKIP:-}"

# When non-empty, every nativeLink invocation runs with -XX:ActiveProcessorCount=$NATIVE_LINK_CPUS:
# each link batch and each NATIVE_HEAVY pre-link. The scala-native toolchain sizes its optimizer pool
# and its concurrent clang forks from availableProcessors, and that fork fleet stacked on the driver
# heap is what overcommits the 16GB CI runners. Scoped to the link invocations only; the plan process,
# the test batches, and the cross pass keep every CPU.
NATIVE_LINK_CPUS="${NATIVE_LINK_CPUS:-}"

# Modules per sbt process in the Native link and test pools. Each batch is a fresh driver, so no
# driver carries more than this many modules of heap and metaspace; the measured accumulation delta
# (+2.2G RSS) builds up around the tenth module of a shared driver. A crash then costs one batch
# instead of the whole phase. Empty or 0 runs the whole plan in one process, which is the shape a
# local run wants; the CI workflow sets both for the Native target.
NATIVE_LINK_BATCH="${NATIVE_LINK_BATCH:-}"
NATIVE_TEST_BATCH="${NATIVE_TEST_BATCH:-}"

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

# sbt for a native link invocation (a NATIVE_HEAVY pre-link or a link-pool batch): applies the
# NATIVE_LINK_CPUS cap when set. The flag is added via the invocation's environment;
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

# -- Native: plan once, then link and test that plan in a pool of fresh drivers, under crash-retry --
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

# Join the non-empty fragments of a native testKyo command with single spaces, so an unset
# NATIVE_SKIP or an empty run-arg leaves no stray double space in the command sbt receives.
native_cmd() {
    local out="" part
    for part in "$@"; do [ -n "$part" ] && out="${out:+$out }$part"; done
    printf '%s' "$out"
}

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

# 0 pass, 1 real failure, 2 no verdict (retry). Called only after a nonzero exit or a watchdog kill.
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
        # watchdog kill). That is tolerable only as a shutdown crash/hang AFTER the batch's last
        # module, with nothing left running. Each module links inside its batch's session on a
        # second run, so a later module's compile, link, or optimize, or a driver OOM-kill
        # mid-optimize (exit 137), after an earlier module's Tests: line means real work was cut
        # short. Scan the region after the last Tests: line for any work-in-progress marker and fail
        # the run when one is present, on BOTH the watchdog and the self-exit paths. Bare "[error]"
        # is deliberately excluded so a genuine post-suite shutdown crash stays tolerated.
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
        # A clean `Tests:` tail with nothing in progress only says "nothing has failed yet": it reads
        # the same whether the pass ran out its module list or died at a module boundary with modules
        # still queued, which is how a Native job that started 10 of 58 modules reported success. The
        # marker is what separates the two, so without it the pass is truncated: retry, do not
        # tolerate. The legitimate libunwind shutdown hang still passes, since the marker prints from
        # the command chain before the JVM exits.
        if ! grep -qF -- "$DONE_MARKER" "$LOG"; then
            log "clean tests but no '$DONE_MARKER': the pass stopped short of its module list"; return 2
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

# Run one sbt invocation ("$@", after the label) under the stale-output watchdog and native
# crash-retry loop: a hung process (no output for STALE_TIMEOUT) is killed and retried, a mid-RPC
# errno-104 reset is retried, a clean pass or a real test failure returns immediately. Returns 0
# (pass) or 1 (real failure, or no verdict after MAX_RETRIES). Because it wraps ONE batch, a crash
# costs that batch's modules rather than the whole test phase. The caller sets the driver heap (a
# leading -J-Xmx arg) and any NATIVE_LINK_CPUS cap via the environment; this loop just runs whatever
# sbt command it is handed.
run_watched() {
    local label="$1"; shift
    local -a base=("$@")
    local attempt
    for attempt in $(seq 1 "$MAX_RETRIES"); do
        # A retry re-runs through testKyo --quick so only the tests sbt did not record as passing run
        # again: a crashed native suite is left unrecorded and re-runs, while every module in the
        # batch that already passed is skipped, keeping the reroll off the modules the first attempt
        # cleared. The testKyo command is the final positional arg; an optional -J-Xmx heap arg
        # precedes it.
        local -a cmd=("${base[@]}")
        if [ "$attempt" -ge 2 ]; then
            local li=$(( ${#cmd[@]} - 1 ))
            case "${cmd[$li]}" in *testKyo*) cmd[$li]="${cmd[$li]} --quick" ;; esac
        fi
        log "attempt $attempt/$MAX_RETRIES $label: sbt ${cmd[*]}"
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
        if [ "$exit_code" -eq 0 ]; then log "$label passed"; return 0; fi
        check_log; rc=$?
        [ "$rc" -le 1 ] && return "$rc"
        log "no verdict from $label: retrying..."
    done
    log "FAILED: $label produced no verdict after $MAX_RETRIES attempts"
    return 1
}

# Remove every container the batch left behind, so one batch's corpses cannot tax the next.
#
# `rm -af` alone is not enough on a runtime whose stop/kill cannot reap a container's process
# (HTTP 500 "given PID did not die within timeout"): the remove fails and the container keeps
# running. The pkill backstop kills what the runtime would not, host-side, and a second sweep
# then removes the now-dead containers. Everything is best-effort and never changes the batch
# verdict: this is hygiene between batches, not a test result.
#
# CONTAINER_SWEEP=0 disables it (the self-test and any local run that wants its containers kept).
sweep_containers() {
    local when="$1"
    [ "${CONTAINER_SWEEP:-1}" = "0" ] && return 0
    command -v podman >/dev/null 2>&1 || return 0
    local before
    before=$(podman ps -aq 2>/dev/null | wc -l | tr -d ' ')
    [ "${before:-0}" = "0" ] && return 0
    log "container sweep $when: $before container(s)"
    podman rm -af --volumes >/dev/null 2>&1
    local left
    left=$(podman ps -aq 2>/dev/null | wc -l | tr -d ' ')
    if [ "${left:-0}" != "0" ]; then
        log "container sweep: $left container(s) survived rm -af; killing their processes host-side"
        local id pid
        for id in $(podman ps -aq 2>/dev/null); do
            pid=$(podman inspect --format '{{.State.Pid}}' "$id" 2>/dev/null)
            [ -n "$pid" ] && [ "$pid" != "0" ] && kill -9 "$pid" 2>/dev/null
        done
        podman rm -af --volumes >/dev/null 2>&1
        log "container sweep: $(podman ps -aq 2>/dev/null | wc -l | tr -d ' ') container(s) remain"
    fi
    return 0
}

# The scala-native TestAdapter spawns one test-runner process per sbt task thread, and sbt's
# cached task pool reaps a thread after 60s idle. With one test task per suite, every suite
# slower than a minute lands on a fresh thread and therefore a fresh runner process, each one
# re-provisioning that module's per-process fixtures: one CI module reached 24 worker processes
# and 7GB. `Test / parallelExecution := false` on Native (build.sbt, native-settings-base) makes
# it one task per module and so one worker; this is the pin that says so. A batch's log should
# carry at most one controller plus one worker per module.
#
# Warns rather than fails: the count is a build-topology invariant, not a test result, and a
# module legitimately gains a second worker if sbt ever splits its task. NATIVE_WORKER_MAX
# tunes the per-module ceiling.
check_worker_count() {
    local batch="$1" modules starts allowed
    [ -n "$LOG" ] && [ -f "$LOG" ] || return 0
    modules=$(printf '%s\n' "$batch" | tr ',' '\n' | grep -c .)
    starts=$(grep -c "Starting process" "$LOG" 2>/dev/null || echo 0)
    allowed=$(( modules * ${NATIVE_WORKER_MAX:-2} ))
    if [ "$starts" -gt "$allowed" ]; then
        log "WARNING: $starts native test-runner processes for $modules module(s) (expected at most $allowed)"
        echo "::warning title=native test workers::$starts test-runner processes started for $modules module(s); expected at most $allowed. A per-suite worker means Test / parallelExecution is back on for Native."
    else
        log "native test-runner processes: $starts for $modules module(s) (ceiling $allowed)"
    fi
    return 0
}

run_native() {
    local arg; arg=$(run_arg)

    # Comma-separated base names to drop from the Native leg (empty by default; CI sets NATIVE_SKIP).
    # Only the two invocations that select for themselves, the plan and the cross pass, take it.
    local skip_csv skip_flag
    skip_csv=$(printf '%s' "$NATIVE_SKIP" | tr -s ', ' ',' | sed 's/^,//; s/,$//')
    skip_flag=""; [ -n "$skip_csv" ] && skip_flag="--exclude $skip_csv"

    # compile: compile main and test only, no plan, no link, no run.
    if [ "$ACTION" = "compile" ]; then
        sbt_resolve_retry "$(native_cmd 'testKyo --phase compile-main' "$skip_flag" "$arg" Native)" || return $?
        sbt_resolve_retry "$(native_cmd 'testKyo --phase compile-test' "$skip_flag" "$arg" Native)" || return $?
        return 0
    fi

    trap native_cleanup EXIT

    # One selection for the whole run: the shell partitions this file, so both pools work the
    # identical module list and each batch's membership lands in the runner log.
    local plan_cmd; plan_cmd=$(native_cmd "testKyo --dry-run --plan-file $PLAN" "$skip_flag" '--scala 3' "$arg" Native)
    log "planning native modules: sbt $plan_cmd"
    sbt_resolve_retry "$plan_cmd" || { log "native planning failed"; return 1; }
    if [ ! -f "$PLAN" ]; then
        log "native planning wrote no plan file ($PLAN)"; return 1
    fi
    log "plan: $(tr '\n' ' ' < "$PLAN")"

    local heavy
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
    # The run-phase heap cap keeps headroom for the podman/chrome forks the container and browser
    # modules spawn; the plan and the links above run at the full .jvmopts heap.
    for batch in $(plan_batches "$NATIVE_TEST_BATCH"); do
        run_watched "test batch $batch" $(run_phase_heap) "testKyo --scala 3 --modules $batch Native" || return 1
        check_worker_count "$batch"
        sweep_containers "after test batch $batch"
    done
    # The cross-build modules select themselves per Scala 2.x version, so they are outside the plan
    # and outside both pools.
    run_watched "cross pass" $(run_phase_heap) "$(native_cmd 'testKyo --cross' "$skip_flag" "$arg" Native)" || return 1
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
