/*
** Wrappers over the SQLite C API for the shapes kyo-ffi cannot bind: a handle returned through an
** out-parameter, a borrowed string that is not the whole return, a variadic function, a sentinel that
** is a cast rather than data, and a read whose length the pointer does not carry. Anything sqlite3.h
** exposes in a bindable shape is bound directly rather than wrapped.
*/

#include <string.h>

/*
** kyo-sql-doltlite compiles this same file with -DKYO_SQLITE_HEADER="doltlite.h" against a SQLite
** fork exposing the same sqlite3_* API, so every wrapper below must stay within that shared API.
*/
#ifndef KYO_SQLITE_HEADER
#define KYO_SQLITE_HEADER "sqlite3.h"
#endif

/* MSVC exports only what is declared, and it is what compiles this library on windows-arm64: an
** undeclared symbol leaves the DLL loadable but empty, and koffi then reports that it cannot find
** the function. MinGW's ld auto-exports and needs no attribute. Every entry point below carries
** KYO_SQLITE_API, as kyo_net_api.h and kyo_aeron.h do for the same reason.
**
** sqlite3.c's own entry points are covered by -DSQLITE_API, set on Windows in build.sbt: the
** bindings call most sqlite3_* symbols directly rather than through a wrapper here.
*/
#if defined(_WIN32)
#define KYO_SQLITE_API __declspec(dllexport)
#else
#define KYO_SQLITE_API
#endif
#include KYO_SQLITE_HEADER

/*
** The handle is the RETURN value and the result code is left on the connection, a handle not being
** able to travel beside a primitive in a multi-value return. SQLite allocates the handle before it
** can fail and records the failure on it, so sqlite3_extended_errcode on the returned handle carries
** the code. NULL means it could not be allocated at all, and closing it is the caller's job either way.
*/
KYO_SQLITE_API sqlite3 *kyo_sqlite3_open_v2(const char *filename, int flags, const char *zVfs) {
  sqlite3 *db = 0;
  /* An EMPTY zVfs means the default VFS. A String argument is marshalled by reading its bytes, so a
  ** null one throws on the Scala side before the call, and absent is spelled "" at this boundary. */
  sqlite3_open_v2(filename, &db, flags, (zVfs && zVfs[0]) ? zVfs : 0);
  return db;
}

/*
** Prepares exactly ONE statement, refusing a string that holds more. sqlite3_prepare_v2 otherwise
** compiles the first and leaves the rest in its tail, so a caller ignoring the tail silently runs a
** prefix of what it was given; both other engines refuse such a string at the wire.
**
** The refusal cannot travel beside the statement handle, so it is encoded in the pair, unambiguously
** because a successful prepare leaves the connection's error code at SQLITE_OK:
**
**   non-NULL            one statement, compiled.
**   NULL, errcode OK    the string held a trailing statement, and nothing was compiled.
**   NULL, errcode set   an ordinary SQL error, readable with sqlite3_errmsg.
**
** Trailing whitespace and a trailing semicolon are not a statement.
*/
KYO_SQLITE_API sqlite3_stmt *kyo_sqlite3_prepare_one(sqlite3 *db, const char *zSql, int nByte) {
  sqlite3_stmt *stmt = 0;
  const char *tail = 0;
  int rc = sqlite3_prepare_v2(db, zSql, nByte, &stmt, &tail);
  if (rc != SQLITE_OK) return 0;
  if (tail) {
    while (*tail == ' ' || *tail == '\t' || *tail == '\n' || *tail == '\r' || *tail == ';') tail++;
    if (*tail != '\0') {
      sqlite3_finalize(stmt);
      return 0;
    }
  }
  return stmt;
}

/* The int-valued sqlite3_db_config operations, fixed at two arguments. NULL for the out-pointer is
** legal, and is what the call sites want since the value being set is already known. */
KYO_SQLITE_API int kyo_sqlite3_db_config_int(sqlite3 *db, int op, int value) {
  return sqlite3_db_config(db, op, value, (int *)0);
}

