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
#
# Env knobs the podman envs read:
#   KYO_BUILD_IMAGE   container image (default ubuntu:noble). Point it at a musl JDK image
#                     (e.g. eclipse-temurin:25-jdk-alpine) to reproduce the release's Alpine
#                     legs; provisioning switches to apk and takes sbt from the release tarball,
#                     because both staging scripts refuse a cross-OS build and the linux-musl-*
#                     natives can only be produced on a genuine musl host.
#   STAGE_BORINGSSL=1 build the vendored BoringSSL before the command (kyo-net TLS).
#   STAGE_AERON=1     build the pinned Aeron C library before the command (kyo-aeron).
#                     Both derive their os-arch from the container's own host, musl included.

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
CI_MEMORY="${CI_MEMORY:-16g}"
CI_CPUS="${CI_CPUS:-4}"
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
        # Ask the container runtime to execute one binary of the target platform. The capability has to be
        # checked where the containers run, which is not always where this script runs: with `podman machine`
        # the binfmt_misc handlers live in the VM's kernel, and a macOS host has no /proc at all, so reading
        # the host's own /proc/sys/fs/binfmt_misc answers "not registered" on every mac and refuses every
        # cross-arch run on the machines most likely to need one. Running the thing is the only probe that
        # cannot be wrong about which kernel it is asking. It costs an image pull the run is about to do
        # anyway, since it uses the same image.
        if [ -z "${BUILD_SKIP_BINFMT:-}" ] &&
            ! podman run --rm --platform "$(podman_platform)" "$CONTAINER_IMAGE" true >/dev/null 2>&1; then
            echo "build.sh: cannot execute $(podman_platform) binaries; register qemu-user-static binfmt handlers, then retry" >&2
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
    # Read host-side: the musl branch installs sbt before the source snapshot is extracted.
    local sbt_version; sbt_version=$(sed -n 's/^sbt.version=//p' "$PROJECT_DIR/project/build.properties")
    # liburing-dev + libssl-dev: the kyo-net JVM FFI shims link the io_uring (-luring) and OpenSSL TLS data planes; without them
    # kyo-netJVM's ffiCompile fails (cannot find -luring). Small and always installed so any kyo-net command builds in the container.
    local apt_pkgs="curl ca-certificates patch liburing-dev libssl-dev"
    local node_pkgs="" native_pkgs="" bssl_pkgs="" aeron_pkgs=""
    # Alpine equivalents, used when KYO_BUILD_IMAGE names a musl image. Alpine spells the OpenSSL and
    # libuuid development packages differently (openssl-dev, util-linux-dev) and has no separate
    # ca-certificates-for-curl split, so the lists are mapped rather than shared.
    # `file` is not optional here: native_assert_arch's linux branch soft-skips its architecture
    # assertion when ar or file is missing, so without it the local musl repro would silently check
    # less than release.yml's Alpine legs, which apk-add both.
    local apk_pkgs="bash curl ca-certificates patch liburing-dev openssl-dev tar file binutils"
    local apk_node_pkgs="" apk_native_pkgs="" apk_bssl_pkgs="" apk_aeron_pkgs=""
    # "all" provisions the union (raw sbt mode may run any platform's command in the container).
    case "$platform" in
        JS|Wasm|all) node_pkgs="nodejs npm"; apk_node_pkgs="nodejs npm" ;;
    esac
    case "$platform" in
        # clang, cc (build-essential), and libssl-dev are preinstalled on GitHub runners,
        # so the CI setup action never lists them; a bare container needs them explicitly
        # (scala-native drives clang, kyo-ffi-it's bundled lib builds with cc, and the
        # openssl-linked modules need -lssl -lcrypto).
        Native|all) native_pkgs="clang build-essential libssl-dev libcurl4-openssl-dev libidn2-dev libh2o-evloop-dev=2.2.5+dfsg2-8.1ubuntu3 libgc-dev"
                    # No libh2o on Alpine; the Native leg is not a musl target, and the musl legs the
                    # release actually runs are JVM-only native staging.
                    apk_native_pkgs="clang build-base openssl-dev curl-dev libidn2-dev gc-dev" ;;
    esac
    # Node 24, matching the workflow's setup-node pin. noble's apt `nodejs` is 18, and jsdom@30 declares
    # engines >= 22, so a DOM-backed suite installs and then fails to load it, reporting "jsdom is not
    # resolvable" no matter what the code under test does. Beyond jsdom, running JS/Wasm on a different V8
    # major than CI makes any local result unfaithful. The apt packages stay as the source of npm and a
    # fallback; /usr/local/bin precedes /usr/bin, so the tarball wins when present.
    local node_setup=""
    if [ -n "$node_pkgs" ]; then
        node_setup='
