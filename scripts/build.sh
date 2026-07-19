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
    local apt_pkgs="curl ca-certificates patch"
    local node_pkgs="" native_pkgs=""
    # "all" provisions the union (raw sbt mode may run any platform's command in the container).
    case "$platform" in
        JS|Wasm|all) node_pkgs="nodejs npm" ;;
    esac
    case "$platform" in
        # clang, cc (build-essential), and libssl-dev are preinstalled on GitHub runners,
        # so the CI setup action never lists them; a bare container needs them explicitly
        # (scala-native drives clang, kyo-ffi-it's bundled lib builds with cc, and the
        # openssl-linked modules need -lssl -lcrypto).
        Native|all) native_pkgs="clang build-essential libssl-dev libcurl4-openssl-dev libidn2-dev libh2o-evloop-dev=2.2.5+dfsg2-8.1ubuntu3" ;;
    esac
    cat <<PROVISION
export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq >/dev/null
    apt-get install -y -qq -o Acquire::Retries=3 $apt_pkgs $node_pkgs $native_pkgs >/dev/null
fi
export COURSIER_CACHE=/root/.cache/coursier
if ! command -v cs >/dev/null 2>&1; then
    # Linux aarch64 launchers are published by VirtusLab's coursier-m1 releases, not
    # by coursier/coursier (whose latest release has no aarch64-pc-linux asset).
    arch=\$(uname -m)
    if [ "\$arch" = aarch64 ]; then
        cs_url="https://github.com/VirtusLab/coursier-m1/releases/latest/download/cs-aarch64-pc-linux.gz"
    else
        cs_url="https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz"
    fi
    curl -fsSL "\$cs_url" | gzip -d > /usr/local/bin/cs && chmod +x /usr/local/bin/cs
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

    local args=(run --rm --security-opt label=disable -v "$snap:/build-input:ro")
    [ -n "$platform_flag" ] && args+=(--platform "$platform_flag")
    local envs=()
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
    && $inner"
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
