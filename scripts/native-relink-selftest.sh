#!/usr/bin/env bash
set -uo pipefail
#
# Regression guard for issue #1821: a Native test binary must still relink after CI has dropped the
# module's Scala Native work directory.
#
# Usage:
#   native-relink-selftest.sh [module]
#   native-relink-selftest.sh --self-test
#
# [module]  cross-project base name, default kyo-data (the cheapest module with a Native test binary).
#
# What it proves, in three steps against the real build:
#
#   1. CI=1 <module>Native/Test/nativeLink succeeds, and the `native-settings` hook leaves the work
#      directory holding nothing but `build-checksum`.
#   2. The mtime of one .nir on the module's classpath is bumped. That is the whole trigger: Scala
#      Native's build-skip checksum covers classpath mtimes, so the next link rebuilds, while every
#      compilation unit's NIR is byte-identical so incremental codegen considers all of them unchanged.
#   3. The relink succeeds and emits no missing-object diagnostics.
#
# Step 3 is what regressed. When the hook deleted `<workdir>/generated` but left `package2hash`, the
# incremental-codegen state naming those IR files, codegen skipped regenerating every unchanged unit
# and handed clang object paths for files nobody had written. Codegen and compilation both logged
# success; the link died on `clang: error: no such file or directory: .../<hash>.ll.o`.
#
# Reads SBT_CMD (the sbt binary, default `sbt`) from the environment; mutates nothing outside the
# module's own target directory, and touches no tracked file.

MISSING_OBJECT_RE='no such file or directory.*\.ll\.o'

