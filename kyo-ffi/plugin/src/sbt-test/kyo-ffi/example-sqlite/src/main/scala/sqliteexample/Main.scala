package sqliteexample

import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.*

/** Driver: open an in-memory DB, execute a SELECT 1-style statement with a
  * row-callback, close the DB. Asserts the callback fires once with the
  * expected arguments.
  *
  * `sqlite3Open`/`sqlite3Exec`/`sqlite3Close` are `@Ffi.blocking`, so each
  * returns a `Fiber.Unsafe` the caller must await. The body runs as an `Async`
  * computation (awaiting with `.safe.get`) and is driven to completion by
  * `KyoApp.Unsafe.runAndBlock`.
  */
object Main:
    def main(args: Array[String]): Unit =
        val db = Ffi.load[SqliteBindings]

        val program: Unit < (Async & Scope & Abort[Any]) =
            for
                handle <- db.sqlite3Open(":memory:").safe.get
                _ =
                    if handle == 0L then
                        throw new AssertionError("sqlite3Open returned null handle")

                rowCount   = new java.util.concurrent.atomic.AtomicInteger(0)
                lastNcols  = new java.util.concurrent.atomic.AtomicInteger(-1)
                lastRowIdx = new java.util.concurrent.atomic.AtomicInteger(-1)

                rc <- db.sqlite3Exec(
                    handle,
                    "SELECT 1",
                    (ncols, rowIdx) =>
                        rowCount.incrementAndGet()
                        lastNcols.set(ncols)
                        lastRowIdx.set(rowIdx)
                        0 // continue
                ).safe.get

                _ =
                    if rc != 0 then
                        throw new AssertionError(s"sqlite3Exec: expected rc=0, got $rc")
                    if rowCount.get() != 1 then
                        throw new AssertionError(s"expected 1 row callback, got ${rowCount.get()}")
                    if lastNcols.get() != 1 then
                        throw new AssertionError(s"expected ncols=1, got ${lastNcols.get()}")
                    if lastRowIdx.get() != 0 then
                        throw new AssertionError(s"expected rowIdx=0, got ${lastRowIdx.get()}")

                closeRc <- db.sqlite3Close(handle).safe.get
            yield
                if closeRc != 0 then
                    throw new AssertionError(s"sqlite3Close: expected 0, got $closeRc")
                println(s"OK: handle=$handle rows=${rowCount.get()} ncols=${lastNcols.get()} closeRc=$closeRc")

        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(program).getOrThrow
    end main
end Main
