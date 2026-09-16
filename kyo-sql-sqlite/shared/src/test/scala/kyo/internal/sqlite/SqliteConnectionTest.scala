package kyo.internal.sqlite

import kyo.*

/** Covers [[SqliteConnection]]'s transaction and statement lifecycle.
  *
  * The read-only leaves below are the ones the conformance battery reaches through
  * `SqlIsolationConformanceTest`'s "a readOnly transaction rejects a write with the typed server error" leaf. They are kept here as well,
  * driven directly against the connection, because the battery leaf can only report that the whole thing stopped while these name the step
  * it stopped at. They are the regression guard for a session that stopped responding: a refused statement used to leave the connection's permit
  * outstanding, so the rollback that followed waited on something nothing would hand back.
  */
class SqliteConnectionTest extends Test:

    /** One in-memory database, opened through a pool of exactly one connection.
      *
      * `:memory:` gives every CONNECTION its own private database, so a table created through one pooled connection would be missing from
      * the next. A single connection is what makes the name mean one database, and it is enough for every leaf here: each drives one client
      * and none needs two sessions.
      *
      * No filesystem, which is what lets this suite run on every platform the module targets rather than only the ones with `java.nio.file`.
      * The conformance descriptor, which DOES open a second client, uses a real file for that reason.
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
                        _ <- client.executeRaw("CREATE TABLE t (id INT)")
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
                        // query_only is per CONNECTION and survives the transaction. A connection returned to the pool with it
                        // still on is poisoned for every later lease, which no single leaf would attribute here.
                        _    <- client.executeRaw("INSERT INTO t VALUES (2)")
                        rows <- client.query("SELECT count(*) FROM t")
                        n    <- rows(0).decode[Long](0)
                    yield assert(n == 1L, s"the later write must land, saw $n rows")
                }
            }
        }
    }

end SqliteConnectionTest
