#!/usr/bin/env bash
# The sbt driver heap for every sbt process the build starts, locally and in CI. This file is the only
# place a driver -Xmx is chosen: .jvmopts carries no heap, workflows set none, and scripts/sbt-heap-check.sh
# fails CI on an -Xmx anywhere else. Sourced by scripts/sbt.sh, scripts/ci-test.sh and scripts/build.sh.
#
#   . "$repo/scripts/sbt-heap-lib.sh"
#   sbt "$(sbt_heap compile)" 'testKyo --phase compile-main JVM'
#
# The heap is passed as a -J command-line flag, which the bash launcher (bin/sbt, what every CI shell
# resolves, Git Bash on Windows included) places after JAVA_OPTS and .jvmopts. sbt.bat has no -J flag and
# hands one to sbt as a command, which fails, so these scripts run under bash. The launcher places SBT_OPTS
# after the -J flags, so sourcing this file clears SBT_OPTS: an inherited SBT_OPTS heap (a developer's
# "-Xms32G -Xmx32G") would otherwise override the role's in every driver.
#
# Each role's value is what that driver needs on a 16GB runner. A smaller runner clamps it to its
# memory minus SBT_HEAP_RESERVE_MB, the room a capped driver's off-heap (metaspace, code cache, thread
# stacks: 1.0 to 1.6GB measured) and the OS and runner agent need beside it.
#
# Roles:
#   compile   compile-main and compile-test drivers, doctest, scaladoc, the doc site build
#   test-jvm  the JVM run phase; its tests run in forked JVMs (build.sbt Test / javaOptions)
#   run       the JS, Wasm and Native run phases; tests run in Node or the linked binary
#   link      Native link batches and the heavy-module pre-link; clang jobs fork beside it
#   publish   publishLocal and ci-release over many modules, one ++ reapply per module
#   tool      planning, ffiCompileAll, packaging checks, release staging, formatting

SBT_HEAP_RESERVE_MB=4096

if [ -n "${SBT_OPTS:-}" ]; then
    echo "sbt-heap: clearing inherited SBT_OPTS so the role's heap applies (was: $SBT_OPTS)" >&2
    unset SBT_OPTS
fi

sbt_heap_role_mb() {
    case "$1" in
        compile)  echo 8192 ;;
        test-jvm) echo 12288 ;;
        run)      echo 6144 ;;
        link)     echo 8192 ;;
        publish)  echo 6144 ;;
        tool)     echo 3072 ;;
        *)        return 1 ;;
    esac
}

# The memory this runner gives its processes, in MB: the cgroup limit when one is set (a container or a
# limited CI job), else the machine's physical memory. Empty when neither can be read.
# SBT_HEAP_MEMORY_MB replaces the detection, so the self-tests run the same code at a chosen size.
sbt_heap_memory_mb() {
    if [ -n "${SBT_HEAP_MEMORY_MB:-}" ]; then echo "$SBT_HEAP_MEMORY_MB"; return; fi
    local total="" limit=""
    if [ -r /proc/meminfo ]; then
        total=$(awk '/^MemTotal:/ { print int($2 / 1024) }' /proc/meminfo)
    elif command -v sysctl >/dev/null 2>&1; then
        total=$(sysctl -n hw.memsize 2>/dev/null | awk '{ print int($1 / 1048576) }')
    fi
    if [ -r /sys/fs/cgroup/memory.max ]; then
        limit=$(cat /sys/fs/cgroup/memory.max)
    elif [ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
        limit=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes)
    fi
    # cgroup v2 writes "max" for no limit; v1 writes a value near 2^63.
    case "$limit" in
        ''|max) limit="" ;;
        *) limit=$(awk -v b="$limit" 'BEGIN { m = int(b / 1048576); if (m < 1073741824) print m }') ;;
    esac
    if [ -n "$limit" ] && { [ -z "$total" ] || [ "$limit" -lt "$total" ]; }; then total="$limit"; fi
    echo "$total"
}

# The heap in MB for role $1 on this runner.
sbt_heap_mb() {
    local want mem cap
    want=$(sbt_heap_role_mb "$1") || { echo "sbt-heap: unknown role '$1'" >&2; return 2; }
    mem=$(sbt_heap_memory_mb)
    if [ -n "$mem" ]; then
        cap=$((mem - SBT_HEAP_RESERVE_MB))
        [ "$cap" -lt 1024 ] && cap=1024
        [ "$want" -gt "$cap" ] && want="$cap"
    fi
    echo "$want"
}

# The sbt launcher flag for role $1, e.g. -J-Xmx6144M.
sbt_heap() {
    local mb
    mb=$(sbt_heap_mb "$1") || return $?
    printf -- '-J-Xmx%sM' "$mb"
}
