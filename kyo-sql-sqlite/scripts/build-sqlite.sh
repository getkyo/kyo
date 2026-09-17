#!/usr/bin/env bash
# Fetch SQLite's C source at the pinned version and stage it for kyo-sql-sqlite.
# Run once before the kyo-sql-sqlite compile step; the staged tree is a build artifact (gitignored),
# consumed by the kyo_sqlite FfiLibrary, which compiles it together with this module's own shim.
#
# Usage: kyo-sql-sqlite/scripts/build-sqlite.sh
#
# The version is pinned because several conformance answers depend on it (RETURNING since 3.35.0, the
# RIGHT/FULL JOIN floor at 3.39.0), so a build fetching a different release would move what the battery
# asserts. The checksum is what makes the pin mean the bytes rather than just the name.
#
# Requires curl, plus any one of shasum/sha256sum/openssl for the checksum and any one of
# unzip/bsdtar/7z to unpack. The alternatives are there for the Windows runners, whose Git Bash
# carries neither unzip nor shasum reliably; every other platform has the first of each.
set -euo pipefail

SQLITE_VERSION="3.53.4"
# sqlite.org's release directory is the year, not the version.
SQLITE_YEAR="2026"
SQLITE_DIST="sqlite-amalgamation-3530400"
# sha256 of the distribution zip. Update both together, never one alone.
SQLITE_SHA256="1e71ddf93849c6a6ecf58b827c0692073d2dd7ee40196158068f7b29f422e87d"

here="$(cd "$(dirname "$0")" && pwd)"
module="$(cd "$here/.." && pwd)"
staged="$module/build/sqlite/staged"
work="$module/build/sqlite/work"

if [[ -f "$staged/sqlite3.c" && -f "$staged/sqlite3.h" ]]; then
    echo "[kyo-sql-sqlite] SQLite $SQLITE_VERSION already staged at $staged"
    exit 0
fi

mkdir -p "$work" "$staged"
zip="$work/$SQLITE_DIST.zip"
url="https://sqlite.org/$SQLITE_YEAR/$SQLITE_DIST.zip"

echo "[kyo-sql-sqlite] fetching $url"
curl -fsSL --retry 3 -o "$zip" "$url"

# Whichever of the three this platform has. Checked rather than assumed: a missing tool would
# otherwise make `actual` empty, which compares unequal and reports a checksum mismatch, sending a
# reader after a corrupt download that never happened.
if command -v shasum >/dev/null 2>&1; then
    actual="$(shasum -a 256 "$zip" | awk '{print $1}')"
elif command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$zip" | awk '{print $1}')"
elif command -v openssl >/dev/null 2>&1; then
    actual="$(openssl dgst -sha256 "$zip" | awk '{print $NF}')"
else
    echo "[kyo-sql-sqlite] no sha256 tool found (tried shasum, sha256sum, openssl)." >&2
    echo "Install one, or stage $staged by hand from $url." >&2
    exit 1
fi
if [[ "$actual" != "$SQLITE_SHA256" ]]; then
    echo "[kyo-sql-sqlite] checksum mismatch for $SQLITE_DIST.zip" >&2
    echo "  expected $SQLITE_SHA256" >&2
    echo "  actual   $actual" >&2
    echo "Refusing to stage. If the version was bumped, update SQLITE_SHA256 in this script together with it." >&2
    exit 1
fi

# bsdtar (the `tar` Windows 10+ and macOS ship) extracts zip, which is what makes the Windows
# runners work without unzip. 7z is the last resort, preinstalled on GitHub's Windows images.
if command -v unzip >/dev/null 2>&1; then
    unzip -o -q "$zip" -d "$work"
elif tar --version 2>/dev/null | grep -qi bsdtar; then
    (cd "$work" && tar -xf "$zip")
elif command -v 7z >/dev/null 2>&1; then
    7z x -y -o"$work" "$zip" >/dev/null
else
    echo "[kyo-sql-sqlite] no zip extractor found (tried unzip, bsdtar, 7z)." >&2
    exit 1
fi
cp "$work/$SQLITE_DIST/sqlite3.c" "$staged/sqlite3.c"
cp "$work/$SQLITE_DIST/sqlite3.h" "$staged/sqlite3.h"
rm -rf "$work/$SQLITE_DIST" "$zip"

echo "[kyo-sql-sqlite] staged SQLite $SQLITE_VERSION at $staged"
