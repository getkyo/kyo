#!/usr/bin/env bash
set -uo pipefail
#
# fixture-images.sh - provides the container images the selected test modules start, so no test
# leaf downloads one. The exceptions are the kyo-pod leaves whose subject is the pull itself: they
# pull or remove their image on purpose, and the registry mirror the setup action configures is
# what keeps those off Docker Hub's per-IP limit. A docker pull here names the mirror explicitly
# (`registry-mirror.sh pull`), and a docker build passes it as the Containerfiles' HUB argument.
#
# Usage: fixture-images.sh <plan-file> | --all
#        fixture-images.sh --self-test
#
# <plan-file> lists sbt projects one per line, as `testKyo --plan-file` writes them (kyo-teamsJVM,
# kyo-slackNative, ...). An entry below is provided when the plan selects its module on any
# platform; --all provides every entry. ci-test.sh runs this between compiling and testing, with
# the plan its own compile or planning pass wrote, so a job builds only what its tests will start.
#
# A `pull` entry pulls an image under the exact reference its fixture starts: the digest where the
# fixture pins one, otherwise the tag it names, since an image pulled by digest is not found under
# its tag. A `build` entry builds a Containerfile in the repository under a local tag, for an image
# no registry serves; the tag carries the version the build installs, and the fixture that starts
# it names the same tag. The live-service fixtures inspect their image first and fail with the pull
# or build command when it is missing; the kyo-pod and ContainerPredef ones pull a missing image
# themselves, so a miss here costs a leaf a download rather than failing it. Either way a failure
# here only warns, and the other suites of the job still run.
#
# postgres:16-alpine and mysql:8.0 are not listed: container-check.sh pulls them on every Linux job.
#
# Images go into every runtime that answers `version` (podman, docker), as kyo-pod may select
# either. KYO_POD_RUNTIME narrows that to one runtime, and `none` provides nothing.

FIXTURES='
pull  kyo-telegram ghcr.io/skrashevich/telegram-mock-ai@sha256:7e75f8f8d7902072a5e318ffe9f9b93240087601419b82f7137261b41a48d606
pull  kyo-whatsapp docker.io/dgadelha/whaloc@sha256:793780655dbe3be764de559625be0026a67978a3a9527e23d9d023f613c51314
pull  kyo-email    docker.io/mailserver/docker-mailserver@sha256:d0fe7668defe157aad57ea31b1707ad1e2fb57d7a91bdf17cbdf876549946c86
build kyo-teams    localhost/kyo-teams-playground:0.2.28   kyo-teams/shared/src/test/playground
build kyo-discord  localhost/kyo-discord-spacebar:0eb6f04f6d-4 kyo-discord/shared/src/test/spacebar
build kyo-slack    localhost/kyo-slack-simulator:87373b8855-1  kyo-slack/shared/src/test/slack-simulator
pull  kyo-sql-tests docker.io/dolthub/dolt-sql-server:2.3.4
pull  kyo-pod      docker.io/library/alpine:latest
pull  kyo-pod      docker.io/library/alpine:3.19
pull  kyo-pod      docker.io/library/alpine:3
pull  kyo-pod      docker.io/library/busybox:latest
pull  kyo-pod      docker.io/library/nginx:alpine
pull  kyo-pod      docker.io/library/redis:7-alpine
pull  kyo-pod      docker.io/library/mongo:7
'

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RETRY_DELAY="${RETRY_DELAY:-10}"
export MIRROR="${MIRROR:-mirror.gcr.io}"

# Three attempts: a registry or npm hiccup on a fresh runner is the usual cause.
retry3() {
    local attempt
    for attempt in 1 2 3; do
        "$@" >/dev/null 2>&1 && return 0
        [ "$attempt" -lt 3 ] && sleep "$RETRY_DELAY"
    done
    return 1
}

# The runtimes to provide into, one per line.
runtimes() {
    local rt
    case "${KYO_POD_RUNTIME:-}" in
        none) return 0 ;;
        podman | docker) set -- "$KYO_POD_RUNTIME" ;;
        *) set -- podman docker ;;
    esac
    for rt in "$@"; do
        command -v "$rt" >/dev/null 2>&1 && "$rt" version >/dev/null 2>&1 && echo "$rt"
    done
}

# Whether the plan selects `module` on some platform.
selects() {
    local plan="$1" module="$2"
    [ "$plan" = --all ] && return 0
    grep -qxE "${module}(JVM|JS|Native|Wasm)?" "$plan"
}

