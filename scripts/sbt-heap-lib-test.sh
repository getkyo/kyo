#!/usr/bin/env bash
set -uo pipefail

# Exercise scripts/sbt-heap-lib.sh and scripts/sbt.sh at chosen runner sizes, with sbt a stub on PATH
# that records its arguments.
script_dir=$(cd "$(dirname "$0")" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
mkdir "$test_dir/bin"
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" > "%s/sbt-args"\nprintf "%%s" "${SBT_OPTS-unset}" > "%s/sbt-opts"\n' \
    "$test_dir" "$test_dir" > "$test_dir/bin/sbt"
chmod +x "$test_dir/bin/sbt"

. "$script_dir/sbt-heap-lib.sh"

pass=0; fail=0
check() {  # check <name> <actual> <expected>
    if [ "$2" = "$3" ]; then echo "  PASS: $1"; pass=$((pass+1))
    else echo "  FAIL: $1: got '$2', expected '$3'"; fail=$((fail+1)); fi
}

echo "Running sbt-heap-lib self-tests..."

# derive <memory MB> <SBT_TASK_LIMIT, 0 for unset> <JvmMemory.java args...>
derive() {
    local mem="$1" limit="$2"; shift 2
    if [ "$limit" = 0 ]; then (unset SBT_TASK_LIMIT; SBT_HEAP_MEMORY_MB="$mem" sbt_heap_derive "$@")
    else SBT_TASK_LIMIT="$limit" SBT_HEAP_MEMORY_MB="$mem" sbt_heap_derive "$@"; fi
}

for mem in 8192 16384 65536; do
    for limit in 1 0; do
        for pair in test-jvm:test docs:docs run:node; do
            role=${pair%%:*}; kind=${pair#*:}
            total=$(( $(derive "$mem" "$limit" driver "$role") + $(derive "$mem" "$limit" forks "$kind") * $(derive "$mem" "$limit" fork-heap "$kind") ))
            check "$role and its forks fit the budget at ${mem}MB, task limit $limit" \
                "$(( total <= mem - mem / 4 ))" "1"
        done
    done
done

for role in compile classnames link publish tool; do
    check "$role, with no JVM beside it, gets the whole budget of a 16GB runner" "$(derive 16384 1 driver "$role")" "12288"
done

check "a 16GB runner under CI's task limit runs one test fork" "$(derive 16384 1 forks test)" "1"
check "a 16GB runner with no task limit still runs one test fork, since two would starve the driver" "$(derive 16384 0 forks test)" "1"
check "a 64GB runner runs the build's cap of two test forks" "$(derive 65536 0 forks test)" "2"

# Measured needs, each at the runner size it was measured on.
# ubuntu-latest reports 15989MB of its 16GB.
check "the test-jvm driver on a 16GB CI runner holds the 4716MB live heap measured over the CI JVM row" \
    "$(( $(derive 15989 1 driver test-jvm) >= 4716 ))" "1"
check "a test fork on a 16GB CI runner holds kyo-tasty's suites, which fail at 4096MB and fill 5120MB" \
    "$(( $(derive 15989 1 fork-heap test) >= 5120 ))" "1"
# ubuntu-24.04-arm, which runs the checks workflow with no task limit, reports 15947MB.
check "the docs driver on the checks runner holds the 8708MB live heap measured over doctest" \
    "$(( $(derive 15947 0 driver docs) >= 8708 ))" "1"
check "a doctest fork on the checks runner holds the 1744MB it was measured at" \
    "$(( $(derive 15947 0 fork-heap docs) >= 1744 ))" "1"
check "classnames on the checks runner holds the 9188MB live heap measured over checkClassNames" \
    "$(( $(derive 15947 0 driver classnames) >= 9188 ))" "1"
check "compile on a 16GB CI runner holds the 6943MB live heap measured over the JVM compile phases" \
    "$(( $(derive 15989 1 driver compile) >= 6943 ))" "1"
check "link on a 16GB CI runner holds the 7054MB live heap measured over the Native link phase" \
    "$(( $(derive 15989 1 driver link) >= 7054 ))" "1"
check "run on a 16GB CI runner holds the 5932MB live heap measured linking kyo-ui's JS tests" \
    "$(( $(derive 15989 1 driver run) >= 5932 ))" "1"
check "a Node test process on a 16GB CI runner holds the 2209MB a JS test task's Node processes reached" \
    "$(( $(derive 15989 1 fork-heap node) >= 2209 ))" "1"
check "link on the 7GB macos-14 runner holds the 5120MB its link measured" \
    "$(( $(derive 7168 1 driver link) >= 5120 ))" "1"
check "the JVM test phase stays under windows-arm64's 19318MB commit limit with its 3717MB idle commit and two JVMs' 1600MB off-heap" \
    "$(( $(derive 16384 1 driver test-jvm) + $(derive 16384 1 fork-heap test) + 3717 + 2 * 1600 <= 19318 ))" "1"
check "the JS test phase stays under windows-arm64's 19318MB commit limit with its 3717MB idle commit and the driver's 1600MB off-heap" \
    "$(( $(derive 16384 1 driver run) + $(derive 16384 1 fork-heap node) + 3717 + 1600 <= 19318 ))" "1"

SBT_HEAP_MEMORY_MB=16384 sbt_heap nope >/dev/null 2>&1
check "an unknown role fails with 2" "$?" "2"

detected=$(unset SBT_HEAP_MEMORY_MB; sbt_heap_memory_mb)
case "$detected" in ''|*[!0-9]*) check "this machine's memory is detected as a number of MB" "$detected" "<MB>" ;;
    *) check "this machine's memory is detected as a number of MB" ok ok ;; esac

rm -f "$test_dir/sbt-args"
PATH="$test_dir/bin:$PATH" SBT_HEAP_MEMORY_MB=16384 "$script_dir/sbt.sh" publish -Dplatform=JVM +publishLocal 2>/dev/null
check "sbt.sh starts sbt with the role's heap first" "$(cat "$test_dir/sbt-args" 2>/dev/null)" \
    "$(SBT_HEAP_MEMORY_MB=16384 sbt_heap publish) -Dplatform=JVM +publishLocal"

PATH="$test_dir/bin:$PATH" SBT_OPTS="-Dx=1 -XX:+UseSerialGC" "$script_dir/sbt.sh" compile about 2>/dev/null
check "sbt.sh starts sbt with no inherited SBT_OPTS, whose options would follow the role's heap" \
    "$(cat "$test_dir/sbt-opts" 2>/dev/null)" "unset"

rm -f "$test_dir/sbt-args"
PATH="$test_dir/bin:$PATH" "$script_dir/sbt.sh" bogus compile >/dev/null 2>&1
rc=$?
check "sbt.sh with an unknown role exits 2" "$rc" "2"
check "sbt.sh with an unknown role starts no sbt" "$([ -f "$test_dir/sbt-args" ] && echo started || echo none)" "none"

echo "Results: $pass/$((pass+fail)) passed, $fail failed"
[ "$fail" = 0 ]
