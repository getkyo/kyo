#!/usr/bin/env bash
set -uo pipefail
#
# registry-mirror.sh - sends the runner's Docker Hub pulls to a pull-through mirror first, for podman
# and docker.
#
# Usage: registry-mirror.sh
#        registry-mirror.sh pull <podman|docker> <image>
#        registry-mirror.sh --self-test
#
# Docker Hub limits unauthenticated pulls per source IP, and a GitHub runner shares its egress IP with
# other tenants, so the allowance can be spent before a job's first pull (`toomanyrequests`, which no
# retry within a job outlasts).
#
# With no arguments it configures both runtimes; nothing in that fails the step.
#
# podman: a drop-in in the rootless user's registries.conf.d. Drop-ins merge over the system
# registries.conf instead of replacing it, so its search list and short-name aliases stay. Every
# docker.io reference (qualified or short, from a test, a pre-pull or a Containerfile FROM) is then
# tried on MIRROR first and on Docker Hub only when the mirror fails. It must be written before the
# podman API service starts, so the service sees it from its first pull.
#
# docker: `registry-mirrors` merged into the daemon.json the runner ships, keeping its other keys,
# then a daemon restart. A daemon that does not answer after the restart gets its original file
# back and another restart, so a failed mirror setup never costs the job its docker. The setting
# is a fallback only: on the runner images dockerd lists the mirror in `docker info` and still
# sent a `docker run` implicit pull of a tag the mirror serves to registry-1.docker.io.
#
# `pull` is therefore how every image a job needs reaches docker: a docker.io tag is pulled by its
# MIRROR name and tagged with the name the job uses, so docker's own registry choice never runs.
# Docker Hub is asked only when the mirror pull fails. A digest reference cannot be renamed onto
# (docker stores digests per repository) and a podman pull already goes through the drop-in, so
# both pull the reference as given.

MIRROR="${MIRROR:-mirror.gcr.io}"
REGISTRIES_CONF_D="${REGISTRIES_CONF_D:-$HOME/.config/containers/registries.conf.d}"
DOCKER_DAEMON_JSON="${DOCKER_DAEMON_JSON:-/etc/docker/daemon.json}"
DOCKER_WAIT="${DOCKER_WAIT:-30}"

configure_podman() {
    local conf="$REGISTRIES_CONF_D/50-docker-hub-mirror.conf"
    if mkdir -p "$REGISTRIES_CONF_D" \
        && printf '[[registry]]\nprefix = "docker.io"\nlocation = "docker.io"\n\n[[registry.mirror]]\nlocation = "%s"\n' "$MIRROR" > "$conf"; then
        echo "registry-mirror: podman pulls docker.io through $MIRROR ($conf)"
    else
        echo "::warning title=Docker Hub mirror not configured::could not write $conf; podman pulls go to Docker Hub directly."
    fi
}

# The repository path and tag of a Docker Hub reference (library/alpine:3 for alpine:3 or
# docker.io/library/alpine:3); fails for another registry's reference and for a digest.
docker_hub_path() {
    local ref="$1" first="${1%%/*}"
    case "$ref" in *@*) return 1 ;; esac
    if [ "$first" != "$ref" ] && { [[ "$first" == *.* ]] || [[ "$first" == *:* ]] || [ "$first" = localhost ]; }; then
        case "$first" in
            docker.io | index.docker.io) ref="${ref#*/}" ;;
            *) return 1 ;;
        esac
    fi
    [[ "$ref" == */* ]] || ref="library/$ref"
    [[ "${ref##*/}" == *:* ]] || ref="$ref:latest"
    echo "$ref"
}

pull_image() {
    local rt="$1" image="$2" path
    if [ "$rt" = docker ] && path=$(docker_hub_path "$image"); then
        if docker pull -q "$MIRROR/$path" >/dev/null && docker tag "$MIRROR/$path" "$image"; then
            docker rmi "$MIRROR/$path" >/dev/null 2>&1 || true
            return 0
        fi
        echo "registry-mirror: $MIRROR could not serve $path; pulling $image from Docker Hub" >&2
    fi
    "$rt" pull -q "$image" >/dev/null
}

docker_answers() {
    local _
    for _ in $(seq 1 "$DOCKER_WAIT"); do
        docker version >/dev/null 2>&1 && return 0
        sleep 1
    done
    return 1
}

