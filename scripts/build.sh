#!/usr/bin/env bash
set -uo pipefail
#
# First-class local entry point for the shared runner.
#
# Usage:
#   build.sh [--env direct|podman|podman-ci] [--arch native|x86|arm] <action> <platform...>
#
# --env  direct     host sbt (relies on the repo .jvmopts for the driver heap)
#        podman      a Linux container running ci-test.sh over a clean snapshot
#        podman-ci   the podman container plus CI memory/CPU caps + CI=true +
#                    SBT_TASK_LIMIT=1 + the -Xmx12G driver, reproducing CI
# --arch native|x86|arm  container architecture (podman/podman-ci only); sets
#        podman --platform. native = host arch, x86 = linux/amd64, arm =
#        linux/arm64; qemu-emulated when it differs from the host arch.
# <action>    one of test, testDiff, compile, link (default: test), or
#             `sbt <raw command>` to run one arbitrary sbt command in the env
#             (e.g. build.sh --env direct sbt 'kyo-netJVM/test'); no platform arg
# <platform>  one or more of JVM, JS, Native, Wasm, all (default: all)
#
# Every env delegates the WHAT to the same ci-test.sh, so a local run and a CI
# run execute identical runner code.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Make the JVM heap deterministic. The runner defines the driver heap via the repo .jvmopts (direct)
# or CI_DRIVER_OPTS (podman-ci). An ambient SBT_OPTS (e.g. a developer's "-Xms32G -Xmx32G" eager
# reservation) otherwise overrides .jvmopts on every sbt launch and, stacked across the driver, forked
# test JVMs, and phase processes a run spawns, oversubscribes the machine into OOM-kills and boot hangs.
# Clear it so the runner's own heap always wins; the podman envs never inherit it.
if [ -n "${SBT_OPTS:-}" ]; then
    echo "build.sh: clearing inherited SBT_OPTS so the runner controls the JVM heap (was: $SBT_OPTS)" >&2
    unset SBT_OPTS
fi

# CI-faithful resource caps for --env podman-ci. GitHub standard public-repo
# runners are 4 vCPU / 16 GB on both linux-x64 and linux-arm64. One place.
CI_MEMORY="16g"
CI_CPUS="4"
CI_DRIVER_OPTS="-Xmx12G -Xss10M -XX:+UseG1GC -XX:+UseCompactObjectHeaders -XX:MaxMetaspaceSize=2G -XX:ReservedCodeCacheSize=256M -Dfile.encoding=UTF-8"
CONTAINER_IMAGE="${KYO_BUILD_IMAGE:-ubuntu:noble}"

ENV_KIND="direct"
ARCH="native"
ACTIONS="test testDiff compile link"
PLATFORMS="JVM JS Native Wasm"

usage() {
    echo "Usage: build.sh [--env direct|podman|podman-ci] [--arch native|x86|arm] <action> <platform...>" >&2
}

contains_word() {
    local word="$1" list="$2" item
    for item in $list; do [ "$item" = "$word" ] && return 0; done
    return 1
}

die_usage() { echo "build.sh: $1" >&2; usage; exit 2; }

# -- parse flags, then positional action + platforms --
while [ $# -gt 0 ]; do
    case "$1" in
        --env)  ENV_KIND="${2:-}"; shift 2 ;;
        --arch) ARCH="${2:-}"; shift 2 ;;
        --) shift; break ;;
        -*) die_usage "unknown flag '$1'" ;;
        *) break ;;
    esac
done

case "$ENV_KIND" in direct|podman|podman-ci) ;; *) die_usage "unknown env '$ENV_KIND'" ;; esac
case "$ARCH" in native|x86|arm) ;; *) die_usage "unknown arch '$ARCH'" ;; esac

ACTION="${1:-test}"
shift || true

