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
. "$(cd "$module/.." && pwd)/scripts/fetch-lib.sh"
staged="$module/build/sqlite/staged"
work="$module/build/sqlite/work"
stamp="$staged/.stamp"
pin="$SQLITE_VERSION $SQLITE_SHA256"

# The stamp is written last, so its presence means a complete tree, and it names the pin, so a
# version bump rebuilds rather than accepting whatever tree it finds.
if [[ -f "$stamp" && "$(cat "$stamp")" == "$pin" ]]; then
    echo "[kyo-sql-sqlite] SQLite $SQLITE_VERSION already staged at $staged"
    exit 0
fi

rm -rf "$staged"
mkdir -p "$work" "$staged"
zip="$work/$SQLITE_DIST.zip"
url="https://sqlite.org/$SQLITE_YEAR/$SQLITE_DIST.zip"

echo "[kyo-sql-sqlite] fetching $url"
fetch_url "$url" "$zip"
fetch_verify_sha256 "$zip" "$SQLITE_SHA256" "$SQLITE_DIST.zip"
fetch_unzip "$zip" "$work"
cp "$work/$SQLITE_DIST/sqlite3.c" "$staged/sqlite3.c"
cp "$work/$SQLITE_DIST/sqlite3.h" "$staged/sqlite3.h"
rm -rf "$work/$SQLITE_DIST" "$zip"
printf '%s\n' "$pin" > "$stamp"

echo "[kyo-sql-sqlite] staged SQLite $SQLITE_VERSION at $staged"
