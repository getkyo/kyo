#!/usr/bin/env bash
# Fetch SQLite's C source at the pinned version and stage it for kyo-sql-sqlite.
# Run once before the kyo-sql-sqlite compile step; the staged tree is a build artifact (gitignored),
# consumed by the kyo_sqlite FfiLibrary, which compiles it together with this module's own shim.
#
# Usage: kyo-sql-sqlite/scripts/build-sqlite.sh
#
# Unlike kyo-aeron and kyo-net, nothing is COMPILED here and nothing is staged per os-arch. SQLite ships
# as one architecture-independent .c plus its header, and the FfiLibrary already compiles per platform,
# so staging is a download and an unzip. The per-arch step other modules need is the build, and there is
# none to do.
#
# The version is pinned rather than tracking latest, and this is not a style choice: several conformance
# answers depend on it (RETURNING since 3.35.0, the RIGHT/FULL JOIN floor at 3.39.0), so a build that
# quietly fetched a different release would move what the battery asserts. The checksum is what makes the
# pin mean the bytes rather than just the name.
#
# Requires curl + unzip + shasum on PATH.
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

actual="$(shasum -a 256 "$zip" | awk '{print $1}')"
if [[ "$actual" != "$SQLITE_SHA256" ]]; then
    echo "[kyo-sql-sqlite] checksum mismatch for $SQLITE_DIST.zip" >&2
    echo "  expected $SQLITE_SHA256" >&2
    echo "  actual   $actual" >&2
    echo "Refusing to stage. If the version was bumped, update SQLITE_SHA256 in this script together with it." >&2
    exit 1
fi

unzip -o -q "$zip" -d "$work"
cp "$work/$SQLITE_DIST/sqlite3.c" "$staged/sqlite3.c"
cp "$work/$SQLITE_DIST/sqlite3.h" "$staged/sqlite3.h"
rm -rf "$work/$SQLITE_DIST" "$zip"

echo "[kyo-sql-sqlite] staged SQLite $SQLITE_VERSION at $staged"