# -- self-test mode (must precede argument handling) --
# Exercises this harness against a faked sbt and a faked module target tree, so each case asserts the
# verdict this script reaches for a known build outcome rather than just its own exit code: the happy
# path, a failing first link, the #1821 relink failure, a hook that stopped pruning, a relink that was
# skipped rather than rebuilt, and a missing .nir.
if [ "${1:-}" = "--self-test" ]; then
    # Absolute: the fixture symlinks this script into a temp directory, and a relative target would
    # dangle there.
    SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
    PASS=0; FAIL=0; TOTAL=0
    SELFDIR=$(mktemp -d)
    trap 'rm -rf "$SELFDIR"' EXIT

    WORKDIR_REL="kyo-data/native/target/scala-3.8.4/native-test"
    CLASSES_REL="kyo-data/native/target/scala-3.8.4/classes"

    # Build a fake repo whose layout matches what the real script discovers, plus a stub sbt whose
    # behaviour per invocation is read from a scenario file. The stub emulates the build AND the
    # `native-settings` hook, so a case can model the hook regressing as well as the link failing.
    make_fixture() {
        rm -rf "$SELFDIR/repo"
        mkdir -p "$SELFDIR/repo/scripts" "$SELFDIR/repo/$CLASSES_REL/kyo" "$SELFDIR/repo/$WORKDIR_REL"
        ln -s "$SELF" "$SELFDIR/repo/scripts/native-relink-selftest.sh"
        : > "$SELFDIR/repo/$CLASSES_REL/kyo/Sample.nir"
        : > "$SELFDIR/repo/$WORKDIR_REL/build-checksum"
        : > "$SELFDIR/repo/calls.log"
    }

    # <exit-1> <exit-2> <mode>
    #   mode=clean      both links rebuild and prune
    #   mode=stale      relink prints the #1821 missing-object errors
    #   mode=unpruned   links succeed but the hook leaves the work directory populated
    #   mode=skipped    relink short-circuits instead of rebuilding
    make_sbt_stub() {
        {
            printf '#!/usr/bin/env bash\n'
            printf 'n=$(( $(cat "%s/n" 2>/dev/null || echo 0) + 1 )); echo "$n" > "%s/n"\n' "$SELFDIR" "$SELFDIR"
            printf 'printf "%%s\\n" "$*" >> "%s/repo/calls.log"\n' "$SELFDIR"
            printf 'wd="%s/repo/%s"\n' "$SELFDIR" "$WORKDIR_REL"
            printf 'if [ "$n" = 1 ]; then code=%s; else code=%s; fi\n' "$1" "$2"
            printf 'mode=%s\n' "$3"
            printf 'if [ "$n" = 2 ] && [ "$mode" = skipped ]; then\n'
            printf '  echo "[info] Build skipped: No changes detected in build configuration and class path contents since last build."\n'
            printf '  exit "$code"\n'
            printf 'fi\n'
            printf 'echo "[info] Produced 141 LLVM IR files"\n'
            printf 'if [ "$n" = 2 ] && [ "$mode" = stale ]; then\n'
            printf '  echo "[error] clang: error: no such file or directory: $wd/generated/373c1303.ll.o"\n'
            printf 'fi\n'
            printf 'mkdir -p "$wd/generated"; : > "$wd/generated/373c1303.ll"; : > "$wd/build-checksum"\n'
            printf 'if [ "$mode" != unpruned ]; then rm -rf "$wd/generated"; fi\n'
            printf 'exit "$code"\n'
        } > "$SELFDIR/sbt"
        chmod +x "$SELFDIR/sbt"
    }

    # Run the real script against the fixture. Sets NR_EXIT.
    run_harness() {
        rm -f "$SELFDIR/n"
        ( cd "$SELFDIR/repo" && PATH="$SELFDIR:$PATH" "$SELFDIR/repo/scripts/native-relink-selftest.sh" kyo-data ) \
            >"$SELFDIR/out.log" 2>&1
        NR_EXIT=$?
    }

    exit_is()    { [ "$NR_EXIT" = "$1" ]; }
    links_run()  { [ "$(wc -l < "$SELFDIR/repo/calls.log" | tr -d ' ')" = "$1" ]; }
    out_has()    { grep -qF -- "$1" "$SELFDIR/out.log"; }

    record() {
        TOTAL=$((TOTAL+1))
        if [ "$1" = "ok" ]; then echo "  PASS: $2"; PASS=$((PASS+1))
        else echo "  FAIL: $2"; FAIL=$((FAIL+1)); fi
    }

    echo "Running native-relink-selftest.sh self-tests..."

    # 1. Both links rebuild, prune, and stay clean.
    make_fixture; make_sbt_stub 0 0 clean; run_harness
    if exit_is 0 && links_run 2; then record ok "clean relink after the intermediates drop passes"
    else record no "clean relink after the intermediates drop passes"; fi

    # 2. A failing first link aborts before the relink.
    make_fixture; make_sbt_stub 1 0 clean; run_harness
    if exit_is 1 && links_run 1; then record ok "first link failure aborts before the relink"
    else record no "first link failure aborts before the relink"; fi

    # 3. The #1821 regression: relink reports missing .ll.o objects.
    make_fixture; make_sbt_stub 0 1 stale; run_harness
    if exit_is 1 && out_has "1821"; then record ok "relink on missing .ll.o objects fails and cites #1821"
    else record no "relink on missing .ll.o objects fails and cites #1821"; fi

    # 3b. Same missing objects, but the link process still exits 0: the log alone must condemn it.
    make_fixture; make_sbt_stub 0 0 stale; run_harness
    if exit_is 1 && out_has "1821"; then record ok "missing .ll.o in the log fails even on a zero exit"
    else record no "missing .ll.o in the log fails even on a zero exit"; fi

    # 4. A hook that stopped pruning is a regression of the disk fix, not of the link.
    make_fixture; make_sbt_stub 0 0 unpruned; run_harness
    if exit_is 1 && out_has "not pruned"; then record ok "an unpruned work directory fails"
    else record no "an unpruned work directory fails"; fi

    # 5. A relink that short-circuits proves nothing, so it must not pass.
    make_fixture; make_sbt_stub 0 0 skipped; run_harness
    if exit_is 1 && out_has "did not rebuild"; then record ok "a skipped relink fails as vacuous"
    else record no "a skipped relink fails as vacuous"; fi

    # 6. No .nir on the classpath is a setup error, distinct from a build failure.
    make_fixture; make_sbt_stub 0 0 clean; rm -f "$SELFDIR/repo/$CLASSES_REL/kyo/Sample.nir"; run_harness
    if exit_is 2 && links_run 1; then record ok "a classpath with no .nir exits 2"
    else record no "a classpath with no .nir exits 2"; fi

    # Negative control: a deliberately wrong expectation MUST flip FAIL, proving the harness is not
    # vacuous. Not counted in the scenario total.
    make_fixture; make_sbt_stub 0 0 clean; run_harness
    if exit_is 1; then echo "  SELFTEST-BUG: negative control passed"; FAIL=$((FAIL+1)); fi

    echo ""
    echo "Results: $PASS/$TOTAL passed, $FAIL failed"
    [ "$FAIL" -eq 0 ] && [ "$TOTAL" -eq 7 ]
    exit $?
