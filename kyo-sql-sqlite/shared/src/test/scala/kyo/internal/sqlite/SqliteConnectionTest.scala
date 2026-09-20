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

end SqliteConnectionTest
