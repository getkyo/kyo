package kyo.internal.sqlite
import kyo.*
import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.Maybe
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.internal.sqlite.SqliteBindings

/** The vendored SQLite engine, bound.
  *
  * The declarations are COPIED from [[SqliteBindings]] rather than inherited: the generator reads the methods declared
  * DIRECTLY on a trait, so one that merely extends another leaves every inherited member unimplemented.
  * `DoltLiteBindings` carries the same copy, and both are compared against the parent trait by their tests.
  *
  * After editing this trait run a full `sbt clean`, not `ffiClean`: the ffiGenerate cache is keyed on TASTy and
  * silently drops new methods otherwise.
  */
private[kyo] trait VendoredSqliteBindings extends SqliteBindings:

    /** Opens a connection, answering the handle alone: the result code is read from it with [[extendedErrcode]], SQLite having allocated
      * the handle before it could fail and recorded the failure there. [[Absent]] means it could not allocate at all. The handle is the
      * caller's to close whether or not the open succeeded, and `vfs` is `""` for the default rather than `null`, a String argument being
      * marshalled by reading its bytes.
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

    /** Compiles exactly one statement, refusing a string that holds more, of which sqlite3_prepare_v2 would otherwise compile a prefix.
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

    /** Binds text SQLite copies before returning: the caller's buffer may move the moment this returns, so a non-copying bind would hand
      * SQLite a pointer it outlives.
      */
    def bindTextCopy(stmt: Ffi.Handle[SqliteStmt], idx: Int, value: Buffer[Byte], nBytes: Int)(using AllowUnsafe): Int
    def bindBlobCopy(stmt: Ffi.Handle[SqliteStmt], idx: Int, value: Buffer[Byte], nBytes: Int)(using AllowUnsafe): Int

    def columnCount(stmt: Ffi.Handle[SqliteStmt])(using AllowUnsafe): Int
    def columnName(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Ffi.Borrowed[String]

    /** The value's storage class for THIS row: 1 integer, 2 float, 3 text, 4 blob, 5 null. Per value, not per column. */
    def columnType(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Int

    /** The column's DECLARED type into `dst`, answering -1 when it has none. Every expression column answers -1, and the codec's dispatch
      * depends on telling that from a column declared `""`.
      */
    def columnDecltypeBytes(stmt: Ffi.Handle[SqliteStmt], idx: Int, dst: Buffer[Byte], cap: Int)(using AllowUnsafe): Int

    def columnInt64(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Long
    def columnDouble(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Double

    /** Reads a value's bytes into `dst`, answering its FULL length so a short `dst` is detectable rather than silently truncating. The text
      * and blob forms are separate because the length must be read through the same accessor that produced the pointer.
      */
    def columnTextBytes(stmt: Ffi.Handle[SqliteStmt], idx: Int, dst: Buffer[Byte], cap: Int)(using AllowUnsafe): Int
    def columnBlobBytes(stmt: Ffi.Handle[SqliteStmt], idx: Int, dst: Buffer[Byte], cap: Int)(using AllowUnsafe): Int

    /** The extended result code, the only thing distinguishing the constraint classes: a primary-key violation is 1555 and a unique index
      * violation 2067, where the primary code is 19 for both.
      */
    def extendedErrcode(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Int
    def errmsg(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Ffi.Borrowed[String]

    /** Rows changed by the most recent statement. SQLite counts MATCHED rows, as PostgreSQL does natively and MySQL does only under
      * CLIENT_FOUND_ROWS.
      */
    def changes64(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Long

    /** The rowid of the last insert, or 0 for a WITHOUT ROWID table. Zero is not a rowid and not an error, so reading a generated key needs
      * RETURNING instead.
      */
    def lastInsertRowid(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Long

    /** Interrupts whatever is running on `db`, from any thread; an FFI call cannot be cancelled mid-flight, so this is the only way to stop
      * a running step. Must be serialised against close, interrupting a connection that may close concurrently being undefined. Interrupting
      * a write inside an explicit transaction rolls the WHOLE transaction back, which the other two engines do not do.
      */
    def interrupt(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Unit

    /** The vendored library's version as an integer, `X*1000000 + Y*1000 + Z`. */
    def libversionNumber()(using AllowUnsafe): Int

end VendoredSqliteBindings

private[kyo] object VendoredSqliteBindings extends Ffi.Config(
        library = "kyo_sqlite",
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
        headers = Chunk("sqlite3.h"),
        // SQLite's C source is compiled into the Native binary, so the generated binding must not emit
        // @link("kyo_sqlite"): that sends the linker after a dynamic library that does not exist.
        nativeBundled = true
    )
