package kyo.mysql

import kyo.*
import kyo.Sql.*
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend
import kyo.internal.mysql.MysqlDialect

/** MySQL-only feature tests.
  *
  * Features covered:
  *   - ilike on MySQL (emulated as LOWER(x) LIKE LOWER(p))
  *   - ++ concat on MySQL (rendered as CONCAT(…))
  *   - onConflictDoNothing is idempotent on MySQL (INSERT IGNORE)
  *   - onConflictDoUpdate updates existing row on MySQL (ON DUPLICATE KEY UPDATE)
  */
class SqlMysqlOnlyTest extends SqlContainerTest:

    override def timeout: Duration = 5.minutes

    case class Person(id: Long, name: String, age: Int) derives SqlSchema, CanEqual

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def withMyClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(f: SqlClient => A < (S & Async & Abort[SqlException] & DB))(using
        Frame
    ): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlConnectionException](MysqlClient.init(myUrl(ctx))).flatMap {
            case Result.Success(client) =>
                Scope.ensure(client.close).andThen(DB.run(client)(f(client)))
            case Result.Failure(e) =>
                Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                Abort.error(Result.Panic(t))
        }

    /** A procedure call leaves the connection at a statement boundary, so the next caller to borrow it gets its own answer.
      *
      * Engine-specific because only this engine can create the situation: a `CALL` here answers with the procedure's result set AND a
      * trailing status packet for the call itself, while the other engine's `CALL` cannot return a result set at all. The property under
      * test is not engine-specific at all though, and it is the most expensive one in the driver: a connection returned to the pool with
      * unread packets on it hands the NEXT borrower the previous caller's rows. Cross-request data leakage, no error anywhere.
      *
      * `maxConnections = 1` is what makes the leaf mean something. With a larger pool the second query could be answered by a different,
      * clean connection and the leaf would pass without ever exercising the case.
      *
      * Both halves are asserted. That the call answers its own rows proves the drain does not eat the caller's result, and that the next
      * query answers 42 proves nothing was left behind for it to trip over.
      */
    "a procedure call leaves the connection usable for the next statement" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                Abort.run[SqlConnectionException](MysqlClient.init(myUrl(ctx), SqlConfig(maxConnections = 1))).flatMap {
                    case Result.Success(client) =>
                        Scope.ensure(client.close).andThen(DB.run(client) {
                            for
                                _      <- client.executeRaw("CREATE PROCEDURE pick() BEGIN SELECT 7 AS v; END")
                                called <- client.query("CALL pick()")
                                picked <- called.head.decode[Int]("v")
                                // The same connection, because the pool holds exactly one.
                                after  <- client.query("SELECT 42 AS answer")
                                answer <- after.head.decode[Int]("answer")
                            yield
                                assert(picked == 7, s"the call must answer its own row, got $picked")
                                assert(
                                    answer == 42,
                                    s"the next statement on this connection must get its own answer, got $answer: " +
                                        "a leftover result set means this connection would serve one caller's rows to another"
                                )
                            end for
                        })
                    case Result.Failure(e) => Abort.fail(e: SqlException)
                    case Result.Panic(t)   => Abort.error(Result.Panic(t))
                }
            }
        }
    }

    // ── Wire forms the decoders model rather than observe ──────────────────────
    //
    // Both leaves run on a single-connection pool: the zero-date one depends on a SESSION `sql_mode`, and a
    // default pool can serve the `SET` and the `INSERT` from different connections.

    private val singleConnection = SqlConfig.default.copy(maxConnections = 1, minConnections = 1)

    "a native TIME column round-trips through SqlSchema[LocalTime]" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                SqlClient.initWith(myUrl(ctx), singleConnection) { client =>
                    // TIME(6) because the default TIME(0) truncates the fractional part, and the binary
                    // struct's 12-byte form is the one that carries microseconds.
                    val value = java.time.LocalTime.of(13, 45, 30, 123456000)
                    for
                        _       <- client.executeRaw("CREATE TABLE clock_rt (id INT PRIMARY KEY, at TIME(6) NOT NULL)")
                        _       <- client.execute(sql"INSERT INTO clock_rt (id, at) VALUES (1, $value)")
                        rows    <- client.query(sql"SELECT at FROM clock_rt WHERE id = 1")
                        decoded <- rows.head.decode[java.time.LocalTime]
                    yield assert(decoded.equals(value), s"TIME round-trip gave $decoded, expected $value")
                    end for
                }
            }
        }
    }

    "a TIME column beyond a day is refused as a LocalTime and carried as a Duration" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                SqlClient.initWith(myUrl(ctx), singleConnection) { client =>
                    // MySQL TIME spans -838:59:59 to 838:59:59. The binary struct splits an out-of-day value
                    // across its `days` and `hours` fields and carries the sign in `is_negative`, so a LocalTime
                    // decoder that read past both would hand back 10:30:00 for -10:30:00.
                    for
                        _         <- client.executeRaw("CREATE TABLE span_rt (id INT PRIMARY KEY, d TIME NOT NULL)")
                        _         <- client.executeRaw("INSERT INTO span_rt VALUES (1, '-10:30:00'), (2, '100:00:00')")
                        rows      <- client.query(sql"SELECT d FROM span_rt ORDER BY id")
                        negAsDur  <- rows.head.decode[java.time.Duration]
                        bigAsDur  <- rows(1).decode[java.time.Duration]
                        negAsTime <- Abort.run[SqlDecodeException](rows.head.decode[java.time.LocalTime])
                        bigAsTime <- Abort.run[SqlDecodeException](rows(1).decode[java.time.LocalTime])
                    yield
                        assert(
                            negAsDur.equals(java.time.Duration.ofHours(-10).minusMinutes(30)),
                            s"a negative TIME must keep its sign as a Duration, got $negAsDur"
                        )
                        assert(
                            bigAsDur.equals(java.time.Duration.ofHours(100)),
                            s"an out-of-day TIME must keep its hours as a Duration, got $bigAsDur"
                        )
                        assert(negAsTime.isFailure, s"a negative TIME is not a time of day, got $negAsTime")
                        assert(bigAsTime.isFailure, s"a 100-hour TIME is not a time of day, got $bigAsTime")
                    end for
                }
            }
        }
    }

    "a zero date is refused rather than decoded as a year the column does not hold" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                SqlClient.initWith(myUrl(ctx), singleConnection) { client =>
                    for
                        // MySQL 8's default sql_mode carries NO_ZERO_DATE and NO_ZERO_IN_DATE, which reject
                        // the INSERT outright. Relaxing it is what lets a table hold the value a user can
                        // then read, and it is per-session, hence the single-connection pool above.
                        _       <- client.executeRaw("SET SESSION sql_mode = ''")
                        _       <- client.executeRaw("CREATE TABLE zd_rt (id INT PRIMARY KEY, d DATE NOT NULL)")
                        _       <- client.executeRaw("INSERT INTO zd_rt VALUES (1, '0000-00-00'), (2, '2024-00-15')")
                        rows    <- client.query(sql"SELECT d FROM zd_rt ORDER BY id")
                        allZero <- Abort.run[SqlDecodeException](rows.head.decode[java.time.LocalDate])
                        partial <- Abort.run[SqlDecodeException](rows(1).decode[java.time.LocalDate])
                    yield
                        allZero match
                            case Result.Failure(_: SqlDecodeTemporalException) => succeed
                            case other => fail(s"0000-00-00 must be refused rather than decoded, got $other")
                        partial match
                            case Result.Failure(e: SqlDecodeTemporalException) =>
                                // The partial form is the worse half: substituting a 1 for the zero month decodes
                                // to a real-looking 2024-01-15, so the refusal has to name the zero component it
                                // saw.
                                assert(e.month == 0, s"the refusal must report month 0, got ${e.month}")
                            case other => fail(s"2024-00-15 must be refused rather than rewritten, got $other")
                        end match
                    end for
                }
            }
        }
    }

end SqlMysqlOnlyTest