node_ok=0
if command -v node >/dev/null 2>&1; then
    node_major=$(node --version | tr -d "v" | cut -d. -f1)
    if [ "${node_major:-0}" -ge 24 ] 2>/dev/null; then node_ok=1; fi
fi
if [ "$node_ok" != 1 ]; then
    case $(uname -m) in aarch64) node_arch=linux-arm64 ;; *) node_arch=linux-x64 ;; esac
    curl -fsSL "https://nodejs.org/dist/v24.16.0/node-v24.16.0-${node_arch}.tar.gz" \
        | tar xz -C /usr/local --strip-components=1
fi'
    fi
    # BoringSSL build toolchain (cmake + Go + a C toolchain), only when STAGE_BORINGSSL=1 builds the vendored BoringSSL so kyo-net's
    # TLS tests run against real libssl/libcrypto instead of cancelling. Heavy, so off by default.
    [ "${STAGE_BORINGSSL:-}" = 1 ] && bssl_pkgs="cmake golang-go build-essential git clang libunwind-dev"
    [ "${STAGE_BORINGSSL:-}" = 1 ] && apk_bssl_pkgs="cmake go build-base git clang libunwind-dev linux-headers perl"
    # Aeron build toolchain (a C toolchain + git for the pinned clone), only when STAGE_AERON=1 stages the static Aeron C library so
    # kyo-aeron's shim has an archive to link. uuid-dev supplies the libuuid.so link target the driver needs and that no base image
    # preinstalls. The staged tree is gitignored, so any container command touching kyo-aeron needs this. Heavy, so off by default.
    local aeron_setup=""
    if [ "${STAGE_AERON:-}" = 1 ]; then
        aeron_pkgs="build-essential git uuid-dev"
        # util-linux-dev is Alpine's libuuid: the Aeron driver links -luuid on Linux, musl included.
        apk_aeron_pkgs="build-base git util-linux-dev linux-headers cmake"
        # Aeron 1.50.2's CMakeLists sets cmake_minimum_required(3.30) and noble's apt cmake is 3.28, so apt cannot satisfy it. GitHub
        # runners only avoid this because they preinstall a newer cmake; the setup action's apt fallback would hit the same wall.
        # Install the upstream binary unless the image already carries >= 3.30.
        aeron_setup='
cmake_ok=0
if command -v cmake >/dev/null 2>&1; then
    cmake_ver=$(cmake --version | head -1 | tr -cd "0-9.\n" )
    cmake_major=${cmake_ver%%.*}
    cmake_rest=${cmake_ver#*.}
    cmake_minor=${cmake_rest%%.*}
    if [ "${cmake_major:-0}" -gt 3 ] 2>/dev/null || { [ "${cmake_major:-0}" -eq 3 ] 2>/dev/null && [ "${cmake_minor:-0}" -ge 30 ] 2>/dev/null; }; then
        cmake_ok=1
    fi
fi
if [ "$cmake_ok" != 1 ]; then
    if command -v apk >/dev/null 2>&1; then
        # Kitware ships glibc binaries only, so there is no upstream tarball to fall back to on musl.
        # Alpine'"'"'s own cmake is the only source; say so rather than installing something unrunnable.
        echo "cmake >= 3.30 required for Aeron 1.50.2 and this musl image has $(cmake --version 2>/dev/null | head -1)." >&2
        echo "Use an Alpine release whose apk cmake is >= 3.30." >&2
        exit 1
    fi
    case $(uname -m) in aarch64) cmake_arch=linux-aarch64 ;; *) cmake_arch=linux-x86_64 ;; esac
    curl -fsSL "https://github.com/Kitware/CMake/releases/download/v3.31.6/cmake-3.31.6-${cmake_arch}.tar.gz" \
        | tar xz -C /usr/local --strip-components=1