# The `sbt` action is a raw escape hatch: everything after it is one sbt command, run in the selected
# env with no platform/diff machinery, for a module- or test-scoped local run (e.g.
# `build.sh --env direct sbt 'kyo-netJVM/test'`). Every other action takes one or more platforms.
RAW_MODE=no
RAW_SBT=""
PLAT_LIST=""
if [ "$ACTION" = "sbt" ]; then
    [ $# -gt 0 ] || die_usage "sbt mode needs a command, e.g. build.sh --env direct sbt 'kyo-netJVM/test'"
    RAW_MODE=yes
    RAW_SBT="$*"
else
    contains_word "$ACTION" "$ACTIONS" || die_usage "unknown action '$ACTION'"
    if [ $# -eq 0 ]; then
        set -- all
    fi
    for p in "$@"; do
        if [ "$p" = "all" ]; then
            PLAT_LIST="JVM JS Native Wasm"
        elif contains_word "$p" "$PLATFORMS"; then
            PLAT_LIST="$PLAT_LIST $p"
        else
            die_usage "unknown platform '$p'"
        fi
    done
fi

# -- arch resolution: a non-native arch is podman-only --
host_arch() {
    [ -n "${_HOST_ARCH_OVERRIDE:-}" ] && { echo "$_HOST_ARCH_OVERRIDE"; return 0; }
    case "$(uname -m)" in
        x86_64|amd64) echo "x86" ;;
        arm64|aarch64) echo "arm" ;;
        *) echo "native" ;;
    esac
}
podman_platform() {
    case "$ARCH" in
        x86) echo "linux/amd64" ;;
        arm) echo "linux/arm64" ;;
        *)   echo "" ;;
    esac
}

if [ "$ARCH" != "native" ] && [ "$ENV_KIND" = "direct" ]; then
    die_usage "--arch $ARCH requires --env podman or podman-ci (direct has no container)"
fi

# -- pre-run echo (unconditional) --
if [ "$RAW_MODE" = yes ]; then
    echo "build.sh: env=$ENV_KIND arch=$ARCH sbt: $RAW_SBT"
else
    echo "build.sh: env=$ENV_KIND arch=$ARCH action=$ACTION platforms=$PLAT_LIST"
fi

# -- emulation notice + binfmt precheck for a cross-arch container --
if [ "$ARCH" != "native" ]; then
    hostarch=$(host_arch)
    if [ "$ARCH" != "$hostarch" ]; then
        echo "build.sh: emulated $ARCH run; expect substantial slowdown"
        if [ -z "${BUILD_SKIP_BINFMT:-}" ] && [ ! -d /proc/sys/fs/binfmt_misc ]; then
            echo "build.sh: binfmt_misc is not registered; install qemu-user-static and binfmt support, then retry" >&2
            exit 1
        fi
    fi
fi

run_one() {
    local platform="$1"
    case "$ENV_KIND" in
        direct)
            ( cd "$PROJECT_DIR" && ci_cmd "$platform" )
            ;;
        podman|podman-ci)
            run_in_container "$platform"
            ;;
    esac
}

# The runner command, identical in every env.
ci_cmd() { "$SCRIPT_DIR/ci-test.sh" "$1" "$ACTION"; }

