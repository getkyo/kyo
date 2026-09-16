/*
** Non-variadic, trailing-out-parameter wrappers over the SQLite C API.
**
** kyo-ffi generates its bindings from a Scala trait, and five shapes in sqlite3.h do not fit what it
** can express. Each wrapper here exists for one of them and does nothing else; anything sqlite3.h
** already exposes in a bindable shape is bound directly rather than wrapped.
**
**   1. A HANDLE returned through an out-parameter. A multi-value return must be entirely primitive:
**      its first field becomes the C return and the rest become out-pointers, and a handle fits
**      neither. So sqlite3_open_v2's `sqlite3**` and sqlite3_prepare_v2's `sqlite3_stmt**` cannot be
**      out-parameters; the wrappers return the handle, and the result code is read back off the
**      connection, which is where SQLite records it anyway.
**
**   2. A BORROWED STRING that is not the whole return value. sqlite3_prepare_v2's tail is a
**      `const char**`, and a borrowed string is bindable only as a top-level return. The wrapper
**      consumes the tail itself and refuses a string holding more than one statement.
**
**   3. Variadic functions. sqlite3_db_config is `(sqlite3*, int op, ...)`, and the plugin binds fixed
**      arities only.
**
**   4. Sentinel values that are casts rather than data. SQLITE_TRANSIENT is
**      `((sqlite3_destructor_type)-1)`, a function pointer built by casting -1, which is not a value a
**      binding can pass as an argument.
**
**   5. Reads whose length cannot be recovered from the pointer, because SQLite text may contain NUL
**      bytes and a C-string read truncates at the first one.
*/

#include <string.h>

#include "sqlite3.h"

/*
** (1) The handle becomes the RETURN value, and the result code is left on the connection.
**
** A multi-value return has to be entirely primitive: its first field becomes the C return and the rest
** become out-pointers, and neither position accepts a handle. So the handle cannot travel beside a
** result code at all, and the code is recovered from the connection instead.
**
** That is sound rather than a workaround. SQLite allocates the handle before it can fail and records
** the failure on it: measured, opening an unwritable path answers rc 14, hands back a non-NULL handle,
** and sqlite3_extended_errcode on that handle also answers 14 with the message intact. A successful
** open leaves the code at 0.
**
** NULL comes back only when SQLite could not allocate the handle at all. Closing it is the caller's
** job either way.
*/
sqlite3 *kyo_sqlite3_open_v2(const char *filename, int flags, const char *zVfs) {
  sqlite3 *db = 0;
  /* An EMPTY zVfs means the default VFS, because a null one cannot get here: the binding marshals a
  ** String argument by reading its bytes, so passing null throws on the Scala side before the call.
  ** Absent is therefore spelled "" at this boundary, and translated back to NULL here. */
  sqlite3_open_v2(filename, &db, flags, (zVfs && zVfs[0]) ? zVfs : 0);
  return db;
}

/*
** (1b) Prepares exactly ONE statement, refusing a string that holds more.
**
** sqlite3_prepare_v2 compiles the first statement and leaves the rest in its tail out-parameter, so a
** caller that ignores the tail silently runs a prefix of what it was given. PostgreSQL and MySQL both
** refuse such a string at the wire, and matching them means refusing here.
**
** The refusal cannot travel back beside the statement handle, for the reason in (1). It is encoded in
** the pair instead, and the encoding is unambiguous because a successful prepare leaves the
** connection's error code at SQLITE_OK (measured):
**
**   non-NULL            one statement, compiled.
**   NULL, errcode OK    the string held a trailing statement, and nothing was compiled.
**   NULL, errcode set   an ordinary SQL error, readable with sqlite3_errmsg.
**
** Trailing whitespace and a trailing semicolon are not a statement: `SELECT 1;  ` leaves a two-space
** tail, which is accepted rather than refused.
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

/*
** (2) The int-valued sqlite3_db_config operations, fixed at two arguments.
**
** Every operation this driver needs takes one int and writes the resulting setting back through an
** int*. Passing NULL for that out-pointer is legal and is what the call sites want, since the value
** being set is already known.
*/
int kyo_sqlite3_db_config_int(sqlite3 *db, int op, int value) {
  return sqlite3_db_config(db, op, value, (int *)0);
}

/*
** (3) Binds that copy their argument.
**
** SQLITE_TRANSIENT tells SQLite to take its own copy before returning, which is the only correct
** choice here: the buffer belongs to the JVM, Native, or JS caller and may be moved or collected the
** moment the binding method returns. SQLITE_STATIC would hand SQLite a pointer it outlives.
*/
int kyo_sqlite3_bind_text_copy(sqlite3_stmt *stmt, int idx, const char *value, int nBytes) {
  return sqlite3_bind_text(stmt, idx, value, nBytes, SQLITE_TRANSIENT);
}