# docker resolves a FROM on its own, past the daemon's registry mirror, so its build names the
# mirror through the Containerfiles' HUB argument and falls back to Docker Hub only when that
# build fails. A podman build already goes through the registries.conf.d mirror.
# shellcheck disable=SC2329 # invoked through retry3
build_image() {
    local rt="$1" image="$2" context="$3"
    local args=(build -q)
    # A buildx builder other than the docker driver keeps the result in its cache unless told to
    # load it; podman builds into its store and has no such flag.
    [ "$rt" = docker ] && args+=(--load)
    args+=(-t "$image")
    local files=(-f "$REPO_ROOT/$context/Containerfile" "$REPO_ROOT/$context")
    if [ "$rt" = docker ] && "$rt" "${args[@]}" --build-arg "HUB=$MIRROR" "${files[@]}"; then
        return 0
    fi
    "$rt" "${args[@]}" "${files[@]}"
}

provide() {
    local plan="$1" rt kind module image context
    local found; found=$(runtimes)
    if [ -z "$found" ]; then
        echo "fixture-images: no container runtime answers; nothing to provide"
        return 0
    fi
    while read -r kind module image context; do
        [ -n "$kind" ] || continue
        if ! selects "$plan" "$module"; then
            echo "fixture-images: $module is not selected; skipping $image"
            continue
        fi
        for rt in $found; do
            case "$kind" in
                pull)
                    if retry3 bash "$REPO_ROOT/scripts/registry-mirror.sh" pull "$rt" "$image"; then
                        echo "fixture-images: $rt pulled $image"
                    else
                        echo "::warning title=fixture image pull failed::$rt could not pull $image; the $module leaves that start it will fail."
                    fi
                    ;;
                build)
                    if retry3 build_image "$rt" "$image" "$context" && "$rt" image inspect "$image" >/dev/null 2>&1; then
                        echo "fixture-images: $rt built $image"
                    else
                        echo "::warning title=fixture image build failed::$rt could not build $image from $context; the $module leaves that start it will fail."
                    fi
                    ;;
            esac
        done
    done <<< "$FIXTURES"
}

