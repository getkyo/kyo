package kyo.internal.doltlite

import kyo.*
import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.Maybe
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.internal.sqlite.SqliteBindings
import kyo.internal.sqlite.SqliteDb
import kyo.internal.sqlite.SqliteStmt

/** The FFI binding for DoltLite, a SQLite fork that replaces the storage engine and keeps the `sqlite3_*` API, so every
  * call here is the same call [[SqliteBindings]] makes into a different library.
  *
  * The declarations are copied from [[SqliteBindings]] rather than inherited: the generator implements only the methods
  * declared directly on a binding trait, so extending one yields an implementation with every inherited member
  * unimplemented. `DoltLiteBindingsShapeTest` compares the two traits method for method to catch drift.
  *
  * `nativeBundled` is true because the shim C is compiled into the Native binary, so there is no `libkyo_doltlite` for a
  * `-l` to find; the prebuilt `libdoltlite.a` arrives through the build's `linkFlags` as an absolute path instead. With
  * it false, Scala Native fails the link with "library 'kyo_doltlite' not found".
  *
  * After editing this trait run a full `sbt clean`, not `ffiClean`: the ffiGenerate cache is keyed on TASTy and silently
  * drops new methods otherwise.
  */
private[kyo] trait DoltLiteBindings extends SqliteBindings:

    /** Opens a connection, answering the handle alone. A multi-value return must be entirely primitive, so the result code is read from the
      * handle with [[extendedErrcode]]; [[Absent]] means SQLite could not allocate a handle. The handle is the caller's to close whether or
      * not the open succeeded.
      *
      * `vfs` is `""` for the default VFS, never `null`: marshalling a String reads its bytes, so a null one throws before the call is made.
      * The wrapper translates empty back to NULL.
      */
    @Ffi.blocking
    def openV2(filename: String, flags: Int, vfs: String)(using AllowUnsafe): Fiber.Unsafe[Maybe[Ffi.Handle[SqliteDb]], Any]

    /** Closes a connection. `close_v2` rather than `close`: it defers until outstanding statements finalize instead of refusing. */
    @Ffi.blocking
    def closeV2(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Fiber.Unsafe[Int, Any]

    /** Applies the per-connection settings the driver requires, none of which is SQLite's default. See `kyo_sqlite.c`. */
    @Ffi.blocking
    def configureConnection(db: Ffi.Handle[SqliteDb], busyTimeoutMillis: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]

    /** Runs statements for their effect only: the PRAGMAs and the transaction verbs. Errors are read from [[errmsg]]. */
    @Ffi.blocking
    def execSimple(db: Ffi.Handle[SqliteDb], sql: String)(using AllowUnsafe): Fiber.Unsafe[Int, Any]

    /** Compiles exactly one statement, refusing a string that holds more. Plain `sqlite3_prepare_v2` compiles the FIRST statement and
      * leaves the rest, which would silently run a prefix of what the caller gave.
      *
      * [[Absent]] carries two outcomes, told apart by [[extendedErrcode]] on the same connection, which a successful prepare leaves at
      * `SQLITE_OK`:
      *   - code `SQLITE_OK`: the string held a trailing statement and nothing was compiled.
      *   - code set: an ordinary SQL error, with the text on [[errmsg]].
      */
    @Ffi.blocking
    def prepareOne(db: Ffi.Handle[SqliteDb], sql: String, nByte: Int)(using AllowUnsafe): Fiber.Unsafe[Maybe[Ffi.Handle[SqliteStmt]], Any]

    /** Advances a statement. `SQLITE_ROW` (100) means a row is available, `SQLITE_DONE` (101) means there are none left. */
    @Ffi.blocking
    def step(stmt: Ffi.Handle[SqliteStmt])(using AllowUnsafe): Fiber.Unsafe[Int, Any]

    def finalizeStmt(stmt: Ffi.Handle[SqliteStmt])(using AllowUnsafe): Int
    def reset(stmt: Ffi.Handle[SqliteStmt])(using AllowUnsafe): Int
    def clearBindings(stmt: Ffi.Handle[SqliteStmt])(using AllowUnsafe): Int
    def bindParameterCount(stmt: Ffi.Handle[SqliteStmt])(using AllowUnsafe): Int

    // Parameter indices are ONE-based, unlike column indices, and binding the wrong slot reports success.
    // The driver converts once at its own boundary rather than at each call.
    def bindNull(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Int
    def bindInt64(stmt: Ffi.Handle[SqliteStmt], idx: Int, value: Long)(using AllowUnsafe): Int
    def bindDouble(stmt: Ffi.Handle[SqliteStmt], idx: Int, value: Double)(using AllowUnsafe): Int

    /** Binds text SQLite copies before returning. The buffer belongs to the caller's runtime and may move the moment this returns, so a
      * non-copying bind would hand SQLite a dangling pointer.
      */
    def bindTextCopy(stmt: Ffi.Handle[SqliteStmt], idx: Int, value: Buffer[Byte], nBytes: Int)(using AllowUnsafe): Int
    def bindBlobCopy(stmt: Ffi.Handle[SqliteStmt], idx: Int, value: Buffer[Byte], nBytes: Int)(using AllowUnsafe): Int

    def columnCount(stmt: Ffi.Handle[SqliteStmt])(using AllowUnsafe): Int
    def columnName(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Ffi.Borrowed[String]

    /** The value's storage class for THIS row: 1 integer, 2 float, 3 text, 4 blob, 5 null. Per value, not per column. */
    def columnType(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Int

    /** The column's DECLARED type into `dst`, answering -1 when it has none. Absent and empty are different facts: every expression column
      * answers -1, and the codec's dispatch depends on telling that from a column declared `""`.
      */
    def columnDecltypeBytes(stmt: Ffi.Handle[SqliteStmt], idx: Int, dst: Buffer[Byte], cap: Int)(using AllowUnsafe): Int

    def columnInt64(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Long
    def columnDouble(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Double

    /** Reads a value's bytes into `dst`, answering its FULL length so a short `dst` is detectable rather than silently truncating. The text
      * and blob forms stay separate because the length must be read through the same accessor that produced the pointer.
      */
    def columnTextBytes(stmt: Ffi.Handle[SqliteStmt], idx: Int, dst: Buffer[Byte], cap: Int)(using AllowUnsafe): Int
    def columnBlobBytes(stmt: Ffi.Handle[SqliteStmt], idx: Int, dst: Buffer[Byte], cap: Int)(using AllowUnsafe): Int

    /** The extended result code, the only thing distinguishing the constraint classes: a primary-key violation is 1555 and a unique index
      * violation 2067, where the primary code is 19 for both.
      */
    def extendedErrcode(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Int
    def errmsg(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Ffi.Borrowed[String]

    /** Rows MATCHED by the most recent statement, not rows actually modified. */
    def changes64(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Long

    /** The rowid of the last insert, or 0 for a WITHOUT ROWID table. Zero is not an error and not a rowid, so a caller reading a generated
      * key needs RETURNING rather than this.
      */
    def lastInsertRowid(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Long

    /** Interrupts whatever is running on `db`, from any thread. Must be serialised against close: interrupting a connection that may close
      * concurrently is undefined. Interrupting a write inside an explicit transaction rolls the WHOLE transaction back.
      */
    def interrupt(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Unit

    /** The linked library's version as an integer, `X*1000000 + Y*1000 + Z`. */
    def libversionNumber()(using AllowUnsafe): Int

end DoltLiteBindings

private[kyo] object DoltLiteBindings extends Ffi.Config(
        library = "kyo_doltlite",
        symbols = Map(
            "openV2"              -> "kyo_sqlite3_open_v2",
            "closeV2"             -> "sqlite3_close_v2",
            "configureConnection" -> "kyo_sqlite3_configure_connection",
            "execSimple"          -> "kyo_sqlite3_exec_simple",
            "prepareOne"          -> "kyo_sqlite3_prepare_one",
            "step"                -> "sqlite3_step",
            "finalizeStmt"        -> "sqlite3_finalize",
            "reset"               -> "sqlite3_reset",
            "clearBindings"       -> "sqlite3_clear_bindings",
            "bindParameterCount"  -> "sqlite3_bind_parameter_count",
            "bindNull"            -> "sqlite3_bind_null",
            "bindInt64"           -> "sqlite3_bind_int64",
            "bindDouble"          -> "sqlite3_bind_double",
            "bindTextCopy"        -> "kyo_sqlite3_bind_text_copy",
            "bindBlobCopy"        -> "kyo_sqlite3_bind_blob_copy",
            "columnCount"         -> "sqlite3_column_count",
            "columnName"          -> "sqlite3_column_name",
            "columnType"          -> "sqlite3_column_type",
            "columnDecltypeBytes" -> "kyo_sqlite3_column_decltype_bytes",
            "columnInt64"         -> "sqlite3_column_int64",
            "columnDouble"        -> "sqlite3_column_double",
            "columnTextBytes"     -> "kyo_sqlite3_column_text_bytes",
            "columnBlobBytes"     -> "kyo_sqlite3_column_blob_bytes",
            "extendedErrcode"     -> "sqlite3_extended_errcode",
            "errmsg"              -> "sqlite3_errmsg",
            "changes64"           -> "sqlite3_changes64",
            "lastInsertRowid"     -> "sqlite3_last_insert_rowid",
            "interrupt"           -> "sqlite3_interrupt",
            "libversionNumber"    -> "sqlite3_libversion_number"
        ),
        headers = Chunk("doltlite.h"),
        nativeBundled = true
    )