/*
** Binds that copy. SQLITE_TRANSIENT makes SQLite take its own copy before returning: the buffer
** belongs to the JVM, Native, or JS caller and may move or be collected the moment the binding method
** returns, so SQLITE_STATIC would hand SQLite a pointer it outlives.
**
** The NULL check is the empty-value contract. sqlite3_bind_text binds SQL NULL whenever its pointer
** is NULL whatever the length says, and a zero-length buffer arrives as a NULL pointer on the
** koffi/Node transport where the JVM passes a valid pointer to a zero-length one. A genuine NULL
** never reaches here, the driver calling sqlite3_bind_null for that, so NULL can only mean empty.
*/
KYO_SQLITE_API int kyo_sqlite3_bind_text_copy(sqlite3_stmt *stmt, int idx, const char *value, int nBytes) {
  return sqlite3_bind_text(stmt, idx, value ? value : "", nBytes, SQLITE_TRANSIENT);
}

KYO_SQLITE_API int kyo_sqlite3_bind_blob_copy(sqlite3_stmt *stmt, int idx, const void *value, int nBytes) {
  return sqlite3_bind_blob(stmt, idx, value ? value : "", nBytes, SQLITE_TRANSIENT);
}

/*
** Reads a value's bytes. SQLite stores text containing NUL bytes and sqlite3_column_text returns a
** NUL-terminated pointer, so a C-string read truncates: length("a\0b") is 3 where strlen is 1.
**
** Returns the value's FULL length in bytes, which may exceed `cap`; nothing is copied beyond `cap`,
** so a short buffer is detectable rather than silently a prefix. A NULL value has length 0.
**
** The text and blob forms are separate because sqlite3_column_bytes reports the length of whatever
** representation was last requested, so it must be read through the accessor that produced the
** pointer.
*/
KYO_SQLITE_API int kyo_sqlite3_column_text_bytes(sqlite3_stmt *stmt, int iCol, void *dst, int cap) {
  const void *src = (const void *)sqlite3_column_text(stmt, iCol);
  int n = sqlite3_column_bytes(stmt, iCol);
  if (src && dst && cap > 0) memcpy(dst, src, n < cap ? (size_t)n : (size_t)cap);
  return n;
}

KYO_SQLITE_API int kyo_sqlite3_column_blob_bytes(sqlite3_stmt *stmt, int iCol, void *dst, int cap) {
  const void *src = sqlite3_column_blob(stmt, iCol);
  int n = sqlite3_column_bytes(stmt, iCol);
  if (src && dst && cap > 0) memcpy(dst, src, n < cap ? (size_t)n : (size_t)cap);
  return n;
}

/*
** The column's DECLARED type, with absent distinguished from empty. sqlite3_column_decltype returns
** NULL for every expression and every column declared with no type, and the codec dispatches on this
** string, so a plain string return would collapse the two. Answers -1 for no declared type, otherwise
** the length in bytes, following kyo_sqlite3_column_text_bytes for `cap`.
*/
KYO_SQLITE_API int kyo_sqlite3_column_decltype_bytes(sqlite3_stmt *stmt, int iCol, void *dst, int cap) {
  const char *decl = sqlite3_column_decltype(stmt, iCol);
  size_t n;
  if (!decl) return -1;
  n = strlen(decl);
  if (dst && cap > 0) memcpy(dst, decl, n < (size_t)cap ? n : (size_t)cap);
  return (int)n;
}

/* sqlite3_exec without its callback. The error message is not returned: sqlite3_errmsg carries the
** same text and needs no freeing, where sqlite3_exec's out-parameter must be released. */
KYO_SQLITE_API int kyo_sqlite3_exec_simple(sqlite3 *db, const char *sql) {
  return sqlite3_exec(db, sql, 0, 0, 0);
}

/*
** How a connection spends a lock wait. sqlite3_busy_timeout sleeps on the thread that made the call,
** and on Node that thread is one of the four libuv workers every blocking call in the process shares:
** four statements waiting on a lock stall every other SQLite call, including the COMMIT of the
** connection holding that lock, which then cannot release it until their timeouts expire.
**
** In DEFER mode the handler records that SQLite asked to wait and declines, so the call returns
** SQLITE_BUSY at once and the driver waits on its fiber before retrying. SQLite calls the handler only
** where waiting can help, never for a deadlock or a stale WAL snapshot, so the record is what tells a
** retryable busy from a final one. WAIT mode is SQLite's own schedule, for a call that cannot be
** retried: a step after its statement produced a row would restart the statement.
**
** The state hangs off the connection as client data, so SQLite frees it as the connection closes.
*/
typedef struct {
  int timeoutMillis;
  int defer;
  int deferred;
} kyo_busy_state;