fi

MODULE="${1:-kyo-data}"
PROJECT="${MODULE}Native"
SBT_CMD="${SBT_CMD:-sbt}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT

log()  { echo "=== [native-relink] $* ==="; }
fail() { echo "=== [native-relink] FAILED: $* ===" >&2; exit "${2:-1}"; }

# The single sbt invocation shape under test. CI=1 is what arms the `native-settings` drop hook, which
# reads the environment rather than a setting.
link() {
    log "$1: CI=1 $SBT_CMD $PROJECT/Test/nativeLink"
    ( cd "$ROOT" && CI=1 "$SBT_CMD" "$PROJECT/Test/nativeLink" ) 2>&1 | tee "$LOG"
    return "${PIPESTATUS[0]}"
}

# Newest <module>/native/target/scala-*/<name>, so a repo carrying more than one Scala version resolves
# to the one the link just wrote.
resolve() {
    local name="$1" newest="" candidate
    for candidate in "$ROOT/$MODULE"/native/target/scala-*/"$name"; do
        [ -e "$candidate" ] || continue
        [ -z "$newest" ] && newest="$candidate"
        [ "$candidate" -nt "$newest" ] && newest="$candidate"
    done
    printf '%s' "$newest"
}

# The hook keeps build-checksum (Scala Native's build-skip state, which needs no other work directory
# file) and drops everything else. Anything left over is either the disk fix regressing or a new state
# file that outlives the IR it describes, which is how #1821 happened.
assert_pruned() {
    local workdir="$1" stage="$2" entry name saved
    local leftovers=()
    saved=$(shopt -p nullglob dotglob)
    shopt -s nullglob dotglob
    for entry in "$workdir"/*; do
        name=$(basename "$entry")
        [ "$name" = "build-checksum" ] && continue
        leftovers+=("$name")
    done
    eval "$saved"
    if [ "${#leftovers[@]}" -gt 0 ]; then
        printf '  %s\n' "${leftovers[@]}" >&2
        fail "$stage: work directory not pruned to build-checksum ($workdir)"
    fi
    log "$stage: work directory pruned to build-checksum"
}

log "module $MODULE"

link "first link" || fail "first link of $PROJECT/Test/nativeLink"

WORKDIR="$(resolve native-test)"
[ -n "$WORKDIR" ] || fail "no native-test work directory under $MODULE/native/target/scala-*"
assert_pruned "$WORKDIR" "first link"

CLASSES="$(resolve classes)"
[ -n "$CLASSES" ] || fail "no classes directory under $MODULE/native/target/scala-*" 2
NIR="$(find "$CLASSES" -name '*.nir' -type f 2>/dev/null | head -n1)"
[ -n "$NIR" ] || fail "no .nir on $MODULE's Native classpath, nothing to invalidate" 2

# Bump one classpath mtime and nothing else. Scala Native's build-skip checksum covers classpath
# mtimes, so this forces a real rebuild; the NIR bytes are untouched, so every compilation unit looks
# unchanged to incremental codegen. That combination is what a CI relink hits and what #1821 broke.
log "invalidating the build-skip checksum: touch ${NIR#"$ROOT"/}"
touch "$NIR"

link "relink" || fail "relink of $PROJECT/Test/nativeLink after the intermediates drop (see issue #1821)"

if grep -qE "$MISSING_OBJECT_RE" "$LOG"; then
    grep -E "$MISSING_OBJECT_RE" "$LOG" | sed 's/^/  /' >&2
    fail "relink referenced object files that codegen never wrote (see issue #1821)"
fi
if ! grep -q "LLVM IR files" "$LOG"; then
    fail "relink did not rebuild, so it proves nothing: the checksum invalidation stopped working"
fi
assert_pruned "$WORKDIR" "relink"

log "PASSED: $PROJECT relinks cleanly after the CI intermediates drop"
