#!/usr/bin/env bash
set -uo pipefail
#
# The single definition of "the tree is formatted": what the checks workflow enforces, the pre-push hook
# checks, and a contributor runs before pushing.
#
# Usage:
#   format.sh [--check] [--changed [<base>]]
#   format.sh --self-test
#
# With no options it formats the whole tree in one sbt session: every source set of the four platform
# aggregates (the in-tree sbt plugins are aggregated by kyoJVM), the build definition, and the scala blocks
# of every README.
#
# --changed limits the run to the files that differ from the merge base with <base> (origin/main when
# omitted), committed or not: changed .scala sources go through scalafmtOnly, a changed build definition
# through scalafmtSbt, and changed Markdown through doctestFormat. A change to .scalafmt.conf, or to the
# scalafmt version anything pins, reformats everything, so it falls back to the whole tree.
#
# --check fails when the run changed any file, names those files and prints their diff. The formatted
# content stays in the working tree, ready to commit.

ALL_TASKS=(kyoJVM/scalafmtAll kyoJS/scalafmtAll kyoNative/scalafmtAll kyoWasm/scalafmtAll scalafmtSbt doctestFormat)

. "$(cd "$(dirname "$0")" && pwd)/sbt-heap-lib.sh"

usage() {
    echo "Usage: format.sh [--check] [--changed [<base>]]" >&2
    echo "       format.sh --self-test" >&2
}

# One line per dirty or untracked file, plus every path in the earlier snapshot $1: its blob hash (or
# "missing") and its path. Carrying the earlier paths forward is what catches a dirty file the run formats
# back to its committed content, which would otherwise drop out of the dirty set unseen.
snapshot() {
    {
        git ls-files -z --modified --others --exclude-standard
        printf '%s\n' "${1:-}" | sed '/^$/d' | cut -d' ' -f2- | tr '\n' '\0'
    } | sort -zu | while IFS= read -r -d '' path; do
        if [ -f "$path" ]; then
            printf '%s %s\n' "$(git hash-object -- "$path")" "$path"
        else
            printf 'missing %s\n' "$path"
        fi
    done
}

# The paths whose line in snapshot $2 is not in snapshot $1: the files the run wrote.
changed_paths() {
    comm -13 <(printf '%s\n' "$1" | sed '/^$/d' | LC_ALL=C sort) <(printf '%s\n' "$2" | sed '/^$/d' | LC_ALL=C sort) |
        cut -d' ' -f2-
}

# The tasks for the files changed against the merge base with $1, one per line; empty when nothing
# formattable changed. Prints ALL to request the whole tree.
changed_tasks() {
    local base merge files
    base=$1
    merge=$(git merge-base HEAD "$base") || {
        echo "format.sh: no merge base with $base; fetch it or name another base" >&2
        return 2
    }
    files=$(
        {
            git diff --name-only --diff-filter=ACMR "$merge"
            git ls-files --others --exclude-standard
        } | sort -u
    )
    [ -n "$files" ] || return 0
    if printf '%s\n' "$files" | grep -qxE '\.scalafmt\.conf|project/plugins\.sbt'; then
        echo ALL
        return 0
    fi
    local scala sbt md
    scala=$(printf '%s\n' "$files" | grep -E '\.scala$' | grep -vE '^project/[^/]*\.scala$' | while IFS= read -r f; do
        [ -f "$f" ] && printf '%s ' "$PWD/$f"
    done)
    sbt=$(printf '%s\n' "$files" | grep -E '\.sbt$|^project/[^/]*\.scala$' | head -1)
    md=$(printf '%s\n' "$files" | grep -E '\.md$' | head -1)
    # scalafmtOnly formats the files it is given whatever project runs it, and it aggregates: unscoped, every
    # aggregated project formats the same files at once, and the concurrent writes can truncate a file to
    # nothing. One leaf project runs it once.
    [ -z "$scala" ] || echo "kyo-dataJVM/scalafmtOnly ${scala% }"
    [ -z "$sbt" ] || echo scalafmtSbt
    [ -z "$md" ] || echo doctestFormat
}

