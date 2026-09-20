/*
** Wrappers over the SQLite C API for the shapes kyo-ffi cannot bind: a handle returned through an
** out-parameter, a borrowed string that is not the whole return, a variadic function, a sentinel that
** is a cast rather than data, and a read whose length the pointer does not carry. Anything sqlite3.h
** exposes in a bindable shape is bound directly rather than wrapped.
*/

#include <string.h>

/*
** kyo-sql-doltlite compiles this same file with -DKYO_SQLITE_HEADER="doltlite.h" -DKYO_SQLITE_DOLTLITE
** against a SQLite fork exposing the same sqlite3_* API, so every wrapper below must stay within that
** shared API.
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
** One binary holds one embedded engine. kyo-sql-sqlite and kyo-sql-doltlite compile THIS file with the
** same kyo_sqlite3_* entry points, and Scala Native unpacks each jar's sources into its own directory, so
** a binary depending on both compiles both copies and the linker rejects the duplicates. That rejection is
** the whole guard, and the branch below can remove it: a delivered DoltLite compiles its copy to nothing,
** leaving one definition of each wrapper, and every doltlite:// call then binds to the plain-SQLite
** definitions in the executable rather than to the delivered library, opening a Dolt database with an
** engine that does not understand it. This definition is outside every branch so the duplicate survives
** whatever the gate says, and it is a strong definition rather than a tentative one so -fcommon cannot
** merge the two.
**
** The guard holds only because both artifacts compile THIS file. Giving kyo-sql-doltlite an entry file of
** its own would remove the collision; native/two-sqlite-engines-natives-plugin asserts it is still there.
*/
int kyo_sql_one_embedded_sqlite_engine_per_binary = 0;

/*
** kyo-sql-sqlite compiles SQLite's own source beside this file, so its engine is always on the link.
** kyo-sql-doltlite links a prebuilt engine instead, and on Scala Native this file compiles in whichever
** build links the binary, a consumer's included. Three states for that build:
**
**   KYO_FFI_LINKED_KYO_DOLTLITE    the DoltLite archive is on this link, so the wrappers compile.
**   KYO_FFI_EXTERNAL_KYO_DOLTLITE  the build links the prebuilt shim library the artifact carries, so
**                                  this file compiles to nothing and every entry point resolves there.
**   neither                        the stubs at the end compile, so the binary still links and DoltLite
**                                  reports the engine unavailable when a database is opened.
*/
#if defined(KYO_SQLITE_DOLTLITE) && defined(KYO_FFI_EXTERNAL_KYO_DOLTLITE)

/* Deliberately empty: the entry points come from the linked library. The definition above is what keeps
** this a valid translation unit. */

#else

#if defined(KYO_SQLITE_DOLTLITE) && !defined(KYO_FFI_LINKED_KYO_DOLTLITE)
#define KYO_SQLITE_ENGINE_STUBS
#endif

#if !defined(KYO_SQLITE_ENGINE_STUBS)

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

  return sqlite3_busy_timeout(db, busyTimeoutMillis);
}

#else

/*
** No engine on the link. These define every function the DoltLite binding reaches, through a wrapper
** or directly, so the link resolves. The version is 0, which no SQLite release reports, and DoltLite
** checks it before any other call, so the rest are never reached; each still answers failure rather
** than plausible data in case one is.
*/
KYO_SQLITE_API sqlite3 *kyo_sqlite3_open_v2(const char *filename, int flags, const char *zVfs) { return 0; }
KYO_SQLITE_API sqlite3_stmt *kyo_sqlite3_prepare_one(sqlite3 *db, const char *zSql, int nByte) { return 0; }
KYO_SQLITE_API int kyo_sqlite3_bind_text_copy(sqlite3_stmt *stmt, int idx, const char *value, int nBytes) { return SQLITE_ERROR; }
KYO_SQLITE_API int kyo_sqlite3_bind_blob_copy(sqlite3_stmt *stmt, int idx, const void *value, int nBytes) { return SQLITE_ERROR; }
KYO_SQLITE_API int kyo_sqlite3_column_text_bytes(sqlite3_stmt *stmt, int iCol, void *dst, int cap) { return 0; }
KYO_SQLITE_API int kyo_sqlite3_column_blob_bytes(sqlite3_stmt *stmt, int iCol, void *dst, int cap) { return 0; }
KYO_SQLITE_API int kyo_sqlite3_column_decltype_bytes(sqlite3_stmt *stmt, int iCol, void *dst, int cap) { return -1; }
KYO_SQLITE_API int kyo_sqlite3_exec_simple(sqlite3 *db, const char *sql) { return SQLITE_ERROR; }
KYO_SQLITE_API int kyo_sqlite3_configure_connection(sqlite3 *db, int busyTimeoutMillis) { return SQLITE_ERROR; }

int sqlite3_libversion_number(void) { return 0; }
int sqlite3_close_v2(sqlite3 *db) { return SQLITE_ERROR; }
int sqlite3_step(sqlite3_stmt *stmt) { return SQLITE_ERROR; }
int sqlite3_finalize(sqlite3_stmt *stmt) { return SQLITE_ERROR; }
int sqlite3_reset(sqlite3_stmt *stmt) { return SQLITE_ERROR; }
int sqlite3_clear_bindings(sqlite3_stmt *stmt) { return SQLITE_ERROR; }
int sqlite3_bind_parameter_count(sqlite3_stmt *stmt) { return 0; }
int sqlite3_bind_null(sqlite3_stmt *stmt, int idx) { return SQLITE_ERROR; }
int sqlite3_bind_int64(sqlite3_stmt *stmt, int idx, sqlite3_int64 value) { return SQLITE_ERROR; }
int sqlite3_bind_double(sqlite3_stmt *stmt, int idx, double value) { return SQLITE_ERROR; }
int sqlite3_column_count(sqlite3_stmt *stmt) { return 0; }
const char *sqlite3_column_name(sqlite3_stmt *stmt, int iCol) { return ""; }
int sqlite3_column_type(sqlite3_stmt *stmt, int iCol) { return SQLITE_NULL; }
sqlite3_int64 sqlite3_column_int64(sqlite3_stmt *stmt, int iCol) { return 0; }
double sqlite3_column_double(sqlite3_stmt *stmt, int iCol) { return 0; }
int sqlite3_extended_errcode(sqlite3 *db) { return SQLITE_ERROR; }
const char *sqlite3_errmsg(sqlite3 *db) { return "the DoltLite engine is not linked into this binary"; }
sqlite3_int64 sqlite3_changes64(sqlite3 *db) { return 0; }
sqlite3_int64 sqlite3_last_insert_rowid(sqlite3 *db) { return 0; }
void sqlite3_interrupt(sqlite3 *db) {}

#endif /* KYO_SQLITE_ENGINE_STUBS */

#endif /* KYO_FFI_EXTERNAL_KYO_DOLTLITE */