fi'
    fi
    cat <<PROVISION
export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq >/dev/null
    apt-get install -y -qq -o Acquire::Retries=3 $apt_pkgs $node_pkgs $native_pkgs $bssl_pkgs $aeron_pkgs >/dev/null
elif command -v apk >/dev/null 2>&1; then
    # musl path, reached via KYO_BUILD_IMAGE=<a musl jdk image>. Both staging scripts refuse a
    # cross-OS build, so the release builds its linux-musl-* natives on a genuine musl host; this is
    # how that leg is reproduced locally.
    apk add --no-cache $apk_pkgs $apk_node_pkgs $apk_native_pkgs $apk_bssl_pkgs $apk_aeron_pkgs >/dev/null
fi
$node_setup
export COURSIER_CACHE=/root/.cache/coursier
if command -v apk >/dev/null 2>&1; then
    # The coursier launchers below are glibc binaries, so a musl image brings its own JDK and takes
    # sbt from the release tarball (a JAR the musl JDK runs), exactly as release.yml's Alpine legs do.
    command -v java >/dev/null 2>&1 || { echo "musl image must carry a JDK (use a *-jdk-alpine image)" >&2; exit 1; }
    if ! command -v sbt >/dev/null 2>&1; then
        # The version comes from the host: provisioning runs before the source snapshot is
        # extracted, so project/build.properties is not readable here yet.
        curl -fsSL "https://github.com/sbt/sbt/releases/download/v$sbt_version/sbt-$sbt_version.tgz" | tar -xz -C /opt
    fi
    export PATH="/opt/sbt/bin:\$PATH"
elif ! command -v cs >/dev/null 2>&1; then
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
if command -v cs >/dev/null 2>&1; then
    eval "\$(cs java --jvm corretto:25 --env)"
    command -v sbt >/dev/null 2>&1 || cs install sbt >/dev/null
    export PATH="/root/.local/share/coursier/bin:\$PATH"
