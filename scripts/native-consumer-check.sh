#!/usr/bin/env bash
# Builds the out-of-tree Scala Native consumer of kyo-net and kyo-http (kyo-ffi/plugin/src/consumer-fixture) against artifacts
# published from this tree, runs both binaries, and checks what they select at runtime.
#
#   scripts/native-consumer-check.sh publish <version>
#   scripts/native-consumer-check.sh run <version> [--expect <key>=<value>]... [--expect <key>!=<value>]... [--expect <key>~<text>]...
#
# publish  publishLocal, at <version>, the Native closure of kyo-net and kyo-http plus kyo-ffi-plugin and kyo-ffi-codegen.
# run      copy the fixture under a scratch directory (NATIVE_CONSUMER_WORK, or a fresh temp dir), then for each app nativeLink it with
#          the sbt on PATH and run the binary. Each binary prints `kyo-consumer <key>=<value>` lines; `=` and `!=` compare the
#          first word of the value, and `~` requires the whole value to contain <text>. Keys: io_backend, tls_provider (a name, or
#          `none`), io.<backend> and tls.<provider> (`available`, or `unavailable (<reason>)`), tls_handshake (`ok`, `failed`, or
#          `skipped` when no provider is available; a loopback TLS echo through the selected provider). Every app must link and run,
#          and every expectation must hold for every app.
#
# Use a <version> no release carries: the consumer then resolves only what this tree published, and a module missing from the
# local repository fails resolution instead of being pulled from Maven Central at a released version.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$repo/kyo-ffi/plugin/src/consumer-fixture"
apps=(netApp httpApp)

usage() {
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

[ $# -ge 2 ] || usage
mode=$1
version=$2
shift 2

case "$mode" in
    publish)
        [ $# -eq 0 ] || usage
        exec "$repo/scripts/sbt.sh" publish -Dplatform=Native "set ThisBuild / version := \"$version\"" publishNativeConsumerClosure
        ;;
    run) ;;
    *) usage ;;
esac

expects=()
while [ $# -gt 0 ]; do
    case "$1" in
        --expect)
            [ $# -ge 2 ] || usage
            expects+=("$2")
            shift 2
            ;;
        *) usage ;;
    esac
done

# The fixture builds with the Scala and Scala Native versions this tree's artifacts were built with; a mismatch fails on TASTy or NIR
# the consumer cannot read, which is a different failure from the one this check exists to find.
scala_version=$(sed -nE 's/^val scala39Version *= *"([^"]+)".*/\1/p' "$repo/build.sbt")
native_version=$(sed -nE 's/.*"sbt-scala-native" *% *"([^"]+)".*/\1/p' "$repo/project/plugins.sbt")
[ -n "$scala_version" ] || { echo "native-consumer-check: no scala39Version in build.sbt" >&2; exit 2; }
[ -n "$native_version" ] || { echo "native-consumer-check: no sbt-scala-native version in project/plugins.sbt" >&2; exit 2; }

work="${NATIVE_CONSUMER_WORK:-$(mktemp -d)}/consumer-fixture"
rm -rf "$work"
mkdir -p "$work"
cp -R "$fixture/." "$work/"
cp "$repo/project/build.properties" "$work/project/build.properties"

echo "native-consumer-check: kyo $version, Scala $scala_version, Scala Native $native_version, java $(java -version 2>&1 | head -1)"
echo "native-consumer-check: fixture in $work"

failed=0
for app in "${apps[@]}"; do
    log="$work/$app.log"
    echo "=== $app"
    if (cd "$work" && "$repo/scripts/sbt.sh" link -batch \
        "-Dkyo.version=$version" "-Dkyo.scalaVersion=$scala_version" "-Dscalanative.version=$native_version" \
        "show $app/ffiNativeDependencyLinkingOptions" "show $app/ffiNativeDependencyCompileOptions" "$app/runReport") >"$log" 2>&1; then
        echo "$app: linked and ran"
    else
        echo "$app: FAILED to link or run; the last lines of $log:"
        tail -40 "$log"
        failed=1
        continue
    fi
    grep '^kyo-consumer ' "$log" || true
    for expect in "${expects[@]+"${expects[@]}"}"; do
        if [[ "$expect" == *"!="* ]]; then
            key=${expect%%!=*}
            want=${expect#*!=}
            op=ne
        elif [[ "$expect" == *"~"* && "${expect%%~*}" != *"="* ]]; then
            key=${expect%%~*}
            want=${expect#*~}
            op=contains
        else
            key=${expect%%=*}
            want=${expect#*=}
            op=eq
        fi
        line=$(grep -m1 "^kyo-consumer $key=" "$log" || true)
        if [ -z "$line" ]; then
            echo "$app: expected $expect, but the binary reported no $key"
            failed=1
            continue
        fi
        value=${line#kyo-consumer "$key"=}
        got=${value%% *}
        if { [ "$op" = eq ] && [ "$got" != "$want" ]; } || { [ "$op" = ne ] && [ "$got" = "$want" ]; } ||
            { [ "$op" = contains ] && [[ "$value" != *"$want"* ]]; }; then
            echo "$app: expected $expect, got $key=$value"
            failed=1
        fi
    done
done

if [ "$failed" != 0 ]; then
    echo "native-consumer-check: FAILED"
    exit 1
fi
echo "native-consumer-check: passed"
