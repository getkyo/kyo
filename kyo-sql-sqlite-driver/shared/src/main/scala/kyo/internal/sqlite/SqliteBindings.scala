package kyo.internal.sqlite
import kyo.*
import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.Maybe
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** The SQLite C API this driver speaks, as declarations alone.
  *
  * No `Ffi.Config` companion, deliberately: a config ties a binding to one library, and the engine-bound subtypes
  * carry it instead (`VendoredSqliteBindings` for the SQLite this build compiles, `DoltLiteBindings` for the
  * prebuilt fork). That split is what keeps the two engines apart. On Scala Native an FFI module's C is compiled
  * INTO the binary, so a module depending on an engine module links that engine in: with both present the
  * sqlite3_* calls bind to the vendored SQLite while the Dolt archive contributes nothing, a binary that links and
  * is quietly the wrong engine. Depending on declarations carries no C at all.
  *
  * Most methods bind a `sqlite3_*` symbol directly. The rest bind a `kyo_sqlite3_*` wrapper from `kyo_sqlite.c`,
  * which exists only where sqlite3.h has a shape this generator cannot express: an out-parameter that is not last,
  * a variadic function, a sentinel that is a cast rather than a value, or a read whose length cannot be recovered
  * from a C string.
  *
  * `@Ffi.blocking` marks everything that can touch the file or wait on a lock. The accessors are left plain: they
  * read memory the step already produced.
  *
  * Reads return a length rather than a string, because SQLite text may contain NUL bytes and a C-string read
  * truncates there: `length('a' || char(0) || 'b')` is 3 where `strlen` of the same pointer is 1.
  */
private[kyo] trait SqliteBindings extends Ffi:

    /** Opens a connection, answering the handle alone.
      *
      * The result code does not come back with it, since a multi-value return must be entirely primitive. It is read from the connection
      * with [[extendedErrcode]] instead, which is sound because SQLite allocates the handle before it can fail and records the failure on
      * it. [[Absent]] means SQLite could not allocate at all.
      *
      * The handle is the caller's to close whether or not the open succeeded. `vfs` is `""` for the default VFS, never `null`: a String
      * argument is marshalled by reading its bytes, so a null one throws before the call is made, and the wrapper translates empty back to
      * NULL.
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
      * tail would silently run a prefix of what it was given.
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

    // Parameter indices are ONE-based, unlike column indices: mixing them up binds the wrong slot and reports success.
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

    /** The extended result code, which is the only thing distinguishing the constraint classes: a primary-key violation is 1555 and a
      * unique index violation 2067, where the primary code is 19 for both.
      */
    def extendedErrcode(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Int
    def errmsg(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Ffi.Borrowed[String]

    /** Rows changed by the most recent statement. SQLite counts MATCHED rows, the semantics PostgreSQL has natively and MySQL needs
      * CLIENT_FOUND_ROWS for.
      */
    def changes64(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Long

    /** The rowid of the last insert, or 0 for a WITHOUT ROWID table. Zero is not an error and not a rowid, so a caller reading a generated
      * key needs RETURNING rather than this.
      */
    def lastInsertRowid(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Long

    /** Interrupts whatever is running on `db`, from any thread. An FFI call cannot be cancelled mid-flight, so this is the only way to stop
      * a running step. Must be serialised against close: interrupting a connection that may close concurrently is undefined. Interrupting a
      * write inside an explicit transaction rolls the WHOLE transaction back.
      */
    def interrupt(db: Ffi.Handle[SqliteDb])(using AllowUnsafe): Unit

    /** The library's version as an integer, `X*1000000 + Y*1000 + Z`, read at runtime so a build that falls back to a host library reports
      * what it actually linked.
      */
    def libversionNumber()(using AllowUnsafe): Int

end SqliteBindings

/** Tag for a `sqlite3 *`. */
final private[kyo] class SqliteDb

/** Tag for a `sqlite3_stmt *`. */
final private[kyo] class SqliteStmt
