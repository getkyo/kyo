#!/usr/bin/env bash
set -uo pipefail
#
# container-check.sh - health gate for the container runtimes the kyo-pod suites depend on.
#
# Usage: container-check.sh [--warn-only]
#
# Runs three checks and prints component diagnostics for each:
#   1. podman startup: create + start + exec a real container. This is the exact cycle the
#      20260810.x runner image regression broke (conmon without journald support exited 1 on
#      every start, and each exec then failed with HTTP 500 "container state improper"; see
#      #1881). The resolved OCI runtime and conmon paths are printed first, so a failure names
#      the component instead of a bare exit code.
#   2. podman teardown: stop a container that ignores SIGTERM and assert its host process is
#      really gone. The #1881 probe validated create/start/exec and stopped there, so it passed
#      on a stack whose stop/kill/rm-f all answer HTTP 500 "given PID did not die within
#      timeout" and leave the container running forever. That is a leak engine, not a flake: one
#      CI job accumulated 28 unkillable mysqld totalling 4.6G and paid ~45s per scoped teardown.
#      When the configured OCI runtime fails this gate the script retries under the alternative
#      runtime and, if that one passes, pins it in containers.conf and restarts the API service.
#   3. docker: the daemon answers `docker version`. Ubuntu's runc package Conflicts with
#      containerd.io, and an apt resolution that removes docker-ce has taken the daemon out
#      from under CI before; the kyo-pod suites run half of their container cases against it.
#
# It also pre-pulls the database images the kyo-pod and kyo-sql fixtures use, into whichever
# runtimes answered, so first-use pull latency and registry flake stay out of the test timing
# budget. Those pulls are best-effort: a failed pre-pull warns and leaves first-use pulling to
# the suite, exactly as before.
#
# Both runtime checks always run, so one broken runtime does not hide the state of the other. By
# default any failed check fails the job. With --warn-only the same checks and diagnostics run,
# but the script emits a GitHub warning annotation and exits 0: the switch for maintainers who
# later decide the gate should be loud instead of fatal, without restructuring the workflow.
#
# Expects a running rootless podman API service and XDG_RUNTIME_DIR pointing at its runtime
# dir (the "Start podman" step provides both). If the service wrote a log to /tmp/podman.log,
# a podman failure appends it to the diagnostics.

warn_only=""
if [ "${1:-}" = "--warn-only" ]; then
    warn_only=1
    shift
