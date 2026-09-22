package kyo.internal.sqlite

import kyo.*

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
                for
                    holder <- SqlClient.init(url, SqlConfig(maxConnections = 1))
                    waiter <- SqlClient.init(url, SqlConfig(maxConnections = 1))
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

end SqliteConnectionTest
