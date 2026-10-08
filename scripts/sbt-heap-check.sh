#!/usr/bin/env bash
# Fails when an sbt driver heap is set anywhere but scripts/sbt-heap-lib.sh, or when something starts
# sbt without going through it. A heap set elsewhere is either overridden by the launcher's precedence
# (inert, and misleading) or silently overrides the table; both have shipped before.
#
#   scripts/sbt-heap-check.sh [root]     check a tree (default: this repository)
#   scripts/sbt-heap-check.sh --self-test
#
# Rules, over .github/, scripts/, .jvmopts and .sbtopts:
#   heap-flag    no -Xmx, -Xms or -XX heap or RAM sizing flag outside sbt-heap-lib.sh
#   bare-sbt     no workflow or action line starts sbt directly instead of scripts/sbt.sh
#   script-sbt   a script that starts sbt sources sbt-heap-lib.sh or goes through scripts/sbt.sh
#   oom-exit     .jvmopts carries -XX:+ExitOnOutOfMemoryError: a driver whose task thread runs out of heap otherwise
#                stays up with nothing running until the CI job is cancelled at its time limit
# Forked JVMs configured in build.sbt (test forks, tool runners) are not drivers and are not checked.
set -uo pipefail

HEAP_FLAG='-Xm[sx][0-9]|-XX:(MaxHeapSize|InitialHeapSize|MaxRAM|MaxRAMPercentage|InitialRAMPercentage|MinRAMPercentage|MaxRAMFraction)='
# sbt as the command word: at the start of the content or after a shell separator or keyword.
SBT_START='(^|&&|\|\||[;|(]|\$\(|\bthen|\bdo|\bexec|\belse|\bsetsid|\bnohup|\btime)[[:space:]]*sbt([[:space:]]|$)'

check_tree() {
    local root="$1" found=0 f n line content
    while IFS= read -r f; do
        case "$f" in */scripts/sbt-heap-lib.sh|*/scripts/sbt-heap-check.sh) continue ;; esac
        while IFS=: read -r n line; do
            echo "heap-flag: ${f#$root/}:$n: $line"; found=1
        done < <(grep -nE -- "$HEAP_FLAG" "$f")
    done < <(find "$root/.github" "$root/scripts" -type f 2>/dev/null; for f in "$root/.jvmopts" "$root/.sbtopts"; do [ -f "$f" ] && echo "$f"; done)

    while IFS= read -r f; do
        while IFS=: read -r n line; do
            content=$(printf '%s' "$line" | sed -E 's/^[[:space:]]*(-[[:space:]]+)?((run|command):[[:space:]]*\|?)?[[:space:]]*//')
            case "$content" in '#'*) continue ;; esac
            if printf '%s' "$content" | grep -qE -- "$SBT_START"; then
                echo "bare-sbt: ${f#$root/}:$n: $line"; found=1
            fi
        done < <(grep -nE -- '(^|[^[:alnum:]_./-])sbt([[:space:]]|$)' "$f")
    done < <(find "$root/.github" -type f \( -name '*.yml' -o -name '*.yaml' \) 2>/dev/null)

    while IFS= read -r f; do
        case "$f" in */scripts/sbt.sh|*/scripts/sbt-heap-check.sh) continue ;; esac
        grep -q 'sbt-heap-lib\.sh\|scripts/sbt\.sh\|/sbt\.sh"' "$f" && continue
        while IFS=: read -r n line; do
            content=$(printf '%s' "$line" | sed -E 's/^[[:space:]]*//')
            case "$content" in '#'*) continue ;; esac
            if printf '%s' "$content" | grep -qE -- "$SBT_START"; then
                echo "script-sbt: ${f#$root/}:$n: $line"; found=1
            fi
        done < <(grep -nE -- '(^|[^[:alnum:]_./-])sbt([[:space:]]|$)' "$f")
    done < <(find "$root/scripts" -type f -name '*.sh' 2>/dev/null)

    if ! grep -qxF -- '-XX:+ExitOnOutOfMemoryError' "$root/.jvmopts" 2>/dev/null; then
        echo "oom-exit: .jvmopts: no -XX:+ExitOnOutOfMemoryError line"; found=1
    fi

    return "$found"
}