fi
[ $# -eq 0 ] || { echo "usage: $0 [--warn-only]" >&2; exit 2; }

failed=""

# Images every container-using suite needs. Pre-pulled rather than left to first use so a slow
# or flaky registry is a setup-step failure with its own log line instead of minutes charged to
# whichever test happened to ask for the image first.
FIXTURE_IMAGES="docker.io/library/postgres:16-alpine docker.io/library/mysql:8.0"

check_podman() {
    podman version
    # Which OCI runtime and conmon podman resolved. Printed rather than asserted on: the probe
    # below is the real gate; these lines attribute a probe failure to the component.
    podman info | grep -iE 'ociRuntime|runc|crun|conmon|rootless|cgroup|path:' || true
    if podman pull -q docker.io/library/alpine:3 \
        && podman create --name setup-probe docker.io/library/alpine:3 sleep 60 >/dev/null \
        && podman start setup-probe >/dev/null \
        && [ "$(podman inspect --format '{{.State.Status}}' setup-probe)" = "running" ] \
        && [ "$(podman exec setup-probe echo probe-exec-ok)" = "probe-exec-ok" ]; then
        echo "podman check passed (create + start + exec)"
    else
        echo "podman check failed: a freshly started container is not running or not exec-able." >&2
        echo "Every kyo-pod podman case would fail the same way. Container state:" >&2
        podman inspect --format '{{.State.Status}} exit={{.State.ExitCode}} err={{.State.Error}}' setup-probe >&2 || true
        if [ -f /tmp/podman.log ]; then
            echo "service log:" >&2
            cat /tmp/podman.log >&2
        fi
        failed=1
    fi
    podman rm -f setup-probe >/dev/null 2>&1 || true
}

# --- podman teardown gate ---

# One stop/kill cycle against a container that refuses to die politely, which is what every
# database fixture is: `trap "" TERM` makes SIGTERM a no-op, so only the runtime's SIGKILL can
# end it, and that SIGKILL is the step the broken stack cannot perform. 0 when the container
# ends up in a terminal state AND its host process is gone; 1 otherwise. The pid check is the
# part that matters: the daemon reporting "exited" while the process still runs is exactly the
# state that leaked 28 mysqld in one job.
teardown_probe() {
    local name="teardown-probe" pid status rc=0
    podman rm -f "$name" >/dev/null 2>&1 || true
    if ! podman run -d --name "$name" docker.io/library/alpine:3 \
        sh -c 'trap "" TERM; sleep 300' >/dev/null 2>&1; then
        echo "teardown probe could not start its container" >&2
        return 1
    fi
    # The trap is installed by the shell after exec, so a stop racing the very first
    # instructions would test nothing.
    sleep 1
    pid="$(podman inspect --format '{{.State.Pid}}' "$name" 2>/dev/null)"
    podman stop -t 1 "$name" >/dev/null 2>&1 || true
    status="$(podman inspect --format '{{.State.Status}}' "$name" 2>/dev/null)"
    if [ "$status" != "exited" ] && [ "$status" != "stopped" ]; then
        echo "teardown probe: container is '$status' after stop -t 1, expected exited" >&2
        rc=1
    fi
    if [ -n "$pid" ] && [ "$pid" != "0" ] && kill -0 "$pid" 2>/dev/null; then
        echo "teardown probe: container process $pid is still alive after stop" >&2
        rc=1
    fi
    if ! podman rm -f "$name" >/dev/null 2>&1; then
        echo "teardown probe: force-remove failed" >&2
        rc=1
    fi
    podman rm -f "$name" >/dev/null 2>&1 || true
    return "$rc"
}

# Restart the rootless API service so a containers.conf runtime change takes effect: libpod
# reads its engine config once, at service start. Mirrors the workflow's "Start podman" step,
# which is the only other place that starts this service.
restart_podman_service() {
    local sock="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"
    pkill -u "$(id -u)" -f "podman system service" 2>/dev/null || true
    rm -f "$sock"
    nohup podman system service --time=0 "unix://$sock" >> /tmp/podman.log 2>&1 &
    for _ in $(seq 1 30); do
        [ -S "$sock" ] && return 0
        sleep 1
    done
    echo "podman API socket did not come back up after a runtime switch" >&2
    return 1
}

pin_oci_runtime() {
    mkdir -p "$HOME/.config/containers"
    printf '[engine]\nruntime = "%s"\n' "$1" > "$HOME/.config/containers/containers.conf"
}

check_podman_teardown() {
    local configured alternative
    configured="$(podman info --format '{{.Host.OCIRuntime.Name}}' 2>/dev/null)"
    echo "podman teardown gate: probing OCI runtime '${configured:-unknown}'"
    if teardown_probe; then
        echo "podman teardown check passed (stop kills a SIGTERM-ignoring container)"
        return
    fi

    # The runc pin exists for the pre-20260810 crun's "unknown version specified" start failure
    # (#1881); it was validated for create/start/exec and never for stop. If the other runtime
    # is installed and can tear a container down, it is strictly better than a stack that
    # cannot: a container that starts but never dies is the worse of the two failure modes.
    case "$configured" in
        crun) alternative=runc ;;
        *)    alternative=crun ;;
    esac
    if ! command -v "$alternative" >/dev/null 2>&1; then
        echo "podman teardown check failed and no alternative OCI runtime is installed." >&2
        echo "Every scoped container fixture will leak: stop, kill and force-remove all leave the" >&2
        echo "container's process running (HTTP 500 'given PID did not die within timeout')." >&2
        failed=1
        return
    fi

    echo "podman teardown gate: '${configured:-unknown}' cannot stop a container; retrying under '$alternative'"
    pin_oci_runtime "$alternative"
    if ! restart_podman_service; then
        failed=1
        return
    fi
    if teardown_probe; then
        echo "podman teardown check passed under '$alternative' (pinned in containers.conf)"
        echo "::warning title=podman OCI runtime switched::'${configured:-unknown}' could not stop a container; the job now runs under '$alternative'."
        return
    fi

    echo "podman teardown check failed under both '${configured:-unknown}' and '$alternative'." >&2
    if [ -f /tmp/podman.log ]; then
        echo "service log:" >&2
        tail -50 /tmp/podman.log >&2
    fi
    # Leave the originally configured runtime in place: neither works, and the pin the workflow
    # chose is the one the rest of CI was validated against.
    pin_oci_runtime "${configured:-runc}"
    restart_podman_service || true
    failed=1
}

check_docker() {
    if docker version; then
        echo "docker check passed"
    else
        echo "docker daemon unreachable. The kyo-pod suite runs its container cases against docker" >&2
        echo "as well as podman. Check the apt output in the podman install step: the ubuntu runc" >&2
        echo "package Conflicts with containerd.io, and installing it removes docker-ce." >&2
        failed=1
    fi
}

# Best-effort by design: a missing image costs the first suite that wants it a pull, which is
# the pre-existing behavior. Failing the gate on it would trade a timing improvement for a new
# way to lose a whole job to registry flake.
prepull_fixture_images() {
    local runtime image
    for runtime in "$@"; do
        for image in $FIXTURE_IMAGES; do
            if "$runtime" pull -q "$image" >/dev/null 2>&1; then
                echo "$runtime pre-pulled $image"
            else
                echo "::warning title=fixture image pre-pull failed::$runtime could not pull $image; the first suite that needs it will pull it instead."
            fi
        done
    done
}

check_podman
podman_ok=$([ -z "$failed" ] && echo 1 || echo "")
check_podman_teardown
check_docker

runtimes=""
[ -n "$podman_ok" ] && runtimes="podman"
docker version >/dev/null 2>&1 && runtimes="$runtimes docker"
# shellcheck disable=SC2086
[ -n "$runtimes" ] && prepull_fixture_images $runtimes

if [ -n "$failed" ]; then
    if [ -n "$warn_only" ]; then
        echo "::warning title=container runtime check failed::podman or docker is broken on this runner; the kyo-pod container suites will fail. See the 'Start podman' step log. Running in --warn-only mode, so the job continues."
        exit 0
    fi
    exit 1
fi