run_format() {
    local check=$1 changed=$2 base=$3
    local root
    root=$(git rev-parse --show-toplevel) || return 2
    cd "$root" || return 2

    local tasks=()
    if [ "$changed" = yes ]; then
        local planned
        planned=$(changed_tasks "$base") || return $?
        if [ "$planned" = ALL ]; then
            tasks=("${ALL_TASKS[@]}")
        elif [ -n "$planned" ]; then
            while IFS= read -r line; do tasks+=("$line"); done <<< "$planned"
        fi
    else
        tasks=("${ALL_TASKS[@]}")
    fi

    if [ "${#tasks[@]}" -eq 0 ]; then
        echo "format.sh: no formattable file changed against $base"
        return 0
    fi

    local before after
    [ "$check" = no ] || before=$(snapshot)
    sbt "$(sbt_heap tool)" "${tasks[@]}" || {
        echo "format.sh: sbt failed" >&2
        return 2
    }
    [ "$check" = yes ] || return 0

    after=$(snapshot "$before")
    local touched
    touched=$(changed_paths "$before" "$after")
    if [ -n "$touched" ]; then
        echo "format.sh: formatting changed these files:" >&2
        printf '  %s\n' $touched >&2
        git --no-pager diff -- $touched >&2
        return 1
    fi
    echo "format.sh: formatted"
}

# -- self-test mode --
# Runs the script against a fake sbt in a throwaway repository: the fake records each invocation and, when
# a case asks, rewrites a file the way a formatter would. Each case asserts the recorded calls or the exit
# code and the files reported, never just that the script ran.
self_test() {
    local self pass=0 fail=0 dir
    self=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN
    mkdir -p "$dir/bin" "$dir/repo"
    cat > "$dir/bin/sbt" <<'STUB'
#!/usr/bin/env bash
case "$1" in -J-Xmx*) printf '%s\n' "$1" >> "$FAKE_SBT_CALLS.heap"; shift ;; esac
printf '%s\n' "$*" >> "$FAKE_SBT_CALLS"
if [ -n "${FAKE_SBT_REWRITES:-}" ]; then
    printf '%s\n' "${FAKE_SBT_CONTENT:-formatted}" > "$FAKE_SBT_REWRITES"
