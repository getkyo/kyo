#!/usr/bin/env bash
# Installs the Chrome-for-Testing builds the kyo-browser tests launch, before any test runs, so no
# test leaf downloads Chrome on CI.
#
#   scripts/chrome-preload.sh version              print the tested version
#   scripts/chrome-preload.sh install <os> <root>  install it under <root> for the CI pole <os>
#
# The version is ChromeDownloader.testedVersion, read from its source line, so the tests and this
# script cannot name different builds. The install layout must stay the one ChromeDownloader.ensure
# reads: <root>/<artifact>-<version>-<platform>/ holding the extracted zip and the
# .kyo-browser-installed marker. A directory it does not recognize as an install it downloads again,
# inside whichever leaf asks first.
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
source_file="$repo/kyo-browser/shared/src/main/scala/kyo/internal/ChromeDownloader.scala"
artifacts=(chrome-headless-shell chrome)
marker=.kyo-browser-installed

tested_version() {
    local matches
    matches=$(sed -n 's/^ *private\[kyo\] val testedVersion = "\([0-9.]*\)"$/\1/p' "$source_file")
    if [ -z "$matches" ] || [ "$(printf '%s\n' "$matches" | wc -l | tr -d ' ')" != 1 ]; then
        echo "expected exactly one 'private[kyo] val testedVersion = \"<version>\"' line in $source_file" >&2
        exit 1
    fi
    printf '%s\n' "$matches"
}

platform_for() {
    case "$1" in
        linux-x64)   echo linux64 ;;
        windows-x64) echo win64 ;;
        macos-arm64) echo mac-arm64 ;;
        macos-x64)   echo mac-x64 ;;
        *)           echo "" ;;
    esac
}

executable_for() {
    local artifact="$1" platform="$2"
    case "$artifact:$platform" in
        chrome-headless-shell:win64)   echo chrome-headless-shell.exe ;;
        chrome-headless-shell:*)       echo chrome-headless-shell ;;
        chrome:win64)                  echo chrome.exe ;;
        chrome:mac-*)                  echo "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing" ;;
        chrome:*)                      echo chrome ;;
    esac
}

install() {
    local os="$1" root="$2" version platform
    version=$(tested_version)
    platform=$(platform_for "$os")
    if [ -z "$platform" ]; then
        echo "Chrome-for-Testing publishes no build for $os; the kyo-browser tests cancel there."
        return 0
    fi
    # shellcheck source=fetch-lib.sh
    . "$repo/scripts/fetch-lib.sh"
    mkdir -p "$root"

    # A restore under an older key carries other versions and leftover staging directories; only
    # this version's installs are kept, so the saved entry holds one version.
    local keep=() entry name artifact
    for artifact in "${artifacts[@]}"; do keep+=("$artifact-$version-$platform"); done
    for entry in "$root"/chrome-* "$root"/.chrome-*; do
        [ -e "$entry" ] || continue
        name=$(basename "$entry")
        case " ${keep[*]} " in
            *" $name "*) ;;
            *) echo "removing $name"; rm -rf "$entry" ;;
        esac
    done

    local dir staging zip url exe
    for artifact in "${artifacts[@]}"; do
        dir="$root/$artifact-$version-$platform"
        if [ -f "$dir/$marker" ]; then
            echo "$artifact $version ($platform) already installed"
            continue
        fi
        staging="$root/.$artifact-$version-$platform.staging-preload"
        rm -rf "$staging" "$dir"
        mkdir -p "$staging"
        zip="$staging.zip"
        url="https://storage.googleapis.com/chrome-for-testing-public/$version/$platform/$artifact-$platform.zip"
        echo "downloading $url"
        fetch_url "$url" "$zip"
        fetch_unzip "$zip" "$staging"
        rm -f "$zip"
        exe="$staging/$artifact-$platform/$(executable_for "$artifact" "$platform")"
        if [ ! -f "$exe" ]; then
            echo "$url extracted without $exe" >&2
            exit 1
        fi
        case "$platform" in win*) ;; *) chmod +x "$exe" ;; esac
        : > "$staging/$marker"
        mv "$staging" "$dir"
        echo "$artifact $version ($platform) installed at $dir"
    done
}

case "${1:-}" in
    version) tested_version ;;
    install) install "${2:?os pole}" "${3:?install root}" ;;
    *)
        echo "usage: $0 version | install <os> <root>" >&2
        exit 2
        ;;
esac
