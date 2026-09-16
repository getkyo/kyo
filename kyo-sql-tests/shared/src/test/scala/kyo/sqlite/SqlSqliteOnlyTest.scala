package kyo.sqlite

import kyo.*

/** SQLite-only behaviour, run against the real engine rather than asserted from the dialect.
  *
  * Two kinds of leaf. The refusals pin what the other two engines have and this one reports as a typed failure. The rest are renderings
  * whose correctness shows only in what the engine does with the SQL, not in its text, which the dialect suites cover instead.
  *
  * Every leaf drives ONE connection over `:memory:`, where each connection gets its own private database, so nothing here needs a second
  * session and the suite runs on every platform. The concurrency properties belong to the shared battery, over a real file.
  */
class SqlSqliteOnlyTest extends Test:

    case class Person(id: Long, name: String, age: Int) derives SqlSchema, CanEqual
    case class Reading(id: Long, v: Double) derives SqlSchema, CanEqual
    case class Measure(id: Long, v: BigDecimal) derives SqlSchema, CanEqual

    private def withClient[A](f: SqlClient => A < (Async & Abort[SqlException] & Scope & DB))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        Scope.run {
            SqlClient.init("sqlite://:memory:", SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client)(f(client))
            }
        }

    // --- what this engine refuses, which is its whole engine-specific surface ---

    // The two weaker levels have no knob that produces them here, and accepting one would hand back snapshot
    // isolation under a name promising less.
    "the isolation levels this engine cannot deliver are refused by name" in {
        withClient { client =>
            Kyo.foreach(Chunk(SqlClient.IsolationLevel.ReadUncommitted, SqlClient.IsolationLevel.ReadCommitted)) { level =>
                Abort.run[SqlException](client.transaction(Present(level), false)(client.query("SELECT 1"))).map {
                    case Result.Failure(e: SqliteIsolationLevelUnsupportedException) =>
                        assert(e.requested == level, s"the refusal must name the level asked for, got ${e.requested} for $level")
                        assert(e.actual == SqlClient.IsolationLevel.RepeatableRead, s"the refusal names ${e.actual} as delivered")
                    case other => fail(s"$level must be refused by name, got $other")
                }
            }.unit
        }
    }

    "the two levels this engine does deliver are accepted" in {
        withClient { client =>
            Kyo.foreach(Chunk(SqlClient.IsolationLevel.RepeatableRead, SqlClient.IsolationLevel.Serializable)) { level =>
                client.transaction(Present(level), false)(client.query("SELECT 1")).map { rows =>
                    assert(rows.size == 1, s"$level must run, got ${rows.size} rows")
                }
            }.unit
        }
    }

    // One writer over the whole database, so there is no second party for a per-key lock to coordinate with, and a
    // silent no-op would be worse than a refusal: a caller would believe it held a lock it never took.
    "advisory locks are refused rather than silently doing nothing" in {
        withClient { client =>
            Abort.run[SqlException](client.withAdvisoryLock(42L)(client.query("SELECT 1"))).map {
                case Result.Failure(_: SqliteAdvisoryLockUnsupportedException) => succeed
                case other                                                     => fail(s"an advisory lock must be refused, got $other")
            }
        }
    }

    // sqlite3_prepare_v2 compiles the FIRST statement and reports the rest as a tail it did not run. Accepting the
    // call would run one statement and silently drop the others, so the driver refuses the whole string.
    "several statements in one call are refused rather than running only the first" in {
        withClient { client =>
            client.executeRaw("CREATE TABLE t (id INTEGER PRIMARY KEY, n INT)").andThen {
                Abort.run[SqlException](client.executeRaw("INSERT INTO t (n) VALUES (1); INSERT INTO t (n) VALUES (2)")).map {
                    case Result.Failure(_: SqliteMultipleStatementsException) =>
                        // The refusal must also have run NOTHING, which is the half that matters.
                        client.query("SELECT COUNT(*) FROM t").map(rows => rows(0).decode[Long](0)).map { n =>
                            assert(n == 0L, s"a refused multi-statement call must run neither statement, $n rows landed")
                        }
                    case other => fail(s"several statements must be refused, got $other")
                }
            }
        }
    }

    // sqlite3_bind_double stores NaN as NULL and reports success, so a bound NaN would turn a value into an absent
    // one with nothing red. The same refusal the writer suite pins, reached the way a caller reaches it.
    "NaN is refused at bind, because the engine would store NULL and report success" in {
        withClient { client =>
            client.executeRaw("CREATE TABLE reading (id INTEGER PRIMARY KEY, v DOUBLE)").andThen {
                Abort.run[SqlException](client.execute(Sql.insert[Reading].values(Reading(1L, Double.NaN)))).map {
                    case Result.Failure(_: SqliteNaNNotStorableException) =>
                        client.query("SELECT COUNT(*) FROM reading").map(rows => rows(0).decode[Long](0)).map { n =>
                            assert(n == 0L, s"a refused bind must store nothing, $n rows landed")
                        }
                    case other => fail(s"NaN must be refused at bind, got $other")
                }
            }
        }
    }

    // The infinities are NOT refused: SQLite holds them and reads them back, so the refusal above is specific to the
    // value the C call mishandles rather than a blanket rule about non-finite doubles.
    "the infinities round-trip, so the NaN refusal is not a blanket rule" in {
        withClient { client =>
            for
                _    <- client.executeRaw("CREATE TABLE reading (id INTEGER PRIMARY KEY, v DOUBLE)")
                _    <- client.execute(Sql.insert[Reading].values(Reading(1L, Double.PositiveInfinity)))
                _    <- client.execute(Sql.insert[Reading].values(Reading(2L, Double.NegativeInfinity)))
                rows <- client.query("SELECT v FROM reading ORDER BY id")
                hi   <- rows(0).decode[Double](0)
                lo   <- rows(1).decode[Double](0)
            yield
                assert(hi == Double.PositiveInfinity, s"positive infinity read back as $hi")
                assert(lo == Double.NegativeInfinity, s"negative infinity read back as $lo")
        }
    }

    // --- renderings whose correctness is only visible in what the engine does ---

    // SQLite reads the ON of ON CONFLICT as the start of a join constraint, which sqlite.org documents as a parsing
    // ambiguity. The dialect wraps the fed query and puts a tautological WHERE on the wrapper. The dialect suite pins
    // the text; this pins that the text the workaround produces actually PARSES and upserts.
    "an upsert fed by a SELECT parses and upserts, which is what the wrapper is for" in {
        withClient { client =>
            for
                _ <- client.executeRaw("CREATE TABLE person (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)")
                _ <- client.executeRaw("INSERT INTO person (id, name, age) VALUES (1, 'alice', 30)")
                _ <- client.execute(
                    Sql.insert[Person].fromSelect(_.id, _.name, _.age)(
                        Sql.from[Person]("s").where(c => c.s.age > 0).select(c => (c.s.id, c.s.name, c.s.age))
                    ).onConflictDoNothing(_.id)
                )
                rows <- client.query("SELECT id, name FROM person ORDER BY id")
                one  <- rows(0).decode[String](1)
            yield
                // Fed by the table itself, so the one existing row conflicts with itself and DO NOTHING keeps it.
                assert(rows.size == 1, s"expected the one row, got ${rows.size}")
                assert(one == "alice", s"the conflicting row must be untouched, got '$one'")
        }
    }

    // A decimal bound as a double loses its scale and its digits past a double before the column ever sees it, so the
    // driver binds it as text and the DDL declares TEXT affinity to hold it verbatim. Twenty digits is past what a
    // double carries, which is what makes this leaf fail if either half is dropped.
    "a decimal keeps every digit, because it is bound as text into a TEXT-affinity column" in {
        withClient { client =>
            val exact = BigDecimal("0.12345678901234567890")
            for
                _        <- client.executeRaw("CREATE TABLE measure (id INTEGER PRIMARY KEY, v DECIMAL TEXT(38,20))")
                _        <- client.execute(Sql.insert[Measure].values(Measure(1L, exact)))
                rows     <- client.query("SELECT v FROM measure")
                readBack <- rows(0).decode[BigDecimal](0)
            yield assert(readBack == exact, s"expected $exact, read back $readBack")
            end for
        }
    }

    // NULL happens to auto-assign for an INTEGER PRIMARY KEY, which is exactly the case the dialect's omission serves,
    // so a dialect that substituted NULL would pass a generated-key test and store NULL for every other defaulted
    // column. Asserting the key came back AND that a defaulted non-key column kept its declared default separates them.
    "a defaulted column takes its declared default rather than NULL" in {
        withClient { client =>
            for
                _    <- client.executeRaw("CREATE TABLE d (id INTEGER PRIMARY KEY AUTOINCREMENT, tag TEXT NOT NULL DEFAULT 'unset')")
                _    <- client.executeRaw("INSERT INTO d DEFAULT VALUES")
                rows <- client.query("SELECT id, tag FROM d")
                id   <- rows(0).decode[Long](0)
                tag  <- rows(0).decode[String](1)
            yield
                assert(id == 1L, s"the key must be auto-assigned, got $id")
                assert(tag == "unset", s"the defaulted column must take its declared default, got '$tag'")
        }
    }

end SqlSqliteOnlyTest
