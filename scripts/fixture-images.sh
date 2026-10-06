#!/usr/bin/env bash
set -uo pipefail
#
# fixture-images.sh - provides the container images the selected test modules start, so no test
# leaf downloads one.
#
# Usage: fixture-images.sh <plan-file> | --all
#        fixture-images.sh --self-test
#
# <plan-file> lists sbt projects one per line, as `testKyo --plan-file` writes them (kyo-teamsJVM,
# kyo-slackNative, ...). An entry below is provided when the plan selects its module on any
# platform; --all provides every entry. ci-test.sh runs this between compiling and testing, with
# the plan its own compile or planning pass wrote, so a job builds only what its tests will start.
#
# A `pull` entry pulls an image pinned by digest. A `build` entry builds a Containerfile in the
# repository under a local tag, for an image no registry serves; the tag carries the version the
# build installs, and the fixture that starts it names the same tag. Every fixture inspects its
# image first and fails with the pull or build command when it is missing, so a miss here is a loud
# test failure, never a download from inside a leaf. That is also why a failure here only warns:
# the leaves that need the image fail with the fix, and the other suites of the job still run.
#
# Images go into every runtime that answers `version` (podman, docker), as kyo-pod may select
# either. KYO_POD_RUNTIME narrows that to one runtime, and `none` provides nothing.

FIXTURES='
pull  kyo-telegram ghcr.io/skrashevich/telegram-mock-ai@sha256:7e75f8f8d7902072a5e318ffe9f9b93240087601419b82f7137261b41a48d606
pull  kyo-whatsapp docker.io/dgadelha/whaloc@sha256:793780655dbe3be764de559625be0026a67978a3a9527e23d9d023f613c51314
build kyo-teams    localhost/kyo-teams-playground:0.2.28   kyo-teams/shared/src/test/playground
build kyo-discord  localhost/kyo-discord-spacebar:0eb6f04f6d-4 kyo-discord/shared/src/test/spacebar
build kyo-slack    localhost/kyo-slack-simulator:87373b8855-1  kyo-slack/shared/src/test/slack-simulator
'

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RETRY_DELAY="${RETRY_DELAY:-10}"

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

provide() {
    local plan="$1" rt kind module image context load
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
                    if retry3 "$rt" pull -q "$image"; then
                        echo "fixture-images: $rt pulled $image"
                    else
                        echo "::warning title=fixture image pull failed::$rt could not pull $image; the $module leaves that start it will fail."
                    fi
                    ;;
                build)
                    # A buildx builder other than the docker driver keeps the result in its cache
                    # unless told to load it; podman builds into its store and has no such flag.
                    load=()
                    [ "$rt" = docker ] && load=(--load)
                    if retry3 "$rt" build -q ${load[@]+"${load[@]}"} -t "$image" -f "$REPO_ROOT/$context/Containerfile" "$REPO_ROOT/$context" \
                        && "$rt" image inspect "$image" >/dev/null 2>&1; then
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
# shellcheck disable=SC2016,SC2329
self_test() {
    SELF_TEST_DIR=$(mktemp -d)
    trap 'rm -rf "$SELF_TEST_DIR"' EXIT
    local dir="$SELF_TEST_DIR" pass=0 fail=0
    for rt in podman docker; do
        printf '#!/usr/bin/env bash\nprintf "%%s %%s\\n" %s "$*" >> "%s/calls"\n[ -f "%s/%s-down" ] && exit 1\n[ "$1 ${2:-}" = "pull -q" ] && [ -f "%s/pull-fails" ] && exit 1\nexit 0\n' \
            "$rt" "$dir" "$dir" "$rt" "$dir" > "$dir/$rt"
        chmod +x "$dir/$rt"
    done
    run() { : > "$dir/calls"; env -u KYO_POD_RUNTIME PATH="$dir:$PATH" RETRY_DELAY=0 "$@" bash "${BASH_SOURCE[0]}" "$plan" > "$dir/out" 2>&1; }
    check() {
        if eval "$2"; then echo "  PASS: $1"; pass=$((pass + 1)); else echo "  FAIL: $1"; fail=$((fail + 1)); sed 's/^/    /' "$dir/calls" "$dir/out"; fi
    }
    calls() { grep -cE -- "$1" "$dir/calls"; }

    echo "Running fixture-images.sh self-tests..."

    plan="$dir/plan"; printf 'kyo-dataJVM\nkyo-coreJVM\n' > "$plan"
    run
    check "a plan without these modules provides nothing" '[ "$(calls " pull ")" = 0 ] && [ "$(calls " build ")" = 0 ]'

    printf 'kyo-dataJS\nkyo-teamsJS\nkyo-whatsappJS\n' > "$plan"
    run
    check "a plan provides only its modules' images, into both runtimes" \
        '[ "$(calls "build -q -t localhost/kyo-teams-playground:0.2.28")" = 1 ] && [ "$(calls "build -q --load -t localhost/kyo-teams-playground:0.2.28")" = 1 ] && [ "$(calls "pull -q docker.io/dgadelha/whaloc")" = 2 ] && [ "$(calls "spacebar|slack-simulator|telegram")" = 0 ]'

    plan=--all
    run
    check "--all provides every entry" '[ "$(calls " pull -q ")" = 4 ] && [ "$(calls " build -q ")" = 6 ]'

    run KYO_POD_RUNTIME=none
    check "KYO_POD_RUNTIME=none provides nothing" '[ ! -s "$dir/calls" ]'

    run KYO_POD_RUNTIME=podman
    check "KYO_POD_RUNTIME names the one runtime used" '[ "$(calls "^docker")" = 0 ] && [ "$(calls "^podman pull")" = 2 ]'

    touch "$dir/docker-down"
    run
    check "a runtime that does not answer is left out" '[ "$(calls "^docker (pull|build)")" = 0 ] && [ "$(calls "^podman build")" = 3 ]'
    rm -f "$dir/docker-down"

    touch "$dir/pull-fails"
    run
    check "a failed pull is retried three times, warns, and does not fail the step" \
        '[ "$(calls "pull -q ghcr.io")" = 6 ] && grep -q "::warning title=fixture image pull failed" "$dir/out" && [ "$(calls " build -q ")" = 6 ]'
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
