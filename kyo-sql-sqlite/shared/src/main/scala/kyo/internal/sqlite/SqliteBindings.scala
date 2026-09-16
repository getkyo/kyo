package kyo.internal.sqlite

import kyo.*
import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.Maybe
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** FFI binding trait for the vendored SQLite C API.
  *
  * Most methods bind a `sqlite3_*` symbol directly. The rest bind a `kyo_sqlite3_*` wrapper from `kyo_sqlite.c`, which exists only where
  * sqlite3.h has a shape this generator cannot express: an out-parameter that is not last, a variadic function, a sentinel that is a cast
  * rather than a value, or a read whose length cannot be recovered from a C string. Each wrapper's own comment says which.
  *
  * Every symbol is named explicitly in [[SqliteBindings.symbols]] rather than derived. Derivation would have to guess whether `columnInt64`
  * is `column_int64` or `column_int_64`, and a wrong guess fails at library load with a message about a missing symbol rather than at the
  * call that wanted it.
  *
  * `@Ffi.blocking` marks everything that can touch the file or wait on a lock. `step` is the obvious one; `prepareOne` reads the schema,
  * `execSimple` runs arbitrary statements, and `openV2` and `configureConnection` both do file I/O. The accessors are left plain: they read
  * memory the step already produced.
  *
  * Reads return a length rather than a string. SQLite text may contain NUL bytes, so a C-string read truncates: measured,
  * `length('a' || char(0) || 'b')` is 3 where `strlen` of the same pointer is 1.
  *
  * After editing this trait run a full `sbt clean`, not `ffiClean`: the ffiGenerate cache is keyed on TASTy and silently drops new methods
  * otherwise. This is the same trap `AeronBindings` documents.
  */
private[sqlite] trait SqliteBindings extends Ffi:

    /** Opens a connection, answering the handle alone.
      *
      * The result code does NOT come back with it: a multi-value return must be entirely primitive, so a handle cannot travel beside one.
      * It is read from the connection with [[extendedErrcode]] instead, which is sound because SQLite allocates the handle before it can
      * fail and records the failure on it. [[Absent]] means SQLite could not allocate at all.
      *
      * The handle is the caller's to close whether or not the open succeeded.
      *
      * `vfs` is `""` for the default VFS, never `null`: a String argument is marshalled by reading its bytes, so a null one throws before
      * the call is made. The wrapper translates empty back to NULL.
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

    /** Compiles exactly one statement, refusing a string that holds more.
      *
      * [[Absent]] carries two different outcomes, told apart by [[extendedErrcode]] on the same connection, which a successful prepare
      * leaves at `SQLITE_OK`:
      *   - code `SQLITE_OK`: the string held a trailing statement and nothing was compiled.
      *   - code set: an ordinary SQL error, with the text on [[errmsg]].
      *
      * Refusing matters because sqlite3_prepare_v2 otherwise compiles the FIRST statement and leaves the rest, so a caller that ignored the
      * tail would silently run a prefix of what it was given. Both other engines refuse such a string at the wire.
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

    // Parameter indices are ONE-based, unlike column indices. Mixing them up binds the wrong slot and
    // reports success, so the driver converts once at its own boundary rather than at each call.
    def bindNull(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Int
    def bindInt64(stmt: Ffi.Handle[SqliteStmt], idx: Int, value: Long)(using AllowUnsafe): Int
    def bindDouble(stmt: Ffi.Handle[SqliteStmt], idx: Int, value: Double)(using AllowUnsafe): Int

    /** Binds text SQLite copies before returning. The buffer belongs to the caller's runtime and may move the moment this returns, so a
      * non-copying bind would hand SQLite a pointer it outlives.
      */
    def bindTextCopy(stmt: Ffi.Handle[SqliteStmt], idx: Int, value: Buffer[Byte], nBytes: Int)(using AllowUnsafe): Int
    def bindBlobCopy(stmt: Ffi.Handle[SqliteStmt], idx: Int, value: Buffer[Byte], nBytes: Int)(using AllowUnsafe): Int

    def columnCount(stmt: Ffi.Handle[SqliteStmt])(using AllowUnsafe): Int
    def columnName(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Ffi.Borrowed[String]

    /** The value's storage class for THIS row: 1 integer, 2 float, 3 text, 4 blob, 5 null. Per value, not per column. */
    def columnType(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Int

    /** The column's DECLARED type into `dst`, answering -1 when it has none. Absent and empty are different facts here: every expression
      * column answers -1, and the codec's dispatch depends on telling that from a column declared `""`.
      */
    def columnDecltypeBytes(stmt: Ffi.Handle[SqliteStmt], idx: Int, dst: Buffer[Byte], cap: Int)(using AllowUnsafe): Int

    def columnInt64(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Long
    def columnDouble(stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Double

    /** Reads a value's bytes into `dst`, answering its FULL length so a short `dst` is detectable rather than silently truncating. The text
      * and blob forms are separate because the length must be read through the same accessor that produced the pointer.
      */
    def columnTextBytes(stmt: Ffi.Handle[SqliteStmt], idx: Int, dst: Buffer[Byte], cap: Int)(using AllowUnsafe): Int
    def columnBlobBytes(stmt: Ffi.Handle[SqliteStmt], idx: Int, dst: Buffer[Byte], cap: Int)(using AllowUnsafe): Int

    /** The extended result code, which is the only thing distinguishing the constraint classes: a primary-key violation is 1555 and a unique
      * index violation 2067, where the primary code is 19 for both.
      */
    def extendedErrcode(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Int
    def errmsg(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Ffi.Borrowed[String]

    /** Rows changed by the most recent statement. SQLite counts MATCHED rows, which is the semantics PostgreSQL has natively and MySQL has
      * to be asked for with CLIENT_FOUND_ROWS.
      */
    def changes64(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Long

    /** The rowid of the last insert, or 0 for a WITHOUT ROWID table. Zero is not an error and not a rowid, so a caller reading a generated
      * key needs RETURNING rather than this.
      */
    def lastInsertRowid(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Long

    /** Interrupts whatever is running on `db`, from any thread. An FFI call cannot be cancelled mid-flight, so this is the only way to stop
      * a running step. Must be serialised against close: interrupting a connection that may close concurrently is undefined.
      *
      * Interrupting a write inside an explicit transaction rolls the WHOLE transaction back, which the other two engines do not do.
      */
    def interrupt(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Unit

    /** The vendored library's version as an integer, `X*1000000 + Y*1000 + Z`. Read at runtime rather than assumed, so a build that falls
      * back to a host library reports what it actually linked.
      */
    def libversionNumber()(using AllowUnsafe): Int

end SqliteBindings

private[sqlite] object SqliteBindings extends Ffi.Config(
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

/** Tag for a `sqlite3 *`. */
final private[sqlite] class SqliteDb

/** Tag for a `sqlite3_stmt *`. */
final private[sqlite] class SqliteStmt
