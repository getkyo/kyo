/*
** Wrappers over the SQLite C API for the shapes kyo-ffi cannot bind: a handle returned through an
** out-parameter, a borrowed string that is not the whole return, a variadic function, a sentinel that
** is a cast rather than data, and a read whose length the pointer does not carry. Anything sqlite3.h
** exposes in a bindable shape is bound directly rather than wrapped.
*/

#include <string.h>

/*
** The header is a macro so a second engine exposing the same sqlite3_* API can compile this same
** file against its own header. Every wrapper below must therefore stay within that shared API.
*/
#ifndef KYO_SQLITE_HEADER
#define KYO_SQLITE_HEADER "sqlite3.h"
#endif
#include KYO_SQLITE_HEADER

/*
** The handle is the RETURN value and the result code is left on the connection, a handle not being
** able to travel beside a primitive in a multi-value return. SQLite allocates the handle before it
** can fail and records the failure on it, so sqlite3_extended_errcode on the returned handle carries
** the code. NULL means it could not be allocated at all, and closing it is the caller's job either way.
*/
sqlite3 *kyo_sqlite3_open_v2(const char *filename, int flags, const char *zVfs) {
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
sqlite3_stmt *kyo_sqlite3_prepare_one(sqlite3 *db, const char *zSql, int nByte) {
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
int kyo_sqlite3_db_config_int(sqlite3 *db, int op, int value) {
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
int kyo_sqlite3_bind_text_copy(sqlite3_stmt *stmt, int idx, const char *value, int nBytes) {
  return sqlite3_bind_text(stmt, idx, value ? value : "", nBytes, SQLITE_TRANSIENT);
}

int kyo_sqlite3_bind_blob_copy(sqlite3_stmt *stmt, int idx, const void *value, int nBytes) {
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
int kyo_sqlite3_column_text_bytes(sqlite3_stmt *stmt, int iCol, void *dst, int cap) {
  const void *src = (const void *)sqlite3_column_text(stmt, iCol);
  int n = sqlite3_column_bytes(stmt, iCol);
  if (src && dst && cap > 0) memcpy(dst, src, n < cap ? (size_t)n : (size_t)cap);
  return n;
}

int kyo_sqlite3_column_blob_bytes(sqlite3_stmt *stmt, int iCol, void *dst, int cap) {
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
int kyo_sqlite3_column_decltype_bytes(sqlite3_stmt *stmt, int iCol, void *dst, int cap) {
  const char *decl = sqlite3_column_decltype(stmt, iCol);
  size_t n;
  if (!decl) return -1;
  n = strlen(decl);
  if (dst && cap > 0) memcpy(dst, decl, n < (size_t)cap ? n : (size_t)cap);
  return (int)n;
}

/* sqlite3_exec without its callback. The error message is not returned: sqlite3_errmsg carries the
** same text and needs no freeing, where sqlite3_exec's out-parameter must be released. */
int kyo_sqlite3_exec_simple(sqlite3 *db, const char *sql) {
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
int kyo_sqlite3_configure_connection(sqlite3 *db, int busyTimeoutMillis) {
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