static const char kyo_busy_key[] = "kyo_busy";

static kyo_busy_state *kyo_busy(sqlite3 *db) {
  return (kyo_busy_state *)sqlite3_get_clientdata(db, kyo_busy_key);
}

/* WAIT mode follows sqliteDefaultBusyCallback's table, so it spends the timeout as
** sqlite3_busy_timeout would. The driver's retry in DEFER mode follows the same table. */
static int kyo_busy_handler(void *arg, int count) {
  static const int delays[] = {1, 2, 5, 10, 15, 20, 25, 25, 25, 50, 50, 100};
  static const int totals[] = {0, 1, 3, 8, 18, 33, 53, 78, 103, 128, 178, 228};
  enum { NDELAY = sizeof(delays) / sizeof(delays[0]) };
  kyo_busy_state *s = (kyo_busy_state *)arg;
  int delay, prior;
  if (s->defer) {
    s->deferred = 1;
    return 0;
  }
  if (count < NDELAY) {
    delay = delays[count];
    prior = totals[count];
  } else {
    delay = delays[NDELAY - 1];
    prior = totals[NDELAY - 1] + delay * (count - (NDELAY - 1));
  }
  if (prior + delay > s->timeoutMillis) {
    delay = s->timeoutMillis - prior;
    if (delay <= 0) return 0;
  }
  sqlite3_sleep(delay);
  return 1;
}

/* Sets how the connection's next calls spend a lock wait, and clears the record of a declined one. */
KYO_SQLITE_API void kyo_sqlite3_busy_defer(sqlite3 *db, int defer) {
  kyo_busy_state *s = kyo_busy(db);
  if (s) {
    s->defer = defer;
    s->deferred = 0;
  }
}

/* 1 when the handler declined a wait SQLite asked for since the last kyo_sqlite3_busy_defer. */
KYO_SQLITE_API int kyo_sqlite3_busy_deferred(sqlite3 *db) {
  kyo_busy_state *s = kyo_busy(db);
  return s ? s->deferred : 0;
}

/*
** The per-connection settings the driver requires, none of them SQLite's default. Applied together so
** a connection cannot be handed out half-configured, returning the FIRST failure so a caller sees the
** setting that actually failed.
**
** journal_mode is deliberately NOT set here: it is persistent in the database file rather than per
** connection and can fail with SQLITE_BUSY against a live reader, so it belongs on the open path
** where that outcome can be retried.
*/
KYO_SQLITE_API int kyo_sqlite3_configure_connection(sqlite3 *db, int busyTimeoutMillis) {
  int rc;
  kyo_busy_state *busy;

  /* Without this a quoted identifier that does not resolve becomes a string literal, so a renamed
  ** column yields rows carrying its own name as text instead of an error. */
  rc = kyo_sqlite3_db_config_int(db, SQLITE_DBCONFIG_DQS_DML, 0);
  if (rc != SQLITE_OK) return rc;
  rc = kyo_sqlite3_db_config_int(db, SQLITE_DBCONFIG_DQS_DDL, 0);
  if (rc != SQLITE_OK) return rc;

  /* The constraint classes are only distinguishable through the extended codes. */
  rc = sqlite3_extended_result_codes(db, 1);
  if (rc != SQLITE_OK) return rc;

  /* Foreign keys are off by default. */
  rc = sqlite3_exec(db, "PRAGMA foreign_keys=ON", 0, 0, 0);
  if (rc != SQLITE_OK) return rc;

  /* A database file is untrusted input. */
  rc = sqlite3_exec(db, "PRAGMA trusted_schema=OFF", 0, 0, 0);
  if (rc != SQLITE_OK) return rc;

  busy = (kyo_busy_state *)sqlite3_malloc(sizeof *busy);
  if (!busy) return SQLITE_NOMEM;
  busy->timeoutMillis = busyTimeoutMillis;
  busy->defer = 0;
  busy->deferred = 0;
  /* On failure SQLite has already run the destructor on the block. */
  rc = sqlite3_set_clientdata(db, kyo_busy_key, busy, sqlite3_free);
  if (rc != SQLITE_OK) return rc;
  return sqlite3_busy_handler(db, kyo_busy_handler, busy);
}
