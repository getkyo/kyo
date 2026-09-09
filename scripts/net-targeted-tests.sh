#!/usr/bin/env bash
# Targeted cross-platform run of the kyo-net suites touched by the detach-window and staging fixes: the specific test classes only, never
# a module-wide or diff-cascaded build. Intended for the ci-dispatch.yml custom mode (a bare JVM-provisioned runner) and equally runnable
# locally; provisions the Native/JS toolchains when apt is available and they are missing.
set -euo pipefail
cd "$(dirname "$0")/.."

if command -v apt-get >/dev/null 2>&1; then
    missing=""
    command -v clang >/dev/null 2>&1 || missing="clang build-essential"
    command -v node >/dev/null 2>&1 || missing="$missing nodejs npm"
    if [ -n "$missing" ]; then
        sudo apt-get update -qq
        # shellcheck disable=SC2086
        sudo apt-get install -y -qq $missing libssl-dev liburing-dev >/dev/null
    fi
fi

POSIX_SUITES='kyo.net.internal.posix.PollerIoDriverUpgradeDetachTest kyo.net.internal.posix.PosixTransportUpgradeRejectTest kyo.net.internal.posix.PosixTransportUpgradeDoubleFeedTest kyo.net.internal.posix.IoUringDriverUpgradeDetachTest kyo.net.internal.posix.IoUringMutualTlsStressTest'
SHARED_SUITES='kyo.net.TransportStartTlsTest kyo.net.TransportStartTlsConcurrentTest kyo.net.TransportStartTlsCrossTailTest'
NIO_SUITES='kyo.net.internal.NioIoDriverTest kyo.net.internal.NioTransportTest'

exec sbt \
    "kyo-netJVM/testOnly $NIO_SUITES $POSIX_SUITES $SHARED_SUITES" \
    "kyo-netNative/testOnly $POSIX_SUITES $SHARED_SUITES" \
    "kyo-netJS/testOnly $SHARED_SUITES kyo.net.TransportInMemoryUpgradeTest"
