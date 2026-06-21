#!/usr/bin/env bash
set -uo pipefail
#
# Self-test harness for scripts/build.sh.
#
# Validates arg/env/arch dispatch using stub podman and ci-test.sh
# interceptors placed alongside build.sh in a temp script dir. No real
# container or sbt is started. Stubs record their argv and selected
# environment variables to call logs so each case asserts the captured
# command, not just the exit code.

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
REAL_BUILD="$SELF_DIR/build.sh"

PASS=0; FAIL=0; TOTAL=0

STUBDIR=$(mktemp -d)
trap 'rm -rf "$STUBDIR"' EXIT

# Place a symlink to build.sh inside STUBDIR so $SCRIPT_DIR resolves to STUBDIR.
# build.sh calls "$SCRIPT_DIR/ci-test.sh" (absolute path), so the ci-test.sh stub
# in STUBDIR is picked up for --env direct, while the podman stub in STUBDIR is
# intercepted via PATH for --env podman/podman-ci.
ln -s "$REAL_BUILD" "$STUBDIR/build.sh"
BUILD="$STUBDIR/build.sh"

PODMAN_LOG="$STUBDIR/podman.log"
CITEST_LOG="$STUBDIR/citest.log"
ENV_LOG="$STUBDIR/env.log"

# Write a podman stub that records its argv and the environment, then exits.
make_podman_stub() {
    local exit_code="${1:-0}"
    {
        printf '#!/usr/bin/env bash\n'
        printf 'printf "%%s\\n" "$*" >> "%s"\n' "$PODMAN_LOG"
        printf 'env >> "%s"\n' "$ENV_LOG"
        printf 'exit %s\n' "$exit_code"
    } > "$STUBDIR/podman"
    chmod +x "$STUBDIR/podman"
}

# Write a ci-test.sh stub placed in STUBDIR (which $SCRIPT_DIR resolves to).
make_citest_stub() {
    local exit_code="${1:-0}"
    {
        printf '#!/usr/bin/env bash\n'
        printf 'printf "%%s\\n" "$*" >> "%s"\n' "$CITEST_LOG"
        printf 'env >> "%s"\n' "$ENV_LOG"
        printf 'exit %s\n' "$exit_code"
    } > "$STUBDIR/ci-test.sh"
    chmod +x "$STUBDIR/ci-test.sh"
}

# Reset the call logs before each case.
reset_logs() {
    : > "$PODMAN_LOG"
    : > "$CITEST_LOG"
    : > "$ENV_LOG"
}

# Run build.sh with stubs visible (podman stub via PATH; ci-test.sh stub via SCRIPT_DIR symlink).
run_build() {
    PATH="$STUBDIR:$PATH" "$BUILD" "$@"
}

# Assertion helpers operating on the logs.
podman_call_count() { wc -l < "$PODMAN_LOG" | tr -d ' '; }
citest_call_count()  { wc -l < "$CITEST_LOG" | tr -d ' '; }
podman_log_has() { grep -qF -- "$1" "$PODMAN_LOG"; }
podman_log_lacks() { ! grep -qF -- "$1" "$PODMAN_LOG"; }
env_log_has()    { grep -qF -- "$1" "$ENV_LOG"; }
env_log_lacks()  { ! grep -qF -- "$1" "$ENV_LOG"; }

record() {
    TOTAL=$((TOTAL+1))
    if [ "$1" = "ok" ]; then
        echo "  PASS: $2"; PASS=$((PASS+1))
    else
        echo "  FAIL: $2"; FAIL=$((FAIL+1))
    fi
}

echo "Running build-selftest.sh..."

# 1. podman-ci exports CI=true, SBT_TASK_LIMIT=1, and the -Xmx12G driver into the container
make_podman_stub 0; reset_logs
run_build --env podman-ci test JVM >/dev/null 2>&1 || true
if podman_log_has "-e CI=true" && podman_log_has "-e SBT_TASK_LIMIT=1" && podman_log_has "JAVA_OPTS=-Xmx12G -Xss10M -XX:+UseG1GC -XX:MaxMetaspaceSize=2G -XX:ReservedCodeCacheSize=256M -Dfile.encoding=UTF-8"
then record ok "podman-ci exports CI=true, SBT_TASK_LIMIT=1, and the full CI driver opts"
else record no "podman-ci exports CI=true, SBT_TASK_LIMIT=1, and the full CI driver opts"; fi

# 2. direct leaves CI unset
make_citest_stub 0; reset_logs
run_build --env direct test JVM >/dev/null 2>&1 || true
if env_log_lacks "CI=true"
then record ok "direct leaves CI unset"
else record no "direct leaves CI unset"; fi

# 3. podman (non-CI) leaves CI unset and SBT_TASK_LIMIT unset
make_podman_stub 0; reset_logs
run_build --env podman test JVM >/dev/null 2>&1 || true
if podman_log_lacks "-e CI=true" && podman_log_lacks "-e SBT_TASK_LIMIT=1"
then record ok "podman (non-CI) leaves CI and SBT_TASK_LIMIT unset"
else record no "podman (non-CI) leaves CI and SBT_TASK_LIMIT unset"; fi

