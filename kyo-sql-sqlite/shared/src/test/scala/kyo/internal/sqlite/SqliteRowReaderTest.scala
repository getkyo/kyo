package kyo.internal.sqlite

import kyo.*

/** Covers the reads in [[SqliteRowReader]] that have to answer for something the engine does not.
  *
  * SQLite's integers are 64-bit and there is no narrower type, so an expression over columns the DDL calls INTEGER overflows 32 bits without
  * complaint. The other engines refuse that at the server. Nothing in a dialect can reconcile it without wrapping every arithmetic
  * expression in a range guard, which would change the answer for legitimate 64-bit arithmetic, so the reconciliation happens at the read:
  * a value that does not fit the type being decoded is refused, and refused as OUT OF RANGE rather than as malformed text.
  *
  * The cross-engine leaf that depends on this only sees the refusal. These pin the reason, so a change to the range check shows up here,
  * naming the mechanism, rather than as an unexplained red in the battery.
  */
class SqliteRowReaderTest extends Test:

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

    "arithmetic over INTEGER columns does not overflow at 32 bits, so the engine reports nothing" in {
        withDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _    <- client.executeRaw("CREATE TABLE t (a INTEGER, b INTEGER)")
                        _    <- client.executeRaw("INSERT INTO t VALUES (2147483647, 1)")
                        rows <- client.query("SELECT a + b FROM t")
                        wide <- rows(0).decode[Long](0)
                    yield
                        // Read at the width the value actually has, the sum is an ordinary 64-bit integer and no error was raised. This is
                        // the fact the other engines do not share, and the reason the refusal has to come from the read.
                        assert(wide == 2147483648L, s"the sum must be exact at 64 bits, got $wide")
                }
            }
        }
    }

    "the same value read at a width that cannot hold it is refused as out of range" in {
        withDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _      <- client.executeRaw("CREATE TABLE t (a INTEGER, b INTEGER)")
                        _      <- client.executeRaw("INSERT INTO t VALUES (2147483647, 1)")
                        rows   <- client.query("SELECT a + b FROM t")
                        narrow <- Abort.run[SqlException](rows(0).decode[Int](0))
                    yield
                        // Out of range rather than a decode failure: the text IS a number, it just does not fit, and a caller that catches
                        // the range marker is the one this is for. It is what makes the cross-engine overflow leaf agree.
                        assert(
                            narrow.failure.exists(_.isInstanceOf[SqlValueOutOfRange]),
                            s"a value past Int must be refused as out of range, got $narrow"
                        )
                }
            }
        }
    }

    "text that is not a number is a numeric failure, not a range one" in {
        withDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _    <- client.executeRaw("CREATE TABLE t (v TEXT)")
                        _    <- client.executeRaw("INSERT INTO t VALUES ('hello')")
                        rows <- client.query("SELECT v FROM t")
                        bad  <- Abort.run[SqlException](rows(0).decode[Long](0))
                    yield
                        // The other half of the split. Both arrive at the same reader and a caller acts on them differently: one says the
                        // column never held numbers, the other that this number is too big for the type asked for.
                        assert(
                            bad.failure.exists(_.isInstanceOf[SqlDecodeNumericException]),
                            s"text that is not a number must be a numeric failure, got $bad"
                        )
                }
            }
        }
    }

end SqliteRowReaderTest
