package kyo.internal.sqlite

import kyo.*
import kyo.ffi.Ffi

/** Covers [[SqliteConnection]]'s transaction and statement lifecycle.
  *
  * What the read-only leaves guard is that a REFUSED statement releases the connection's permit. Without that the rollback which follows
  * waits on a permit nothing will hand back, and the session is unusable from then on.
  */
class SqliteConnectionTest extends Test:

    /** One in-memory database, opened through a pool of exactly one connection. `:memory:` gives every CONNECTION its own private database,
      * so a table created through one pooled connection would be missing from the next, and no leaf here needs two sessions.
      */
    private def withDb[A](f: String => A < (Async & Abort[SqlException] & Scope))(using Frame): A < (Async & Abort[SqlException]) =
        Scope.run(f("sqlite://:memory:"))

    "a read-only transaction opens and closes with no statement in it" in {
        withDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    client.transaction(Absent, readOnly = true)(Kyo.unit).andThen(succeed)
                }
            }
        }
    }

    "a read-only transaction runs a read" in {
        withDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _    <- client.executeRaw("CREATE TABLE t (id INT)")
                        rows <- client.transaction(Absent, readOnly = true)(client.query("SELECT count(*) FROM t"))
                        n    <- rows(0).decode[Long](0)
                    yield assert(n == 0L)
                }
            }
        }
    }

    "a write inside a read-only transaction is refused" in {
        withDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _       <- client.executeRaw("CREATE TABLE t (id INT)")
                        outcome <- Abort.run[SqlException] {
                            client.transaction(Absent, readOnly = true)(client.executeRaw("INSERT INTO t VALUES (1)"))
                        }
                    yield assert(outcome.isFailure, "a write inside a read-only transaction must be refused")
                }
            }
        }
    }

    "the connection is usable after a refused read-only write" in {
        withDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _ <- client.executeRaw("CREATE TABLE t (id INT)")
                        _ <- Abort.run[SqlException] {
                            client.transaction(Absent, readOnly = true)(client.executeRaw("INSERT INTO t VALUES (1)"))
                        }
                        rows <- client.query("SELECT count(*) FROM t")
                        n    <- rows(0).decode[Long](0)
                    yield assert(n == 0L, s"a refused write must leave nothing behind, saw $n")
                }
            }
        }
    }

    "a write succeeds once the read-only transaction is done" in {
        withDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _ <- client.executeRaw("CREATE TABLE t (id INT)")
                        _ <- Abort.run[SqlException] {
                            client.transaction(Absent, readOnly = true)(client.executeRaw("INSERT INTO t VALUES (1)"))
                        }
                        // query_only is per CONNECTION and survives the transaction, so one returned to the pool with it still
                        // on is poisoned for every later lease.
                        _    <- client.executeRaw("INSERT INTO t VALUES (2)")
                        rows <- client.query("SELECT count(*) FROM t")
                        n    <- rows(0).decode[Long](0)
                    yield assert(n == 1L, s"the later write must land, saw $n rows")
                }
            }
        }
    }

    /** A transaction interrupted while its `BEGIN IMMEDIATE` waits for another connection's write lock leaves nothing open.
      *
      * The native call cannot be cancelled: it goes on waiting after the fiber that issued it is gone, and takes the lock once the other
      * connection lets go. The pool's reclaim rolls back only a transaction the connection knows it opened, so a session that learned of
      * its transaction only after BEGIN returned goes back to the pool holding the database's write lock, and every later writer waits
      * out the busy timeout behind it. The next transaction on the same pooled connection is the observable: it must begin, not be
      * refused as a transaction within a transaction.
      *
      * Each round interrupts a waiter that cannot have finished, since the holder has the lock, but whether its BEGIN was already on
      * the wire when the interrupt landed is a race, so the scenario runs twenty times.
      */
    "a transaction interrupted while BEGIN waits for the write lock leaves no transaction on its connection" in {
        Scope.run {
            Path.run(Path.tempDir("kyo-sql-sqlite-interrupt")).map { dir =>
                val url = s"sqlite://${(dir / "db").unsafe.show}"
                // Unbounded budgets, so the leaf waits for the reclaim to finish whatever the host's speed. Under the defaults a slow
                // reclaim fails the next acquire, and one past `cancelTimeout` swaps in a fresh connection the reclaim never touched.
                val config = SqlConfig(maxConnections = 1, acquireTimeout = Duration.Infinity, cancelTimeout = Duration.Infinity)
                for
                    holder <- SqlClient.init(url, config)
                    waiter <- SqlClient.init(url, config)
                    _      <- holder.executeRaw("CREATE TABLE t (id INT)")
                    _      <- waiter.query("SELECT count(*) FROM t")
                    _      <- Kyo.foreachDiscard(1 to 20) { round =>
                        for
                            inside  <- Latch.init(1)
                            release <- Latch.init(1)
                            started <- Latch.init(1)
                            held    <- Fiber.init(holder.transaction {
                                holder.executeRaw(s"INSERT INTO t VALUES ($round)").andThen(inside.release).andThen(release.await)
                            })
                            _       <- inside.await
                            blocked <- Fiber.init(started.release.andThen {
                                waiter.transaction(waiter.executeRaw("INSERT INTO t VALUES (-1)"))
                            })
                            _           <- started.await
                            interrupted <- blocked.interrupt
                            _           <- release.release
                            _           <- held.get
                            after       <- Abort.run[SqlException](waiter.transaction(waiter.executeRaw("INSERT INTO t VALUES (0)")))
                        yield
                            assert(interrupted, s"round $round: the waiter cannot finish while the holder has the lock")
                            assert(after.isSuccess, s"round $round: the next transaction on the waiter's connection must begin, got $after")
                        end for
                    }
                    rows <- holder.query("SELECT count(*) FROM t WHERE id = -1")
                    n    <- rows(0).decode[Long](0)
                yield assert(n == 0L, s"no interrupted write may have landed, saw $n")
                end for
            }
        }
    }

    /** More writers wait for the write lock than the runtime has threads for native calls, and the holder still commits.
      *
      * On JS a native call runs on one of libuv's worker threads, four by default. A writer whose BEGIN IMMEDIATE waits in SQLite's busy
      * handler holds one of them until the lock frees, and the holder's COMMIT is a native call too, so once every worker is parked behind
      * the lock the call that would free it never starts. Each writer's transaction flag goes up immediately before it waits, with no
      * suspension between the two, so once every flag reads true every writer has reached the point where it waits.
      */
    "a commit lands while more writers wait for the write lock than there are threads for native calls" in {
        onFile("kyo-sql-sqlite-writers") { open =>
            for
                holder <- open()
                others <- Kyo.fill(Writers)(open())
                _      <- holder.simpleExecute("CREATE TABLE t (id INT)")
                _      <- holder.beginTransaction(Absent, readOnly = false)
                _      <- holder.simpleExecute("INSERT INTO t VALUES (0)")
                fibers <- Kyo.foreach(others) { conn =>
                    Fiber.init(
                        conn.beginTransaction(Absent, readOnly = false)
                            .andThen(conn.simpleExecute("INSERT INTO t VALUES (1)"))
                            .andThen(conn.commitTransaction)
                    )
                }
                _ <- assertEventually(Sync.defer(others.forall(_.inOpenTransaction(using AllowUnsafe.embrace.danger))))
                _ <- holder.commitTransaction
                _ <- Kyo.foreachDiscard(fibers)(_.get)
                n <- count(holder)
                _ <- Kyo.foreachDiscard(holder +: others)(_.close)
            yield assert(n == Writers + 1L, s"every writer must commit after the holder, saw $n rows")
        }
    }

    /** The same starvation through a deferred transaction, which takes no lock at BEGIN and asks for the write lock at its first write.
      *
      * The holder's reads before its COMMIT are the barrier. Each is a native call queued behind what the writers dispatched before it, so
      * the writers' INSERTs reach the native threads ahead of the COMMIT, and a write that waits there rather than for the gate parks them.
      */
    "a commit lands while more deferred transactions wait to write than there are threads for native calls" in {
        onFile("kyo-sql-sqlite-deferred") { open =>
            for
                holder <- open()
                others <- Kyo.fill(Writers)(open())
                _      <- holder.simpleExecute("CREATE TABLE t (id INT)")
                _      <- holder.beginTransaction(Absent, readOnly = false)
                _      <- holder.simpleExecute("INSERT INTO t VALUES (0)")
                fibers <- Kyo.foreach(others) { conn =>
                    Fiber.init(
                        conn.simpleExecute("BEGIN")
                            .andThen(conn.simpleExecute("INSERT INTO t VALUES (1)"))
                            .andThen(conn.simpleExecute("COMMIT"))
                    )
                }
                _ <- Kyo.foreachDiscard(1 to Writers)(_ => holder.simpleQuery("SELECT 1"))
                _ <- holder.commitTransaction
                _ <- Kyo.foreachDiscard(fibers)(_.get)
                n <- count(holder)
                _ <- Kyo.foreachDiscard(holder +: others)(_.close)
            yield assert(n == Writers + 1L, s"every deferred writer must commit after the holder, saw $n rows")
        }
    }

    "a writer interrupted while it waits for the write gate leaves the gate free and no transaction on its connection" in {
        onFile("kyo-sql-sqlite-gate-wait") { open =>
            for
                holder <- open()
                waiter <- open()
                _      <- holder.simpleExecute("CREATE TABLE t (id INT)")
                _      <- holder.beginTransaction(Absent, readOnly = false)
                fiber  <- Fiber.init(waiter.beginTransaction(Absent, readOnly = false))
                gates  <- waiter.writeGate.covered
                _      <- assertEventually(Abort.run[Closed](gates.head.waiters).map(_ == Result.succeed(1)))
                _      <- fiber.interrupt
                _      <- holder.simpleExecute("INSERT INTO t VALUES (0)")
                _      <- holder.commitTransaction
                // The pool's reclaim, in its order.
                _       <- waiter.cancelInFlight
                _       <- waiter.drainToIdle
                flagged <- Sync.defer(waiter.inOpenTransaction(using AllowUnsafe.embrace.danger))
                _       <- writeOne(holder, 1)
                _       <- writeOne(waiter, 2)
                n       <- count(holder)
                _       <- Kyo.foreachDiscard(Chunk(holder, waiter))(_.close)
            yield
                assert(!flagged, "the reclaim must leave no transaction flagged on a connection whose BEGIN never ran")
                assert(n == 3L, s"both connections must write after the interrupted wait, saw $n rows")
        }
    }

    "a writer interrupted while BEGIN waits on a lock taken outside the gate gives the gate back once reclaimed" in {
        onFile("kyo-sql-sqlite-gate-begin") { file =>
            given AllowUnsafe                               = AllowUnsafe.embrace.danger
            def exec(db: Ffi.Handle[SqliteDb], sql: String) =
                Sync.Unsafe.defer(file.bindings.execSimple(db, sql)).map(_.safe.get).map(rc => assert(rc == 0, s"$sql answered $rc"))
            for
                writer <- file()
                other  <- file()
                _      <- writer.simpleExecute("CREATE TABLE t (id INT)")
                // A bare handle, outside every connection's gate, takes the lock as another process would.
                outsider <- Sync.Unsafe.defer(file.bindings.openV2(file.path, SqliteConnectionFactory.OpenFlags, "")).map(_.safe.get)
                bare = outsider.getOrElse(fail("the bare handle must open"))
                _     <- exec(bare, "BEGIN IMMEDIATE")
                fiber <- Fiber.init(writer.beginTransaction(Absent, readOnly = false))
                _     <- assertEventually(writer.writeGate.held)
                _     <- fiber.interrupt
                _     <- exec(bare, "COMMIT")
                _     <- writer.cancelInFlight
                _     <- writer.drainToIdle
                held  <- writer.writeGate.held
                _     <- writeOne(other, 1)
                _     <- writeOne(writer, 2)
                n     <- count(other)
                _     <- Kyo.foreachDiscard(Chunk(writer, other))(_.close)
                _     <- Sync.Unsafe.defer(file.bindings.closeV2(bare)).map(_.safe.get)
            yield
                assert(!held, "the reclaim's rollback must give the gate back")
                assert(n == 2L, s"both connections must write after the reclaim, saw $n rows")
            end for
        }
    }

    "a failed COMMIT gives the write gate back" in {
        onFile("kyo-sql-sqlite-gate-commit") { open =>
            for
                failing <- open()
                other   <- open()
                _       <- failing.simpleExecute("CREATE TABLE p (id INTEGER PRIMARY KEY)")
                _       <- failing.simpleExecute("CREATE TABLE c (pid INT REFERENCES p(id) DEFERRABLE INITIALLY DEFERRED)")
                _       <- failing.beginTransaction(Absent, readOnly = false)
                _       <- failing.simpleExecute("INSERT INTO c VALUES (1)")
                commit  <- Abort.run[SqlException](failing.commitTransaction)
                held    <- failing.writeGate.held
                _       <- other.beginTransaction(Absent, readOnly = false)
                _       <- other.simpleExecute("INSERT INTO p VALUES (1)")
                _       <- other.commitTransaction
                _       <- Kyo.foreachDiscard(Chunk(failing, other))(_.close)
            yield
                assert(commit.isFailure, s"the deferred foreign key must refuse the COMMIT, got $commit")
                assert(!held, "the COMMIT's rollback must give the gate back")
        }
    }

    "a pooled transaction interrupted while it holds the write gate gives it back" in {
        Scope.run {
            Path.run(Path.tempDir("kyo-sql-sqlite-gate-pool")).map { dir =>
                val url    = s"sqlite://${(dir / "db").unsafe.show}"
                val config = SqlConfig(maxConnections = 1, acquireTimeout = Duration.Infinity, cancelTimeout = Duration.Infinity)
                for
                    first  <- SqlClient.init(url, config)
                    second <- SqlClient.init(url, config)
                    _      <- first.executeRaw("CREATE TABLE t (id INT)")
                    inside <- Latch.init(1)
                    never  <- Latch.init(1)
                    held   <- Fiber.init(first.transaction {
                        first.executeRaw("INSERT INTO t VALUES (-1)").andThen(inside.release).andThen(never.await)
                    })
                    _    <- inside.await
                    _    <- held.interrupt
                    _    <- second.transaction(second.executeRaw("INSERT INTO t VALUES (1)"))
                    _    <- first.transaction(first.executeRaw("INSERT INTO t VALUES (2)"))
                    rows <- second.query("SELECT count(*) FROM t WHERE id > 0")
                    n    <- rows(0).decode[Long](0)
                yield assert(n == 2L, s"both clients must write after the interrupted transaction, saw $n rows")
                end for
            }
        }
    }

    "closing every connection to a file drops its write gate" in {
        onFile("kyo-sql-sqlite-gate-close") { open =>
            for
                a      <- open()
                b      <- open()
                gates  <- a.writeGate.covered
                before <- SqliteWriteGate.references(gates.head.path)
                _      <- a.close
                _      <- Sync.defer(b.closeNow(using summon[Frame], AllowUnsafe.embrace.danger))
                after  <- SqliteWriteGate.references(gates.head.path)
            yield
                assert(before == 2, s"both connections must reference the file's gate, saw $before")
                assert(after == 0, s"no reference may outlive the connections, saw $after")
        }
    }

    "an in-memory database has no write gate" in {
        Sync.Unsafe.defer(Ffi.load[VendoredSqliteBindings]).map { bindings =>
            val config = SqlConfig(maxConnections = 1, acquireTimeout = Duration.Infinity)
            new SqliteConnectionFactory(bindings).open(SqlConfig.Address.Local("sqlite", ":memory:"), Absent, config).map { conn =>
                conn.writeGate.covered.map { covered =>
                    conn.close.andThen(assert(covered.isEmpty, s"an in-memory database has no file lock to gate, saw $covered"))
                }
            }
        }
    }

    private val Writers = 16

    /** One fresh database file, whose `apply` opens a connection to it outside any pool. */
    final private class DbFile(val bindings: VendoredSqliteBindings, val path: String):
        private val factory = new SqliteConnectionFactory(bindings)
        private val config  = SqlConfig(maxConnections = 1, acquireTimeout = Duration.Infinity)
        def apply()(using Frame): SqliteConnection < (Async & Abort[SqlException]) =
            factory.open(SqlConfig.Address.Local("sqlite", path), Absent, config)
    end DbFile

    private def onFile[A](prefix: String)(f: DbFile => A < (Async & Abort[SqlException] & Scope))(using
        Frame
    ): A < (Async & Abort[SqlException | FileSystemException]) =
        Scope.run {
            Path.run(Path.tempDir(prefix)).map { dir =>
                Sync.Unsafe.defer(Ffi.load[VendoredSqliteBindings]).map(bindings => f(new DbFile(bindings, (dir / "db").unsafe.show)))
            }
        }

    private def writeOne(conn: SqliteConnection, id: Int)(using Frame): Unit < (Async & Abort[SqlException]) =
        conn.beginTransaction(Absent, readOnly = false)
            .andThen(conn.simpleExecute(s"INSERT INTO t VALUES ($id)"))
            .andThen(conn.commitTransaction)

    private def count(conn: SqliteConnection)(using Frame): Long < (Async & Abort[SqlException]) =
        conn.simpleQuery("SELECT count(*) FROM t").map(rows => rows(0).decode[Long](0))

end SqliteConnectionTest
