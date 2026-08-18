#!/usr/bin/env bash
set -uo pipefail
#
# container-check.sh - health gate for the container runtimes the kyo-pod suites depend on.
#
# Usage: container-check.sh [--warn-only]
#
# Runs two checks and prints component diagnostics for each:
#   1. podman: create + start + exec a real container. This is the exact cycle the 20260810.x
#      runner image regression broke (conmon without journald support exited 1 on every start,
#      and each exec then failed with HTTP 500 "container state improper"; see #1881). The
#      resolved OCI runtime and conmon paths are printed first, so a failure names the
#      component instead of a bare exit code.
#   2. docker: the daemon answers `docker version`. Ubuntu's runc package Conflicts with
#      containerd.io, and an apt resolution that removes docker-ce has taken the daemon out
#      from under CI before; the kyo-pod suites run half of their container cases against it.
#
# Both checks always run, so one broken runtime does not hide the state of the other. By
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

check_podman
check_docker

if [ -n "$failed" ]; then
    if [ -n "$warn_only" ]; then
        echo "::warning title=container runtime check failed::podman or docker is broken on this runner; the kyo-pod container suites will fail. See the 'Start podman' step log. Running in --warn-only mode, so the job continues."
        exit 0
    fi
    exit 1
fi