configure_docker() {
    if ! command -v docker >/dev/null 2>&1 || ! docker version >/dev/null 2>&1; then
        echo "registry-mirror: no docker daemon answers; leaving docker unconfigured"
        return 0
    fi
    local work current merged backup=""
    work=$(mktemp -d)
    current="$work/current.json"
    merged="$work/daemon.json"
    if sudo test -f "$DOCKER_DAEMON_JSON"; then
        # shellcheck disable=SC2024 # the read needs root, the copy is this user's scratch file
        sudo cat "$DOCKER_DAEMON_JSON" > "$current"
        backup="$work/original.json"
        cp "$current" "$backup"
    else
        echo '{}' > "$current"
    fi
    if ! jq --arg m "https://$MIRROR" '.["registry-mirrors"] = ([$m] + ((.["registry-mirrors"] // []) - [$m]))' "$current" > "$merged"; then
        echo "::warning title=Docker Hub mirror not configured::$DOCKER_DAEMON_JSON is not a JSON object jq can extend; docker pulls go to Docker Hub directly."
        rm -rf "$work"
        return 0
    fi
    if [ -n "$backup" ] && cmp -s "$merged" <(jq . "$backup"); then
        echo "registry-mirror: docker already pulls docker.io through $MIRROR"
        rm -rf "$work"
        return 0
    fi
    sudo mkdir -p "$(dirname "$DOCKER_DAEMON_JSON")"
    sudo install -m 644 "$merged" "$DOCKER_DAEMON_JSON"
    sudo systemctl restart docker
    if docker_answers; then
        echo "registry-mirror: docker pulls docker.io through $MIRROR ($DOCKER_DAEMON_JSON)"
    else
        if [ -n "$backup" ]; then
            sudo install -m 644 "$backup" "$DOCKER_DAEMON_JSON"
        else
            sudo rm -f "$DOCKER_DAEMON_JSON"
        fi
        sudo systemctl restart docker
        docker_answers || true
        echo "::warning title=Docker Hub mirror not configured::docker did not answer after adding $MIRROR to $DOCKER_DAEMON_JSON; the original file is restored and docker pulls go to Docker Hub directly."
    fi
    rm -rf "$work"
}