self_test() {
    local dir pass=0 fail=0 out
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN
    expect() {  # expect <name> <0|1> <needle or ''>
        out=$(check_tree "$dir"); local rc=$?
        if [ "$rc" = "$2" ] && { [ -z "$3" ] || grep -qF -- "$3" <<<"$out"; }; then
            echo "  PASS: $1"; pass=$((pass+1))
        else
            echo "  FAIL: $1 (rc=$rc)"; echo "$out" | sed 's/^/    /'; fail=$((fail+1))
        fi
    }
    reset() {
        rm -rf "$dir"/.github "$dir"/scripts "$dir"/.jvmopts "$dir"/.sbtopts
        mkdir -p "$dir/.github/workflows" "$dir/scripts"
        printf -- '-XX:+ExitOnOutOfMemoryError\n' > "$dir/.jvmopts"
    }
    echo "Running sbt-heap-check.sh self-tests..."

    reset
    printf 'steps:\n  - run: scripts/sbt.sh compile doctest\n  # sbt doctest runs here\n  - name: Set up JDK + sbt\n' > "$dir/.github/workflows/a.yml"
    printf -- '-Xss10M\n-XX:+UseG1GC\n-XX:+ExitOnOutOfMemoryError\n' > "$dir/.jvmopts"
    printf 'SBT_HEAP_RESERVE_MB=4096\nfoo() { printf -- "-J-Xmx%%sM" 1; }\n' > "$dir/scripts/sbt-heap-lib.sh"
    printf '#!/bin/bash\n. "$here/sbt-heap-lib.sh"\nsbt "$(sbt_heap tool)" x\n' > "$dir/scripts/ok.sh"
    expect "a clean tree passes" 0 ""

    reset
    printf 'env:\n  JAVA_OPTS: -Xms4G -Xmx4G\n' > "$dir/.github/workflows/a.yml"
    expect "an -Xmx in a workflow env fails" 1 "heap-flag: .github/workflows/a.yml:2:"

    reset
    printf -- '-Xmx12G\n' > "$dir/.jvmopts"
    expect "an -Xmx in .jvmopts fails" 1 "heap-flag: .jvmopts:1:"

    reset
    printf 'run() { sbt -J-Xmx6G x; }\n' > "$dir/scripts/b.sh"
    expect "a -J-Xmx in a script fails" 1 "heap-flag: scripts/b.sh:1:"

    reset
    printf -- '-XX:MaxRAMPercentage=75\n' > "$dir/.sbtopts"
    expect "a RAM-percentage sizing flag fails" 1 "heap-flag: .sbtopts:1:"

    reset
    printf 'steps:\n  - run: sbt doctest\n' > "$dir/.github/workflows/a.yml"
    expect "a workflow run: sbt fails" 1 "bare-sbt: .github/workflows/a.yml:2:"

    reset
    printf 'steps:\n  - run: |\n      if x; then\n        sbt -Dplatform=JVM y\n      fi\n' > "$dir/.github/workflows/a.yml"
    expect "a bare sbt in a multi-line run block fails" 1 "bare-sbt: .github/workflows/a.yml:4:"

    reset
    printf 'with:\n  command: rm -rf t && sbt ci-release\n' > "$dir/.github/workflows/a.yml"
    expect "an sbt after && in a command: fails" 1 "bare-sbt: .github/workflows/a.yml:2:"

    reset
    printf '#!/bin/bash\nexec sbt "$@"\n' > "$dir/scripts/c.sh"
    expect "a script starting sbt without the lib fails" 1 "script-sbt: scripts/c.sh:2:"

    reset
    printf '#!/bin/bash\n    setsid sbt "$@" &\n' > "$dir/scripts/d.sh"
    expect "a setsid-prefixed sbt counts as starting sbt" 1 "script-sbt: scripts/d.sh:2:"

    reset
    printf -- '-Xss10M\n-XX:+UseG1GC\n' > "$dir/.jvmopts"
    expect "a .jvmopts without ExitOnOutOfMemoryError fails" 1 "oom-exit: .jvmopts:"

    reset
    rm "$dir/.jvmopts"
    expect "a missing .jvmopts fails" 1 "oom-exit: .jvmopts:"

    echo "Results: $pass/$((pass+fail)) passed, $fail failed"
    [ "$fail" = 0 ]
}

if [ "${1:-}" = --self-test ]; then
    self_test; exit $?
fi
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
if check_tree "$root"; then
    echo "sbt-heap-check: every sbt driver heap comes from scripts/sbt-heap-lib.sh"
else
    echo "sbt-heap-check: set the driver heap in scripts/sbt-heap-lib.sh, start sbt through scripts/sbt.sh, and keep -XX:+ExitOnOutOfMemoryError in .jvmopts" >&2
    exit 1
fi