# Install the JDK + sbt via coursier inside a bare container image, making it
# a usable build host (GitHub setup actions cannot run in a bare container).
# Also installs Node 24 for JS/Wasm, the Linux native libraries for Native,
# and patch (the snapshot applies uncommitted changes via patch). Emitted as
# a shell prelude run inside the already-launched container, quiet and idempotent.
container_provision() {
    local platform="$1"
    # liburing-dev + libssl-dev: the kyo-net JVM FFI shims link the io_uring (-luring) and OpenSSL TLS data planes; without them
    # kyo-netJVM's ffiCompile fails (cannot find -luring). Small and always installed so any kyo-net command builds in the container.
    local apt_pkgs="curl ca-certificates patch liburing-dev libssl-dev"
    local node_pkgs="" native_pkgs="" bssl_pkgs=""
    # "all" provisions the union (raw sbt mode may run any platform's command in the container).
    case "$platform" in
        JS|Wasm|all) node_pkgs="nodejs npm" ;;
    esac
    case "$platform" in
        Native|all) native_pkgs="libcurl4-openssl-dev libidn2-dev libh2o-evloop-dev=2.2.5+dfsg2-8.1ubuntu3" ;;
    esac
    # BoringSSL build toolchain (cmake + Go + a C toolchain), only when STAGE_BORINGSSL=1 builds the vendored BoringSSL so kyo-net's
    # TLS tests run against real libssl/libcrypto instead of cancelling. Heavy, so off by default.
    [ "${STAGE_BORINGSSL:-}" = 1 ] && bssl_pkgs="cmake golang-go build-essential git clang libunwind-dev"
    cat <<PROVISION
export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq >/dev/null
    apt-get install -y -qq -o Acquire::Retries=3 $apt_pkgs $node_pkgs $native_pkgs $bssl_pkgs >/dev/null
fi
export COURSIER_CACHE=/root/.cache/coursier
if ! command -v cs >/dev/null 2>&1; then
    arch=\$(uname -m)
    if [ "\$arch" = x86_64 ]; then
        # x86_64 Linux: coursier ships a native standalone launcher (no JVM needed to run it).
        curl -fsSL "https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz" \
            | gzip -d > /usr/local/bin/cs && chmod +x /usr/local/bin/cs
    else
        # aarch64 Linux: coursier ships NO native launcher (only x86_64-pc-linux and aarch64-apple-darwin), so
        # bootstrap a JDK via apt and use coursier's arch-independent JVM launcher (a self-executable polyglot
        # script that runs on any JVM). This is the only path that works on the arm64-native container.
        apt-get install -y -qq -o Acquire::Retries=3 default-jdk-headless >/dev/null
        curl -fsSL "https://github.com/coursier/coursier/releases/latest/download/coursier" > /usr/local/bin/cs \
            && chmod +x /usr/local/bin/cs
    fi
fi
eval "\$(cs java --jvm corretto:25 --env)"
command -v sbt >/dev/null 2>&1 || cs install sbt >/dev/null
export PATH="/root/.local/share/coursier/bin:\$PATH"
PROVISION
}