int kyo_sqlite3_bind_blob_copy(sqlite3_stmt *stmt, int idx, const void *value, int nBytes) {
  return sqlite3_bind_blob(stmt, idx, value, nBytes, SQLITE_TRANSIENT);
}

/*
** Reading a value OUT as bytes.
**
** sqlite3_column_text returns a NUL-terminated pointer, and reading it as a C string truncates at the
** first NUL. That is not hypothetical: SQLite stores and returns text containing NUL bytes, and
** length("a\0b") is 3 while strlen of the same pointer is 1. Every read therefore goes through
** sqlite3_column_bytes, which is the only length that is correct for both text and blobs.
**
** Returns the value's FULL length in bytes, which may exceed `cap`. The caller compares the return
** against `cap` to detect a short buffer rather than silently receiving a prefix; nothing is copied
** beyond `cap`. A NULL value has length 0 and copies nothing.
**
** The text and blob forms are separate because sqlite3_column_bytes reports the length of whatever
** representation was last requested, so the length has to be read through the same accessor that
** produced the pointer.
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
** The column's DECLARED type, with absent distinguished from empty.
**
** sqlite3_column_decltype returns NULL for any column not traceable to a declared one, which is every
** expression and every column declared with no type. The codec dispatches on this string, so "there
** is no declared type" and "the declared type is the empty string" must not arrive as the same
** answer, and a plain string return collapses them.
**
** Returns -1 when the column has no declared type, otherwise its length in bytes, following
** kyo_sqlite3_column_text_bytes for everything else: the full length is returned even when it exceeds
** `cap`, and nothing is copied past `cap`.
*/
int kyo_sqlite3_column_decltype_bytes(sqlite3_stmt *stmt, int iCol, void *dst, int cap) {
  const char *decl = sqlite3_column_decltype(stmt, iCol);
  size_t n;
  if (!decl) return -1;
  n = strlen(decl);
  if (dst && cap > 0) memcpy(dst, decl, n < (size_t)cap ? n : (size_t)cap);
  return (int)n;
}

/*
** sqlite3_exec without its callback, for the statements the driver issues for their effect only
** (the PRAGMAs, and the transaction control verbs).
**
** The error message is deliberately not returned: sqlite3_errmsg on the connection carries the same
** text and needs no freeing, whereas sqlite3_exec's out-parameter must be released with
** sqlite3_free, which is one more lifetime for a binding to get wrong.
*/
int kyo_sqlite3_exec_simple(sqlite3 *db, const char *sql) {
  return sqlite3_exec(db, sql, 0, 0, 0);
}

/*
** Applies the session settings the driver requires, in one call.
**
** These are per connection, not per database, and none of them is SQLite's default. They are applied
** together so a connection cannot be handed out half-configured, and the first failure is returned
** rather than the last, so a caller sees the setting that actually failed.
**
** journal_mode is deliberately NOT set here. It is persistent in the database file rather than per
** connection, and it can fail with SQLITE_BUSY against a live reader, so it belongs on the open path
** where that outcome can be retried.
*/
int kyo_sqlite3_configure_connection(sqlite3 *db, int busyTimeoutMillis) {
  int rc;

  /*
  ** Without this a quoted identifier that does not resolve becomes a string literal, so a renamed
  ** column yields rows carrying its own name as text instead of an error. The driver quotes every
  ** identifier it emits, which is what makes this reachable rather than theoretical.
  */
  rc = kyo_sqlite3_db_config_int(db, SQLITE_DBCONFIG_DQS_DML, 0);
  if (rc != SQLITE_OK) return rc;
  rc = kyo_sqlite3_db_config_int(db, SQLITE_DBCONFIG_DQS_DDL, 0);
  if (rc != SQLITE_OK) return rc;

  /* The constraint classes are only distinguishable through the extended codes. */
  rc = sqlite3_extended_result_codes(db, 1);
  if (rc != SQLITE_OK) return rc;

  /* Foreign keys are off by default, so without this the referential leaves pass by enforcing nothing. */
  rc = sqlite3_exec(db, "PRAGMA foreign_keys=ON", 0, 0, 0);
  if (rc != SQLITE_OK) return rc;

  /* A database file is untrusted input. */
  rc = sqlite3_exec(db, "PRAGMA trusted_schema=OFF", 0, 0, 0);
  if (rc != SQLITE_OK) return rc;

  /* SQLITE_BUSY is a routine outcome under concurrency, not a fault. */
  return sqlite3_busy_timeout(db, busyTimeoutMillis);
}
