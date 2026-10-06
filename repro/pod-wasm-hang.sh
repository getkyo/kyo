#!/usr/bin/env bash
# Usage: repro/pod-wasm-hang.sh <iterations> [test-class]; loops kyo-podWasm testOnly and, when the log stops growing for
# 6 minutes, dumps the process tree and the JS stack of every busy node process, then stops.
set +e +o pipefail
iters=${1:-10}
cls=${2:-kyo.ContainerItTest}
echo kyo-podWasm > /tmp/pod-plan
scripts/fixture-images.sh /tmp/pod-plan
docker version --format 'docker {{.Server.Version}}'
podman version --format 'podman {{.Server.Version}}'
for i in $(seq 1 "$iters"); do
    log=/tmp/pod-wasm-$i.log
    echo "=== iteration $i start $(date -u +%T)"
    ./scripts/sbt.sh run "kyo-podWasm/testOnly $cls" > "$log" 2>&1 &
    sbtpid=$!
    last=0
    still=0
    hung=no
    while kill -0 "$sbtpid" 2>/dev/null; do
        sleep 30
        size=$(stat -c %s "$log")
        if [ "$size" = "$last" ]; then still=$((still + 30)); else still=0; last=$size; fi
        if [ "$still" -ge 360 ]; then hung=yes; break; fi
    done
    grep -aE "^\[(PASS|FAIL|TIMEOUT|STUCK)\]|kyo-test:|\*\*\* FAILED|WARN kyo" "$log" | sed 's/\x1b\[[0-9;]*m//g' | grep -avE "^\[PASS\]" | tail -40
    grep -acE "^\[PASS\]" "$log" | sed 's/^/passed leaves: /'
    if [ "$hung" = yes ]; then
        echo "=== HANG in iteration $i at $(date -u +%T); last lines:"
        sed 's/\x1b\[[0-9;]*m//g' "$log" | tail -15
        echo "=== process tree"
        ps -eo pid,ppid,pcpu,rss,etime,args --forest | grep -avE "ps -eo|grep" | cut -c1-260
        echo "=== containers"
        docker ps -a --format '{{.ID}} {{.Status}} {{.Image}} {{.Labels}}' | cut -c1-200
        podman ps -a --format '{{.ID}} {{.Status}} {{.Image}}'
        port=9229
        for p in $(pgrep -x node; pgrep -x MainThread); do
            cpu=$(ps -o pcpu= -p "$p")
            echo "=== node pid $p cpu $cpu args: $(tr '\0' ' ' < /proc/$p/cmdline | cut -c1-200)"
            echo "--- kernel stack wchan: $(cat /proc/$p/wchan 2>/dev/null)"
            ls -l /proc/$p/fd 2>/dev/null | wc -l | sed 's/^/open fds: /'
            kill -USR1 "$p"
            sleep 3
            node repro/cdp-stack.mjs "$port"
            port=$((port + 1))
        done
        kill "$sbtpid"
        pkill -f "kyo-podWasm"
        exit 1
    fi
    wait "$sbtpid"
    echo "=== iteration $i exit $? end $(date -u +%T)"
done
echo "=== no hang in $iters iterations"