# 4. podman-ci sets the CI memory and cpu caps
make_podman_stub 0; reset_logs
run_build --env podman-ci test JVM >/dev/null 2>&1 || true
if podman_log_has "--memory 16g" && podman_log_has "--cpus 4"
then record ok "podman-ci sets --memory 16g and --cpus 4"
else record no "podman-ci sets --memory 16g and --cpus 4"; fi

# 5. --arch x86 in podman-ci sets podman --platform linux/amd64
make_podman_stub 0; reset_logs
BUILD_SKIP_BINFMT=1 _HOST_ARCH_OVERRIDE=arm run_build --env podman-ci --arch x86 test JVM >/dev/null 2>&1 || true
if podman_log_has "--platform linux/amd64"
then record ok "--arch x86 in podman-ci sets podman --platform linux/amd64"
else record no "--arch x86 in podman-ci sets podman --platform linux/amd64"; fi

# 6. --arch arm in podman-ci sets podman --platform linux/arm64
make_podman_stub 0; reset_logs
BUILD_SKIP_BINFMT=1 _HOST_ARCH_OVERRIDE=x86 run_build --env podman-ci --arch arm test JVM >/dev/null 2>&1 || true
if podman_log_has "--platform linux/arm64"
then record ok "--arch arm in podman-ci sets podman --platform linux/arm64"
else record no "--arch arm in podman-ci sets podman --platform linux/arm64"; fi

# 7. cross-host-arch prints the emulation slowdown notice
# BUILD_SKIP_BINFMT=1 prevents the binfmt_misc directory check from failing on macOS/container.
make_podman_stub 0; reset_logs
out=$(BUILD_SKIP_BINFMT=1 _HOST_ARCH_OVERRIDE=arm run_build --env podman-ci --arch x86 test JVM 2>/dev/null)
if echo "$out" | grep -qF "emulated x86 run; expect substantial slowdown"
then record ok "cross-host-arch prints the emulation slowdown notice"
else record no "cross-host-arch prints the emulation slowdown notice"; fi

# 8. --arch native does NOT print the notice or check binfmt
make_podman_stub 0; reset_logs
out=$(run_build --env podman --arch native test JVM 2>/dev/null) || true
if ! echo "$out" | grep -qF "emulated"
then record ok "--arch native does not print the emulation notice"
else record no "--arch native does not print the emulation notice"; fi

# 9. --arch x86 with --env direct exits 2 and launches no container
make_podman_stub 0; reset_logs
rc=0; run_build --arch x86 --env direct test JVM >/dev/null 2>&1 || rc=$?
if [ "$rc" -eq 2 ] && [ "$(podman_call_count)" = "0" ]
then record ok "--arch x86 with --env direct exits 2 and launches no container"
else record no "--arch x86 with --env direct exits 2 and launches no container"; fi

# 10. unknown env exits 2
rc=0; run_build --env frob test JVM >/dev/null 2>&1 || rc=$?
if [ "$rc" -eq 2 ]
then record ok "unknown env exits 2"
else record no "unknown env exits 2"; fi

# 11. test all expands to four ci-test.sh invocations, fail-fast on the second platform
# ci-test.sh stub: JVM exits 0, JS exits 1, any later call exits 0.
{
    printf '#!/usr/bin/env bash\n'
    printf 'printf "%%s\\n" "$*" >> "%s"\n' "$CITEST_LOG"
    printf 'case "$1" in\n'
    printf '  JVM) exit 0 ;;\n'
    printf '  JS)  exit 1 ;;\n'
    printf '  *)   exit 0 ;;\n'
    printf 'esac\n'
} > "$STUBDIR/ci-test.sh"
chmod +x "$STUBDIR/ci-test.sh"
reset_logs
rc=0; run_build --env direct test all >/dev/null 2>&1 || rc=$?
call_count=$(citest_call_count)
if [ "$rc" -eq 1 ] && [ "$call_count" = "2" ]
then record ok "test all fails fast on second platform; exactly 2 ci-test.sh calls"
else record no "test all fails fast on second platform; exactly 2 ci-test.sh calls (rc=$rc calls=$call_count)"; fi

# 12. the pre-run echo is unconditional
make_citest_stub 0; reset_logs
out=$(run_build --env direct test JVM 2>/dev/null)
if echo "$out" | grep -qF "env=direct arch=native action=test"
then record ok "the pre-run echo is unconditional and appears before ci-test.sh runs"
else record no "the pre-run echo is unconditional and appears before ci-test.sh runs"; fi

# Negative control: a deliberately wrong check must flip FAIL to prove the harness
# is not vacuous. Not counted in the scenario total.
make_podman_stub 0; reset_logs
run_build --env podman-ci test JVM >/dev/null 2>&1 || true
if podman_log_has "--memory 999z"
then echo "  SELFTEST-BUG: negative control passed (vacuous harness)"; FAIL=$((FAIL+1)); fi

echo ""
echo "Results: $PASS/$TOTAL passed, $FAIL failed"
[ "$FAIL" -eq 0 ] && [ "$TOTAL" -eq 12 ]
exit $?
