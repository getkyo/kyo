/*
 * SQLite stub, ABI-compatible shims for a minimal subset of sqlite3 calls:
 * open/exec/close. The goal is to exercise the kyo-ffi binding path (String
 * params, opaque long handles as pointers, transient per-row callbacks inside
 * exec) without depending on libsqlite3 being installed.
 *
 * Real-world binding:
 *   - drop this stub
 *   - set ffiLinkLibs := Seq("sqlite3")
 *   - keep the Scala trait unchanged (symbols line up via symbolPrefix = "kyo_")
 *
 * Execution model:
 *   kyo_sqlite3_exec(db, sql, cb) invokes `cb(ncols, row_idx)` once per row.
 *   The stub "executes" any SQL by emitting a hard-coded single-row result
 *   (ncols=1, row_idx=0), just enough for Main.scala to assert the callback
 *   fires exactly once and receives the expected arguments.
 */

#include <stdint.h>
#include <string.h>

#define KYO_SQLITE_OK     0
#define KYO_SQLITE_ERROR  1

/* Monotonic counter standing in for sqlite3* allocations. */
static int64_t g_next_db = 1;

/* Open an in-memory database. Real signature is
 *   int sqlite3_open(const char *filename, sqlite3 **ppDb)
 * but we return the handle via the C return (as int64) and accept the path as
 * a C string. Accept any filename (including ":memory:"). */
int64_t kyo_sqlite3_open(const char *filename) {
    (void)filename;
    return g_next_db++;
}

int kyo_sqlite3_close(int64_t db) {
    (void)db;
    return KYO_SQLITE_OK;
}

/* Run `sql` against `db`. For every logical row, invoke `cb(ncols, row_idx)`.
 * Real sqlite3_exec is
 *   int sqlite3_exec(sqlite3*, const char*, int (*cb)(void*,int,char**,char**),
 *                    void*, char**)
 *, we simplify to `(ncols, row_idx)` so the binding stays in primitives.
 * For any non-null `sql`, emit exactly one row (ncols=1, row_idx=0). Returns
 * OK regardless. */
int kyo_sqlite3_exec(int64_t db, const char *sql, int (*cb)(int ncols, int row_idx)) {
    (void)db;
    if (sql == NULL) return KYO_SQLITE_ERROR;

    /* Single-row result, exercise the callback-per-row pattern. */
    int rc = cb(1, 0);
    if (rc != 0) return KYO_SQLITE_ERROR;
    return KYO_SQLITE_OK;
}