# Container run: a clean git-archived snapshot mounted read-only, the
# runner executed inside it. podman-ci adds the CI caps + CI env.
run_in_container() {
    local platform="$1"
    local platform_flag; platform_flag=$(podman_platform)
    local snap; snap=$(mktemp -d)
    git -C "$PROJECT_DIR" archive HEAD --format=tar > "$snap/src.tar"
    git -C "$PROJECT_DIR" diff HEAD > "$snap/changes.patch" 2>/dev/null || true

    # --privileged + memlock: podman's default seccomp/limits block the io_uring syscalls and ring buffer locking that kyo-net's io_uring
    # backend needs; without these the io_uring tests fail to init the ring and cancel. (GitHub runners allow them without privilege; this is a
    # podman-sandbox concern only, so it does not change observed behavior.)
    local args=(run --rm --security-opt label=disable --privileged --ulimit memlock=-1:-1 -v "$snap:/build-input:ro")
    [ -n "$platform_flag" ] && args+=(--platform "$platform_flag")
    # Artifact extraction. The container is --rm, so its target/ (coverage reports, etc.) is discarded on exit. When KYO_BUILD_OUT names a host
    # directory, mount it at /output; the inner script below copies the scoverage report/data dirs there after the run, so a coverage run's
    # report survives the container. Unset by default, so a normal run is unaffected.
    if [ -n "${KYO_BUILD_OUT:-}" ]; then
        mkdir -p "$KYO_BUILD_OUT"
        args+=(-v "$KYO_BUILD_OUT:/output")
    fi
    local envs=()
    # Forward the leak-debug flag so the forked test JVM (which inherits the container env) runs leaves serially and attributes each leaked
    # descriptor to the test that opened it (see kyo.test.runner.internal.LeakDebug). Unset by default, so a normal run is unaffected.
    [ -n "${KYO_TEST_LEAK_DEBUG:-}" ] && envs+=(-e "KYO_TEST_LEAK_DEBUG=$KYO_TEST_LEAK_DEBUG")
    # Forward the BoringSSL-staging flag; when set the container builds the vendored BoringSSL before the command so kyo-net's TLS tests run
    # against real libssl/libcrypto instead of cancelling.
    [ -n "${STAGE_BORINGSSL:-}" ] && envs+=(-e "STAGE_BORINGSSL=$STAGE_BORINGSSL")
    # Forward the kyo-net per-backend test isolation flag (KYO_NET_ONLY=<backend>), the per-TLS-provider isolation flag
    # (KYO_NET_TLS_ONLY=<provider>), and the success-leaves-only flag (KYO_NET_SUCCESS_ONLY=1) so a podman run can
    # validate/sample a single (backend x provider) cell in isolation. Unset by default (all backends/providers), so a normal run is unaffected.
    [ -n "${KYO_NET_ONLY:-}" ] && envs+=(-e "KYO_NET_ONLY=$KYO_NET_ONLY")
    [ -n "${KYO_NET_TLS_ONLY:-}" ] && envs+=(-e "KYO_NET_TLS_ONLY=$KYO_NET_TLS_ONLY")
    [ -n "${KYO_NET_SUCCESS_ONLY:-}" ] && envs+=(-e "KYO_NET_SUCCESS_ONLY=$KYO_NET_SUCCESS_ONLY")
    if [ "$ENV_KIND" = "podman-ci" ]; then
        args+=(--memory "$CI_MEMORY" --cpus "$CI_CPUS")
        envs+=(-e CI=true -e SBT_TASK_LIMIT=1
               -e "JAVA_OPTS=$CI_DRIVER_OPTS"
               -e "JVM_OPTS=$CI_DRIVER_OPTS")
    fi
    # Raw mode runs the arbitrary sbt command (passed via the environment to avoid host-side quoting);
    # otherwise the inner command is the standard per-platform ci-test.sh runner.
    local inner
    if [ "$RAW_MODE" = yes ]; then
        envs+=(-e "RAW_SBT=$RAW_SBT")
        inner='sbt "$RAW_SBT"'
    else
        inner="./scripts/ci-test.sh '$platform' '$ACTION'"
    fi
    local provision; provision=$(container_provision "$platform")
    podman "${args[@]}" "${envs[@]}" "$CONTAINER_IMAGE" \
        bash -c "set -e
$provision
mkdir -p /work && cd /work && tar xf /build-input/src.tar \
    && if [ -s /build-input/changes.patch ]; then patch -p1 < /build-input/changes.patch; fi \
    && if [ \"\${STAGE_BORINGSSL:-}\" = 1 ]; then bash kyo-net/build/boringssl/build-boringssl.sh \"linux-\$(uname -m)\"; fi
if $inner; then __rc=0; else __rc=\$?; fi
if [ -d /output ]; then find . -type d \\( -name scoverage-report -o -name scoverage-data \\) -exec cp -r --parents {} /output/ \\; 2>/dev/null || true; fi
exit \${__rc:-1}"
    local rc=$?
    rm -rf "$snap"
    return $rc
}

# -- raw sbt escape hatch (no platform loop), or fail-fast across platforms --
if [ "$RAW_MODE" = yes ]; then
    case "$ENV_KIND" in
        direct)            ( cd "$PROJECT_DIR" && sbt "$RAW_SBT" ); exit $? ;;
        podman|podman-ci)  run_in_container all; exit $? ;;
    esac
fi
for platform in $PLAT_LIST; do
    run_one "$platform" || exit $?
done
