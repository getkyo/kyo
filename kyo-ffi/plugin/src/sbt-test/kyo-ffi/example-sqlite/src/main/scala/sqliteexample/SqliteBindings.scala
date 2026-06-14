package sqliteexample

import kyo.AllowUnsafe
import kyo.Fiber
import kyo.ffi.*

/** Worked example: SQLite-style bindings, the canonical Panama benchmark target.
  *
  * Demonstrates:
  *   - String parameters (filename, sql), auto-marshalled as UTF-8 C strings.
  *   - Opaque handles (`sqlite3*`) represented as `Long`.
  *   - Transient per-row callback inside `exec`, invoked synchronously during
  *     the call, no `Ffi.Guard` needed.
  *
  * In a real-world binding the same trait works verbatim against linked
  * `sqlite3`; only `build.sbt` swaps `ffiCSources` for `ffiLinkLibs := Seq("sqlite3")`.
  */
trait SqliteBindings extends Ffi:
    // sqlite3_open(filename) -> sqlite3* (opaque handle as Long)
    // Blocking because the real call may do disk I/O when opening on-disk DBs.
    @Ffi.blocking
    def sqlite3Open(filename: String)(using AllowUnsafe): Fiber.Unsafe[Long, Any]

    // sqlite3_close(db) -> int (0 = ok, non-zero = error)
    @Ffi.blocking
    def sqlite3Close(db: Long)(using AllowUnsafe): Fiber.Unsafe[Int, Any]

    // sqlite3_exec(db, sql, cb) -> int
    // `cb(ncols, rowIdx)` is invoked once per result row; return non-zero to abort.
    @Ffi.blocking
    def sqlite3Exec(db: Long, sql: String, cb: (Int, Int) => Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]
end SqliteBindings

object SqliteBindings extends Ffi.Config(library = "kyo_sqlite_stub", symbolPrefix = "kyo_")
