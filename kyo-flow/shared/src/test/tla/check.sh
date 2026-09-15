#!/usr/bin/env bash
# Model-check kyo-flow's claim lifecycle with TLC, enforcing the polarity of
# every config.
#
#   ./check.sh              run every config in the table below except Wide
#   ./check.sh Designed     run one or more by name, Wide included
#
# Run it sequentially. It passes TLC -cleanup, so two runs at once wipe each
# other's state directory and both die reading the disk.
#
# Each config has an expectation: a green config must complete with no error,
# and a control must report a violation of the property named for it. A control
# that stays green is a failure of the MODEL, and this script fails on it. That
# is the enforcement the first version of this spec lacked: it checked clean and
# proved nothing, and nobody noticed because the controls were a human reading
# logs.
#
# Downloads tla2tools.jar on first use into this directory; it is gitignored.
# TLC is a JVM tool, so this needs a JDK on PATH and nothing else.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$here/tla2tools.jar"
url="https://github.com/tlaplus/tlaplus/releases/latest/download/tla2tools.jar"

if [[ ! -f "$jar" ]]; then
    echo "fetching tla2tools.jar"
    curl -sSL -o "$jar" "$url"
fi

# name:expectation. "green" or "invariant <Name>" or "temporal".
# TLC names a violated invariant; it does not name a violated temporal property,
# so a temporal control lists exactly one PROPERTY in its config.
table=(
    "Designed:green"
    "Signalled:green"
    "Wide:green"
    "Vacuity:invariant BelieverAlwaysRight"
    "Refusal:invariant NeverRefused"
    "TokenOnly:invariant NoLapsedWrite"
    "TokenOnlyFinish:invariant NoLapsedWrite"
    "OwnerWitness:invariant NoLapsedWrite"
    "ReleaseGap:invariant NoLapsedWrite"
    "UnscopedCancel:invariant NoUselessClaim"
    "NoWaitClearing:invariant ProgressAtomic"
    "Wedge:invariant NoWedge"
    "LoserRow:invariant LedgerBlessed"
    "NoCancelArm:temporal"
    "MovedWake:temporal"
)

expectation_of() {
    local name="$1" row
    for row in "${table[@]}"; do
        if [[ "${row%%:*}" == "$name" ]]; then
            echo "${row#*:}"
            return 0
        fi
    done
    echo "unknown config: $name" >&2
    return 1
}

# Configs the no-argument sweep leaves out. `Wide` is a real config with a real
# expectation and `./check.sh Wide` runs it and reads its polarity like any
# other; what it cannot do is REACH a verdict at those sizes. Leaving it in the
# default sweep would mean the default sweep can never pass, and a runner whose
# exit code is always non-zero teaches everyone to stop reading it, which is the
# same failure as a control nobody checks. Run it by name to explore a wider
# state space than the sweep reaches, expecting to stop it rather than finish it.
default_skip=("Wide")

if [[ $# -gt 0 ]]; then
    names=("$@")
else
    names=()
    for row in "${table[@]}"; do
        name="${row%%:*}"
        skipped="no"
        for s in "${default_skip[@]}"; do
            [[ "$name" == "$s" ]] && skipped="yes"
        done
        [[ "$skipped" == "no" ]] && names+=("$name")
    done
fi

logs="$here/logs"
mkdir -p "$logs"

failures=0
for name in "${names[@]}"; do
    expect="$(expectation_of "$name")" || { failures=$((failures + 1)); continue; }
    log="$logs/$name.log"
    echo
    echo "=============================================================="
    echo "  $name    (expected: $expect)"
    echo "=============================================================="
    # Three workers, not "auto". This machine runs several sessions at once and
    # TLC will take every core it is given. Override with TLC_WORKERS.
    #
    # Liveness is checked once at the end for every config EXCEPT the temporal
    # controls, because the periodic passes grow with the state count (on
    # Wide.cfg they went 1:52, 2:52, 4:17 and were still growing at 30M states)
    # and pause exploration while they run. A temporal control is the one case
    # where that trade inverts: its whole purpose is to REACH a violation, the
    # violation is usually shallow, and "final" makes it build the entire state
    # graph first. MovedWake passed 54 million distinct states with its queue
    # still growing before it could report a counterexample that a periodic pass
    # finds in minutes. Either way the counterexample is the same; what changes
    # is when it is reported.
    lncheck=(-lncheck final)
    [[ "$expect" == "temporal" ]] && lncheck=()

    java -XX:+UseParallelGC -cp "$jar" tlc2.TLC \
        -config "$here/$name.cfg" \
        -workers "${TLC_WORKERS:-3}" \
        "${lncheck[@]}" \
        -cleanup \
        "$here/FlowClaim.tla" > "$log" 2>&1
    grep -E 'states generated|distinct states|Finished in|The depth of' "$log" | sed 's/^/    /'

    verdict="fail"
    case "$expect" in
        green)
            if grep -q 'No error has been found' "$log"; then verdict="ok"; fi
            ;;
        "invariant "*)
            prop="${expect#invariant }"
            if grep -q "Invariant $prop is violated" "$log"; then verdict="ok"; fi
            ;;
        temporal)
            if grep -q 'Temporal properties were violated' "$log"; then verdict="ok"; fi
            ;;
    esac

    if [[ "$verdict" == "ok" ]]; then
        echo "    [$name] OK: $expect"
    else
        failures=$((failures + 1))
        echo "    [$name] FAILED: expected '$expect'; see $log"
        grep -E '^Error|violated|No error' "$log" | head -5 | sed 's/^/        /'
    fi
done

echo
if [[ $failures -eq 0 ]]; then
    echo "all ${#names[@]} configs at their expected result"
else
    echo "$failures of ${#names[@]} configs NOT at their expected result"
fi
exit $failures