fi
$aeron_setup
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
    # Docker-out-of-docker for the container-backed suites (kyo-sql / kyo-pod): when KYO_POD_SOCKET names the host podman socket, mount it and
    # share the host network so kyo-pod inside the container can start sibling DB containers and reach their published ports on localhost. Opt-in,
    # so a normal run is unaffected.
    if [ -n "${KYO_POD_SOCKET:-}" ]; then
        args+=(--network host -v "${KYO_POD_SOCKET}:${KYO_POD_SOCKET}")
        envs+=(-e "CONTAINER_HOST=unix://${KYO_POD_SOCKET}")
        # /tmp at the SAME path on both sides, because a sibling container's bind mounts are resolved by the daemon, not by us. A suite that
        # generates a file and hands its path to a sibling (kyo-sql's TLS suites write server certs to a temp dir and bind it into Postgres at
        # /etc/ssl-pg) otherwise names a path that exists only inside this container: the sibling mounts an empty directory, Postgres starts
        # without TLS, and every TLS leaf fails with the server answering 'N' to SSLRequest rather than anything resembling the real defect.
        # Sharing the daemon's own /tmp makes the two views agree, so the generated path resolves identically on both.
        args+=(-v /tmp:/tmp)
    fi
    # Forward the BoringSSL-staging flag; when set the container builds the vendored BoringSSL before the command so kyo-net's TLS tests run
    # against real libssl/libcrypto instead of cancelling.
    [ -n "${STAGE_BORINGSSL:-}" ] && envs+=(-e "STAGE_BORINGSSL=$STAGE_BORINGSSL")
    # Forward the libaeron-staging flag; when set the container builds the pinned Aeron C library before the command so kyo-aeron's
    # ffiCompile finds the staged archive instead of failing to link. Both staging scripts derive the os-arch from the container's
    # own host (musl included), so neither is passed one here: a hand-computed "linux-$(uname -m)" is wrong on an Alpine image.
    [ -n "${STAGE_AERON:-}" ] && envs+=(-e "STAGE_AERON=$STAGE_AERON")
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
        # Mirror build.yml's Native env so a podman-ci Native run reproduces the row's link staging:
        # the link CPU cap and the pool batch sizes carry the workflow's values. NATIVE_SKIP (the
        # app/integration tier dropped from the Native leg) is forwarded so a host value reproduces the
        # CI cut; it is empty by default here, so a bare `build.sh podman-ci test Native` links the whole
        # set (set NATIVE_SKIP=<the build.yml list> to match the CI Native row exactly). NATIVE_HEAVY is
        # the one deliberate difference: the workflow leaves it empty because its NATIVE_SKIP list drops
        # kyo-schema-tests from the Native leg entirely, while a local run that keeps the whole set still
        # wants that module pre-linked in a driver of its own. A host value wins for each. Native target
        # only, matching the workflow's `matrix.target == 'Native'` gate.
        if [ "$platform" = Native ]; then
            envs+=(-e "NATIVE_HEAVY=${NATIVE_HEAVY-kyo-schema-tests}"
                   -e "NATIVE_LINK_CPUS=${NATIVE_LINK_CPUS-3}"
                   -e "NATIVE_LINK_BATCH=${NATIVE_LINK_BATCH-8}"
                   -e "NATIVE_TEST_BATCH=${NATIVE_TEST_BATCH-8}"
                   -e "NATIVE_SKIP=${NATIVE_SKIP-}")
        fi
    fi
    # Forward a host override of the native-run stale-output watchdog into any container run.
    [ -n "${STALE_TIMEOUT:-}" ] && envs+=(-e "STALE_TIMEOUT=$STALE_TIMEOUT")
    # Raw mode runs the arbitrary sbt command (passed via the environment to avoid host-side quoting);
    # otherwise the inner command is the standard per-platform ci-test.sh runner.
    local inner
    if [ "$RAW_MODE" = yes ]; then
        envs+=(-e "RAW_SBT=$RAW_SBT")
        inner='sbt "$RAW_SBT"'
    else
        inner="./scripts/ci-test.sh '$platform' '$ACTION'"
    fi
    # jsdom, on the same JS/Wasm condition the workflow's setup action applies. The DOM-backed kyo-ui suites
    # resolve it lazily from the repository root and abort in their constructor when it is missing, and the
    # container starts from a git archive, which never carries node_modules. Without this a DOM suite cannot
    # run in a container at all: it fails identically whether or not the code under test is broken, which
    # reads like a real failure. Gated rather than unconditional so a JVM or Native run keeps no dependency
    # on the npm registry being reachable. Raw mode has no platform, so the sbt command itself is what says
    # whether a JS or Wasm project is involved.
    local stage_jsdom=0
    if [ "$RAW_MODE" = yes ]; then
        case "$RAW_SBT" in *JS*|*Wasm*) stage_jsdom=1 ;; esac
    elif [ "$platform" = JS ] || [ "$platform" = Wasm ]; then
        stage_jsdom=1
    fi
    envs+=(-e "STAGE_JSDOM=$stage_jsdom")
    local provision; provision=$(container_provision "$platform")
    # `sh`, not `bash`: a musl JDK image ships busybox sh and no bash, and provisioning is what
    # installs bash there, so a bash entrypoint cannot get far enough to install it. This prelude is
    # POSIX throughout; the two staging scripts genuinely need bash and are invoked as `bash <script>`
    # below, by which point the package step has provided it.
    podman "${args[@]}" "${envs[@]}" "$CONTAINER_IMAGE" \
        sh -c "set -e
$provision
mkdir -p /work && cd /work && tar xf /build-input/src.tar \
    && if [ -s /build-input/changes.patch ]; then patch -p1 < /build-input/changes.patch; fi \
    && if [ \"\${STAGE_BORINGSSL:-}\" = 1 ]; then bash kyo-net/build/boringssl/build-boringssl.sh; fi \
    && if [ \"\${STAGE_AERON:-}\" = 1 ]; then bash kyo-aeron/scripts/build-aeron.sh; fi \
    && if [ \"\${STAGE_JSDOM:-}\" = 1 ]; then npm install --no-save --no-fund --no-audit jsdom@^30; fi
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