# -- self-test: fake sudo, systemctl and docker on PATH, and the two configuration paths pointed into
# a temporary directory. The fake daemon goes down on restart when `break-on-mirror` exists and the
# daemon.json names the mirror, which is how the restore path is driven. --
# shellcheck disable=SC2016,SC2329
self_test() {
    SELF_TEST_DIR=$(mktemp -d)
    trap 'rm -rf "$SELF_TEST_DIR"' EXIT
    local dir="$SELF_TEST_DIR" pass=0 fail=0
    mkdir -p "$dir/bin"
    printf '#!/usr/bin/env bash\nexec "$@"\n' > "$dir/bin/sudo"
    printf '#!/usr/bin/env bash\necho "systemctl $*" >> "%s/calls"\nif [ -f "%s/break-on-mirror" ] && grep -q mirror "%s/daemon.json" 2>/dev/null; then touch "%s/docker-down"; else rm -f "%s/docker-down"; fi\n' \
        "$dir" "$dir" "$dir" "$dir" "$dir" > "$dir/bin/systemctl"
    for rt in docker podman; do
        printf '#!/usr/bin/env bash\n[ "$1" = version ] || echo "%s $*" >> "%s/calls"\n[ -f "%s/no-docker" ] && exit 1\n[ -f "%s/docker-down" ] && exit 1\n[ "$1" = pull ] && [ -f "%s/mirror-fails" ] && [[ "$*" == *mirror.example* ]] && exit 1\nexit 0\n' \
            "$rt" "$dir" "$dir" "$dir" "$dir" > "$dir/bin/$rt"
    done
    chmod +x "$dir/bin/"*
    run() {
        : > "$dir/calls"
        PATH="$dir/bin:$PATH" MIRROR=mirror.example REGISTRIES_CONF_D="$dir/registries.conf.d" \
            DOCKER_DAEMON_JSON="$dir/daemon.json" DOCKER_WAIT=1 bash "${BASH_SOURCE[0]}" "$@" > "$dir/out" 2>&1
        echo "$?" > "$dir/rc"
    }
    calls() { grep -vE '^systemctl' "$dir/calls" | paste -sd ';' -; }
    check() {
        if eval "$2"; then echo "  PASS: $1"; pass=$((pass + 1)); else echo "  FAIL: $1"; fail=$((fail + 1)); sed 's/^/    /' "$dir/calls" "$dir/out"; fi
    }
    daemon() { jq -c "$1" "$dir/daemon.json"; }
    restarts() { grep -c 'restart docker' "$dir/calls"; }

    echo "Running registry-mirror.sh self-tests..."

    printf '{"exec-opts":["native.cgroupdriver=cgroupfs"],"cgroup-parent":"/actions_job"}' > "$dir/daemon.json"
    run
    check "podman gets a docker.io drop-in whose mirror is MIRROR" \
        'grep -qx "prefix = \"docker.io\"" "$dir/registries.conf.d/50-docker-hub-mirror.conf" && grep -qx "location = \"mirror.example\"" "$dir/registries.conf.d/50-docker-hub-mirror.conf"'
    check "docker gets the mirror and keeps the runner's keys, with one restart" \
        '[ "$(daemon ".\"registry-mirrors\"")" = "[\"https://mirror.example\"]" ] && [ "$(daemon ".\"cgroup-parent\"")" = "\"/actions_job\"" ] && [ "$(restarts)" = 1 ]'

    run
    check "a second run neither duplicates the mirror nor restarts docker" \
        '[ "$(daemon ".\"registry-mirrors\"")" = "[\"https://mirror.example\"]" ] && [ "$(restarts)" = 0 ]'

    printf '{"registry-mirrors":["https://other.example"]}' > "$dir/daemon.json"
    run
    check "an existing mirror list keeps its entries behind MIRROR" \
        '[ "$(daemon ".\"registry-mirrors\"")" = "[\"https://mirror.example\",\"https://other.example\"]" ]'

    rm -f "$dir/daemon.json"
    run
    check "a runner without daemon.json gets one" '[ "$(daemon ".\"registry-mirrors\"")" = "[\"https://mirror.example\"]" ]'

    printf '{"cgroup-parent":"/actions_job"}' > "$dir/daemon.json"
    touch "$dir/break-on-mirror"
    run
    check "a daemon that does not come back gets its original file and a second restart, and the step passes" \
        '[ "$(daemon .)" = "{\"cgroup-parent\":\"/actions_job\"}" ] && [ "$(restarts)" = 2 ] && [ ! -f "$dir/docker-down" ] && grep -q "::warning title=Docker Hub mirror not configured" "$dir/out"'
    rm -f "$dir/break-on-mirror"

    printf 'not json' > "$dir/daemon.json"
    run
    check "a daemon.json jq cannot read is left alone with a warning" \
        '[ "$(cat "$dir/daemon.json")" = "not json" ] && [ "$(restarts)" = 0 ] && grep -q "::warning" "$dir/out"'

    printf '{}' > "$dir/daemon.json"
    touch "$dir/no-docker"
    run
    check "no docker daemon leaves daemon.json alone and still configures podman" \
        '[ "$(cat "$dir/daemon.json")" = "{}" ] && [ "$(restarts)" = 0 ] && [ -f "$dir/registries.conf.d/50-docker-hub-mirror.conf" ]'
    rm -f "$dir/no-docker"

    run pull docker eclipse-temurin:25-jdk-alpine
    check "docker pulls a short Hub tag by its mirror name and tags it with the name given" \
        '[ "$(calls)" = "docker pull -q mirror.example/library/eclipse-temurin:25-jdk-alpine;docker tag mirror.example/library/eclipse-temurin:25-jdk-alpine eclipse-temurin:25-jdk-alpine;docker rmi mirror.example/library/eclipse-temurin:25-jdk-alpine" ]'

    run pull docker docker.io/dolthub/dolt-sql-server:2.3.4
    check "a qualified Hub reference keeps its repository path on the mirror" \
        '[[ "$(calls)" == "docker pull -q mirror.example/dolthub/dolt-sql-server:2.3.4;docker tag mirror.example/dolthub/dolt-sql-server:2.3.4 docker.io/dolthub/dolt-sql-server:2.3.4;"* ]]'

    run pull docker alpine
    check "a reference without a tag is the latest tag" '[[ "$(calls)" == "docker pull -q mirror.example/library/alpine:latest;docker tag mirror.example/library/alpine:latest alpine;"* ]]'

    touch "$dir/mirror-fails"
    run pull docker docker.io/library/mongo:7
    check "a mirror that fails sends the pull to Docker Hub" \
        '[ "$(calls)" = "docker pull -q mirror.example/library/mongo:7;docker pull -q docker.io/library/mongo:7" ]'
    rm -f "$dir/mirror-fails"

    run pull docker docker.io/dgadelha/whaloc@sha256:79
    check "a digest reference is pulled as given" '[ "$(calls)" = "docker pull -q docker.io/dgadelha/whaloc@sha256:79" ]'

    run pull docker ghcr.io/owner/image:1
    check "another registry's reference is pulled as given" '[ "$(calls)" = "docker pull -q ghcr.io/owner/image:1" ]'

    run pull podman docker.io/library/alpine:3
    check "podman pulls as given, through its drop-in" '[ "$(calls)" = "podman pull -q docker.io/library/alpine:3" ]'

    touch "$dir/no-docker"
    run pull docker alpine:3
    check "a pull that fails both ways fails" '[ "$(grep -c "^docker pull" "$dir/calls")" = 2 ] && [ "$(cat "$dir/rc")" != 0 ]'
    rm -f "$dir/no-docker"

    echo "registry-mirror.sh self-tests: $pass passed, $fail failed"
    [ "$fail" = 0 ]
}

case "${1:-}" in
    --self-test) self_test; exit $? ;;
    pull)
        if [ $# -ne 3 ] || { [ "$2" != podman ] && [ "$2" != docker ]; }; then
            echo "usage: $0 pull <podman|docker> <image>" >&2
            exit 2
        fi
        pull_image "$2" "$3"
        exit $?
        ;;
    "") ;;
    *) echo "usage: $0 [pull <podman|docker> <image> | --self-test]" >&2; exit 2 ;;
esac

configure_podman
configure_docker
exit 0
