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
# Every heap is derived from the machine's memory by project/JvmMemory.java, which build.sbt uses for the
# forked JVMs beside each driver, so a driver and its forks split one budget.
#
# Roles:
#   compile     compile-main and compile-test drivers, the doc site build
#   docs        doctest and scaladoc, which compile every module's tests; doctest and scaladoc forks run beside it
#   classnames  checkClassNames, which compiles in one driver every JVM module's tests the doctest step
#               left uncompiled; at 8192 it ran out of heap compiling kyo-net's tests
#   test-jvm    the JVM run phase; forked test JVMs run beside it
#   run         the JS, Wasm and Native run phases; tests run in Node or the linked binary beside it
#   link        Native link batches and the heavy-module pre-link; clang jobs fork beside it
#   publish     publishLocal and ci-release over many modules, one ++ reapply per module
#   tool        planning, ffiCompileAll, packaging checks, release staging, formatting

# Resolved through a symlink, which build-selftest.sh uses to source this file from a stub directory.
sbt_heap_lib_path="${BASH_SOURCE[0]}"
if [ -L "$sbt_heap_lib_path" ]; then
    sbt_heap_lib_link=$(readlink "$sbt_heap_lib_path")
    case "$sbt_heap_lib_link" in /*) sbt_heap_lib_path="$sbt_heap_lib_link" ;; *) sbt_heap_lib_path="$(dirname "$sbt_heap_lib_path")/$sbt_heap_lib_link" ;; esac
fi
SBT_HEAP_JVM_MEMORY="$(cd "$(dirname "$sbt_heap_lib_path")/.." && pwd)/project/JvmMemory.java"
# Git Bash paths (/c/...) mean nothing to a Windows java.
command -v cygpath >/dev/null 2>&1 && SBT_HEAP_JVM_MEMORY=$(cygpath -m "$SBT_HEAP_JVM_MEMORY")

if [ -n "${SBT_OPTS:-}" ]; then
    echo "sbt-heap: clearing inherited SBT_OPTS so the role's heap applies (was: $SBT_OPTS)" >&2
    unset SBT_OPTS
fi

sbt_heap_derive() { java "$SBT_HEAP_JVM_MEMORY" "$@"; }

# The memory this runner gives its processes, in MB: the cgroup limit when one is set (a container or a
# limited CI job), else the machine's physical memory. SBT_HEAP_MEMORY_MB replaces the detection, so the
# self-tests run the same code at a chosen size.
sbt_heap_memory_mb() { sbt_heap_derive memory; }

# The heap in MB for role $1 on this runner.
sbt_heap_mb() {
    sbt_heap_derive driver "$1" 2>/dev/null || { echo "sbt-heap: unknown role '$1'" >&2; return 2; }
}

# The sbt launcher flag for role $1, e.g. -J-Xmx6144M.
sbt_heap() {
    local mb
    mb=$(sbt_heap_mb "$1") || return $?
    printf -- '-J-Xmx%sM' "$mb"
}