# -- self-test: a fake podman and docker record every call, so each case asserts which images a
# plan provides into which runtime without touching a real one. Each case's assertion is a string
# evaluated after the run, hence the single quotes. --
# shellcheck disable=SC2016,SC2034,SC2329
self_test() {
    SELF_TEST_DIR=$(mktemp -d)
    trap 'rm -rf "$SELF_TEST_DIR"' EXIT
    local dir="$SELF_TEST_DIR" pass=0 fail=0
    for rt in podman docker; do
        printf '#!/usr/bin/env bash\nprintf "%%s %%s\\n" %s "$*" >> "%s/calls"\n[ -f "%s/%s-down" ] && exit 1\n[ "$1 ${2:-}" = "pull -q" ] && [ -f "%s/pull-fails" ] && exit 1\n[ "$1" = build ] && [ -f "%s/mirror-build-fails" ] && [[ "$*" == *HUB=* ]] && exit 1\nexit 0\n' \
            "$rt" "$dir" "$dir" "$rt" "$dir" "$dir" > "$dir/$rt"
        chmod +x "$dir/$rt"
    done
    run() { : > "$dir/calls"; env -u KYO_POD_RUNTIME PATH="$dir:$PATH" RETRY_DELAY=0 MIRROR=mirror.example "$@" bash "${BASH_SOURCE[0]}" "$plan" > "$dir/out" 2>&1; }
    check() {
        if eval "$2"; then echo "  PASS: $1"; pass=$((pass + 1)); else echo "  FAIL: $1"; fail=$((fail + 1)); sed 's/^/    /' "$dir/calls" "$dir/out"; fi
    }
    calls() { grep -cE -- "$1" "$dir/calls"; }
    local pulls builds
    pulls=$(grep -c '^pull ' <<< "$FIXTURES")
    builds=$(grep -c '^build ' <<< "$FIXTURES")

    echo "Running fixture-images.sh self-tests..."

    plan="$dir/plan"; printf 'kyo-dataJVM\nkyo-coreJVM\n' > "$plan"
    run
    check "a plan without these modules provides nothing" '[ "$(calls " pull ")" = 0 ] && [ "$(calls " build ")" = 0 ]'

    printf 'kyo-dataJS\nkyo-teamsJS\nkyo-whatsappJS\n' > "$plan"
    run
    check "a plan provides only its modules' images, into both runtimes" \
        '[ "$(calls "build -q -t localhost/kyo-teams-playground:0.2.28")" = 1 ] && [ "$(calls "build -q --load -t localhost/kyo-teams-playground:0.2.28")" = 1 ] && [ "$(calls "pull -q docker.io/dgadelha/whaloc")" = 2 ] && [ "$(calls "spacebar|slack-simulator|telegram")" = 0 ]'

    printf 'kyo-sqlJVM\nkyo-sql-testsNative\n' > "$plan"
    run
    check "kyo-sql-tests pulls Dolt, and kyo-sql alone selects nothing" \
        '[ "$(calls "^podman pull -q docker.io/dolthub/dolt-sql-server:2.3.4$")" = 1 ] && [ "$(calls "^docker pull -q mirror.example/dolthub/dolt-sql-server:2.3.4$")" = 1 ] && [ "$(calls "^docker tag mirror.example/dolthub/dolt-sql-server:2.3.4 docker.io/dolthub/dolt-sql-server:2.3.4$")" = 1 ] && [ "$(calls " pull ")" = 2 ] && [ "$(calls " build ")" = 0 ]'

    printf 'kyo-podJVM\n' > "$plan"
    run
    check "kyo-pod pulls its leaves' images by the tags they name, docker's by the mirror name, and nothing else" \
        '[ "$(calls "^podman pull -q docker.io/library/(alpine:latest|alpine:3.19|alpine:3|busybox:latest|nginx:alpine|redis:7-alpine|mongo:7)$")" = 7 ] && [ "$(calls "^docker tag mirror.example/library/(alpine:latest|alpine:3.19|alpine:3|busybox:latest|nginx:alpine|redis:7-alpine|mongo:7) docker.io/library/")" = 7 ] && [ "$(calls " pull ")" = 14 ] && [ "$(calls " build ")" = 0 ]'

    printf 'kyo-slackJVM\n' > "$plan"
    run
    check "a docker build names the mirror as HUB, a podman build does not" \
        '[ "$(calls "^docker build .*--build-arg HUB=mirror.example")" = 1 ] && [ "$(calls "^docker build")" = 1 ] && [ "$(calls "^podman build .*HUB=")" = 0 ]'

    touch "$dir/mirror-build-fails"
    run
    check "a docker build the mirror fails is rebuilt from Docker Hub" \
        '[ "$(calls "^docker build")" = 2 ] && [ "$(calls "^docker build .*HUB=")" = 1 ] && ! grep -q "::warning" "$dir/out"'
    rm -f "$dir/mirror-build-fails"

    plan=--all
    run
    check "--all provides every entry" '[ "$(calls " pull -q ")" = $((2 * pulls)) ] && [ "$(calls " build -q ")" = $((2 * builds)) ]'

    run KYO_POD_RUNTIME=none
    check "KYO_POD_RUNTIME=none provides nothing" '[ ! -s "$dir/calls" ]'

    run KYO_POD_RUNTIME=podman
    check "KYO_POD_RUNTIME names the one runtime used" '[ "$(calls "^docker")" = 0 ] && [ "$(calls "^podman pull")" = "$pulls" ]'

    touch "$dir/docker-down"
    run
    check "a runtime that does not answer is left out" '[ "$(calls "^docker (pull|build)")" = 0 ] && [ "$(calls "^podman build")" = "$builds" ]'
    rm -f "$dir/docker-down"

    touch "$dir/pull-fails"
    run
    check "a failed pull is retried three times, warns, and does not fail the step" \
        '[ "$(calls "pull -q ghcr.io")" = 6 ] && grep -q "::warning title=fixture image pull failed" "$dir/out" && [ "$(calls " build -q ")" = $((2 * builds)) ]'
    rm -f "$dir/pull-fails"

    echo "fixture-images.sh self-tests: $pass passed, $fail failed"
    [ "$fail" = 0 ]
}

case "${1:-}" in
    --self-test) self_test; exit $? ;;
    "") echo "usage: $0 <plan-file> | --all | --self-test" >&2; exit 2 ;;
    --all) provide --all ;;
    *)
        [ -f "$1" ] || { echo "fixture-images: no plan file at $1" >&2; exit 2; }
        provide "$1"
        ;;
esac
exit 0
