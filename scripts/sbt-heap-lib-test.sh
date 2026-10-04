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

for role in compile docs test-jvm run link publish tool; do
    want=$(sbt_heap_role_mb "$role")
    check "$role on a 32GB runner is its table value" "$(SBT_HEAP_MEMORY_MB=32768 sbt_heap "$role")" "-J-Xmx${want}M"
done

check "a role above runner memory less the reserve is clamped to it" \
    "$(SBT_HEAP_MEMORY_MB=7168 sbt_heap_mb link)" "$((7168 - SBT_HEAP_RESERVE_MB))"
check "a role within runner memory less the reserve is not clamped" \
    "$(SBT_HEAP_MEMORY_MB=16384 sbt_heap_mb compile)" "$(sbt_heap_role_mb compile)"
check "a runner smaller than the reserve still gets 1GB" "$(SBT_HEAP_MEMORY_MB=2048 sbt_heap_mb tool)" "1024"

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