fi
exit "${FAKE_SBT_EXIT:-0}"
STUB
    chmod +x "$dir/bin/sbt"

    local repo=$dir/repo
    (
        cd "$repo" || exit 1
        git init -q -b main .
        git config user.email self-test@example.com
        git config user.name self-test
        mkdir -p mod/src project
        printf 'object A\n' > mod/src/A.scala
        printf 'lazy val a = 1\n' > build.sbt
        printf '# Readme\n' > README.md
        printf 'version = "3.11.5"\n' > .scalafmt.conf
        git add -A
        git commit -q -m base
        git update-ref refs/remotes/origin/main HEAD
    ) || {
        echo "self-test setup failed" >&2
        return 1
    }

    record() {
        if [ "$1" = ok ]; then
            echo "  PASS: $2"
            pass=$((pass + 1))
        else
            echo "  FAIL: $2"
            fail=$((fail + 1))
        fi
    }
    calls() { cat "$dir/calls" 2>/dev/null; }
    run() {
        rm -f "$dir/calls" "$dir/calls.heap" "$dir/out"
        (cd "$repo" && PATH="$dir/bin:$PATH" FAKE_SBT_CALLS="$dir/calls" "$self" "$@" > "$dir/out" 2>&1)
    }
    reset_repo() {
        (cd "$repo" && git reset -q --hard && git clean -qfd && git checkout -q main && git reset -q --hard origin/main)
    }

    echo "Running format.sh self-tests..."

    # Snapshot lines are ordered by path, so their hashes run in no particular order; this pair puts the
    # changed file's old hash after the unchanged file's.
    if [ "$(changed_paths $'ffff kyo/a.scala\n0000 scripts/b.sh' $'1111 kyo/a.scala\n0000 scripts/b.sh')" = "kyo/a.scala" ]; then
        record ok "the comparison names only the changed path, whatever order the hashes fall in"
    else record no "the comparison names only the changed path, whatever order the hashes fall in"; fi

    run
    if [ $? -eq 0 ] && [ "$(calls)" = "${ALL_TASKS[*]}" ]; then
        record ok "the whole tree in one sbt session"
    else record no "the whole tree in one sbt session"; fi
    if [ "$(cat "$dir/calls.heap" 2>/dev/null)" = "$(sbt_heap tool)" ]; then
        record ok "the session runs with the tool role's heap"
    else record no "the session runs with the tool role's heap: $(cat "$dir/calls.heap" 2>/dev/null)"; fi

    reset_repo
    (cd "$repo" && printf 'object A { val x = 1 }\n' > mod/src/A.scala && git commit -qam change)
    run --changed
    if [ $? -eq 0 ] && [ "$(calls)" = "kyo-dataJVM/scalafmtOnly $(cd "$repo" && pwd -P)/mod/src/A.scala" ]; then
        record ok "--changed formats a committed .scala change alone"
    else record no "--changed formats a committed .scala change alone: $(calls)"; fi

    reset_repo
    (cd "$repo" && printf 'lazy val a = 2\n' > build.sbt && printf '# Readme 2\n' > README.md)
    run --changed
    if [ $? -eq 0 ] && [ "$(calls)" = "scalafmtSbt doctestFormat" ]; then
        record ok "--changed routes uncommitted build and Markdown changes"
    else record no "--changed routes uncommitted build and Markdown changes: $(calls)"; fi

    reset_repo
    (cd "$repo" && printf 'object B\n' > mod/src/B.scala)
    run --changed
    if [ $? -eq 0 ] && [ "$(calls)" = "kyo-dataJVM/scalafmtOnly $(cd "$repo" && pwd -P)/mod/src/B.scala" ]; then
        record ok "--changed includes an untracked .scala file"
    else record no "--changed includes an untracked .scala file: $(calls)"; fi

    reset_repo
    (cd "$repo" && printf 'version = "3.11.6"\n' > .scalafmt.conf)
    run --changed
    if [ $? -eq 0 ] && [ "$(calls)" = "${ALL_TASKS[*]}" ]; then
        record ok "a .scalafmt.conf change formats the whole tree"
    else record no "a .scalafmt.conf change formats the whole tree: $(calls)"; fi

    reset_repo
    run --changed
    if [ $? -eq 0 ] && [ -z "$(calls)" ]; then
        record ok "--changed with nothing changed starts no sbt"
    else record no "--changed with nothing changed starts no sbt"; fi

    reset_repo
    FAKE_SBT_REWRITES="$repo/mod/src/A.scala" run --check
    if [ $? -eq 1 ] && grep -q "mod/src/A.scala" "$dir/out"; then
        record ok "--check fails and names a file the run changed"
    else record no "--check fails and names a file the run changed"; fi

    reset_repo
    (cd "$repo" && printf 'dirty but formatted\n' > README.md)
    run --check
    if [ $? -eq 0 ] && ! grep -q "README.md" "$dir/out"; then
        record ok "--check ignores a dirty file the run left alone"
    else record no "--check ignores a dirty file the run left alone"; fi

    reset_repo
    (cd "$repo" && printf 'z\n' > README.md && printf 'y\n' > build.sbt && printf 'x\n' > mod/src/C.scala && printf 'w\n' > mod/src/D.scala)
    FAKE_SBT_REWRITES="$repo/mod/src/C.scala" run --check
    if [ $? -eq 1 ] && grep -q "mod/src/C.scala" "$dir/out" && ! grep -qE "README.md|build.sbt|D.scala" "$dir/out"; then
        record ok "--check names only the file the run changed among several dirty ones"
    else record no "--check names only the file the run changed among several dirty ones"; fi

    reset_repo
    (cd "$repo" && printf 'dirty\n' > README.md)
    FAKE_SBT_REWRITES="$repo/README.md" run --check
    if [ $? -eq 1 ] && grep -q "README.md" "$dir/out"; then
        record ok "--check catches a change to an already dirty file"
    else record no "--check catches a change to an already dirty file"; fi

    reset_repo
    (cd "$repo" && printf 'object   A\n' > mod/src/A.scala)
    FAKE_SBT_REWRITES="$repo/mod/src/A.scala" FAKE_SBT_CONTENT='object A' run --check
    if [ $? -eq 1 ] && grep -q "mod/src/A.scala" "$dir/out"; then
        record ok "--check catches a dirty file formatted back to its committed content"
    else record no "--check catches a dirty file formatted back to its committed content"; fi

    reset_repo
    FAKE_SBT_EXIT=1 run --check
    if [ $? -eq 2 ]; then
        record ok "an sbt failure exits 2, distinct from a formatting failure"
    else record no "an sbt failure exits 2, distinct from a formatting failure"; fi

    reset_repo
    run --changed refs/remotes/origin/nowhere
    if [ $? -eq 2 ] && grep -q "no merge base" "$dir/out"; then
        record ok "an unknown base fails with a message"
    else record no "an unknown base fails with a message"; fi

    echo "format.sh self-tests: $pass passed, $fail failed"
    [ "$fail" -eq 0 ]
}

check=no
changed=no
base=origin/main
case "${1:-}" in
    --self-test)
        self_test
        exit $?
        ;;
esac
while [ $# -gt 0 ]; do
    case "$1" in
        --check)
            check=yes
            shift
            ;;
        --changed)
            changed=yes
            shift
            if [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; then
                base=$1
                shift
            fi
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done
run_format "$check" "$changed" "$base"
