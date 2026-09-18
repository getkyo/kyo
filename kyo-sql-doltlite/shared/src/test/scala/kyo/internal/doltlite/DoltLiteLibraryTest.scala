package kyo.internal.doltlite

import kyo.*
import kyo.ffi.Ffi

/** That the generated binding reaches the staged DoltLite library, end to end.
  *
  * Deliberately below the driver: no connection, no codec, no dialect. Everything above assumes these calls work, so
  * when a higher suite goes red this suite says whether the binding is the reason.
  */
class DoltLiteLibraryTest extends Test:

    // Unsafe: the binding tier is the unsafe one by construction, and this suite exercises it directly rather than
    // through the driver that would otherwise own the boundary.
    import AllowUnsafe.embrace.danger

    private val OpenReadWrite = 0x00000002
    private val OpenCreate    = 0x00000004
    private val Ok            = 0
    private val Row           = 100

    private def bindings(using Frame): DoltLiteBindings < Sync =
        // The engine is published for some platforms and not others, and CI runs this leg on one it is
        // absent from. Cancelling names that, where letting the load fail would report a missing native as
        // a broken driver. The unavailability contract itself is asserted by its own leaf in DoltLiteClientTest.
        assume(DoltLiteEngineProbe.available, "the DoltLite engine is not published for this platform")
        Sync.Unsafe.defer(Ffi.load[DoltLiteBindings])
    end bindings

    /** Opens an in-memory database, configures it, and closes it whatever the body does. */
    private def withDb[A](f: (DoltLiteBindings, Ffi.Handle[kyo.internal.sqlite.SqliteDb]) => A < Async)(using
        Frame
    ): A < Async =
        Scope.run {
            bindings.flatMap { b =>
                // "" rather than null: marshalling a String reads its bytes, so a null one throws before the call.
                Sync.Unsafe.defer(b.openV2(":memory:", OpenReadWrite | OpenCreate, "")).map(_.safe.get).flatMap {
                    case Absent => Abort.panic(new AssertionError("could not allocate a connection handle"))
                    case Present(db) =>
                        Scope.ensure(Sync.Unsafe.defer(b.closeV2(db)).map(_.safe.get).unit).andThen {
                            Sync.Unsafe.defer(b.configureConnection(db, 5000)).map(_.safe.get).flatMap(_ => f(b, db))
                        }
                }
            }
        }

    "the staged library loads and answers a SQLite version" in {
        bindings.map { b =>
            Sync.Unsafe.defer(b.libversionNumber()).map { v =>
                // DoltLite v0.50.10 reports SQLite 3.54.0, which renders as 3*1000000 + 54*1000 + 0. A floor rather
                // than an equality: what matters is that a real engine answered.
                assert(v >= 3_054_000, s"expected the staged engine's version, got $v")
            }
        }
    }

    "a statement prepares, steps and returns a row" in {
        withDb { (b, db) =>
            for
                created <- Sync.Unsafe.defer(b.execSimple(db, "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")).map(_.safe.get)
                _ = assert(created == Ok, s"CREATE answered $created")
                inserted <- Sync.Unsafe.defer(b.execSimple(db, "INSERT INTO t VALUES (1, 'alice')")).map(_.safe.get)
                _ = assert(inserted == Ok, s"INSERT answered $inserted")
                stmt <- Sync.Unsafe.defer(b.prepareOne(db, "SELECT v FROM t", -1)).map(_.safe.get)
                res <- stmt match
                    case Absent => Abort.panic(new AssertionError("prepare produced no statement"))
                    case Present(s) =>
                        Sync.Unsafe.defer(b.step(s)).map(_.safe.get).map { code =>
                            Sync.Unsafe.defer(b.finalizeStmt(s)).map { _ =>
                                assert(code == Row, s"step answered $code rather than a row")
                            }
                        }
            yield res
            end for
        }
    }

    "the library is DoltLite rather than a plain SQLite" in {
        withDb { (b, db) =>
            Sync.Unsafe.defer(b.prepareOne(db, "SELECT dolt_version()", -1)).map(_.safe.get).map {
                case Absent =>
                    fail("dolt_version() did not prepare, so the loaded library is not DoltLite")
                case Present(s) =>
                    Sync.Unsafe.defer(b.step(s)).map(_.safe.get).map { code =>
                        Sync.Unsafe.defer(b.finalizeStmt(s)).map { _ =>
                            assert(code == Row, s"dolt_version() answered $code rather than a row")
                        }
                    }
            }
        }
    }

end DoltLiteLibraryTest
