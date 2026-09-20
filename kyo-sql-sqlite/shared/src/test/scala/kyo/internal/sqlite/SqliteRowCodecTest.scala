package kyo.internal.sqlite

import kyo.*

/** Covers [[SqliteRowCodec]]'s resolution order: the declared type decides, and the stored value's class is the fallback.
  *
  * Each direction is pinned by a case the other breaks. Reading the stored class first would refuse nothing, a date and a decimal both being
  * text; reading the declared type only would refuse a CAST, which has no declared type and must still read back.
  *
  * SQLite reports the FIRST arm's declared type for every row of a compound select, so a later row can hold something else entirely. The
  * disagreement has to surface as a decode failure rather than as a plausible wrong value.
  */
class SqliteRowCodecTest extends Test:

    /** One in-memory database, opened through a pool of exactly one connection. `:memory:` gives every CONNECTION its own private database,
      * so a table created through one pooled connection would be missing from the next, and no leaf here needs two sessions.
      */
    private def withDb[A](f: String => A < (Async & Abort[SqlException] & Scope))(using Frame): A < (Async & Abort[SqlException]) =
        Scope.run(f("sqlite://:memory:"))

    "a compound select reports the first arm's declared type for every row" in {
        withDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _    <- client.executeRaw("CREATE TABLE d (v DATE TEXT)")
                        _    <- client.executeRaw("INSERT INTO d VALUES ('2026-08-25')")
                        rows <- client.query("SELECT v FROM d UNION ALL SELECT 42")
                    yield
                        assert(rows.size == 2, s"expected both arms, got ${rows.size}")
                        assert(
                            rows(0).columns.head.name == "v",
                            s"the compound select reports the first arm's column, got ${rows(0).columns.head.name}"
                        )
                }
            }
        }
    }

    "a row whose value contradicts the declared type fails to decode rather than answering a wrong value" in {
        withDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _    <- client.executeRaw("CREATE TABLE d (v DATE TEXT)")
                        _    <- client.executeRaw("INSERT INTO d VALUES ('2026-08-25')")
                        rows <- client.query("SELECT v FROM d UNION ALL SELECT 42")
                        good <- rows(0).decode[java.time.LocalDate](0)
                        bad  <- Abort.run[SqlException](rows(1).decode[java.time.LocalDate](0))
                    yield
                        assert(good.toString == "2026-08-25", s"the conforming row must decode, got $good")
                        // Answering some date parsed out of `42` would be the bad outcome: a value the caller cannot tell from a real one.
                        assert(bad.isFailure, s"a row contradicting its declared type must not decode to a value, got $bad")
                }
            }
        }
    }

    "a CAST has no declared type and still reads back" in {
        withDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _    <- client.executeRaw("CREATE TABLE t (i INTEGER)")
                        _    <- client.executeRaw("INSERT INTO t VALUES (42)")
                        rows <- client.query("SELECT CAST(i AS TEXT) FROM t")
                        v    <- rows(0).decode[String](0)
                    yield
                        // A CAST is traceable to no declared column, so the fallback is what lets it read.
                        assert(v == "42", s"a CAST must read back as its text, got $v")
                }
            }
        }
    }

end SqliteRowCodecTest
