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

    // ── ilike on MySQL uses LOWER(x) LIKE LOWER(p) emulation ──────────────────

    "Leaf 17: ilike on MySQL returns expected rows using LOWER(…) LIKE LOWER(…)" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw("INSERT INTO person VALUES (1, 'Alice', 30), (2, 'BOB', 25), (3, 'carol', 28)")
                        // Verify rendering uses LOWER(…) LIKE LOWER(…) on MySQL
                        _ =
                            val rendered = Sql.from[Person]("p").where(c => c.p.name.ilike("alice%")).select(c => c.p.name)
                                .render(MysqlDialect)
                            assert(
                                rendered.onlySql.get.contains("LOWER"),
                                s"Expected LOWER in MySQL ilike SQL, got: ${rendered.onlySql.get}"
                            )
                        // Execute against live MySQL
                        rows <- Sql
                            .from[Person]("p")
                            .where(c => c.p.name.ilike("alice%"))
                            .select(c => c.p.name)
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 ilike match for 'alice%', got: ${rows.size}")
                        assert(rows.head == "Alice", s"Expected 'Alice', got: '${rows.head}'")
                }
            }
        }
    }

    // ── ++ concat on MySQL uses CONCAT(…) ─────────────────────────────────────

    "Leaf 19: ++ concat on MySQL returns expected concatenated string using CONCAT" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                        // Verify the rendered SQL uses CONCAT for concat on MySQL
                        _ =
                            val rendered = Sql.from[Person]("p").select(c => c.p.name ++ " rocks")
                                .render(MysqlDialect)
                            assert(
                                rendered.onlySql.get.contains("CONCAT"),
                                s"Expected CONCAT in MySQL concat SQL, got: ${rendered.onlySql.get}"
                            )
                        // Execute the concat query against live MySQL
                        rows <- Sql
                            .from[Person]("p")
                            .where(c => c.p.id == 1L)
                            .select(c => c.p.name ++ " rocks")
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 row, got: ${rows.size}")
                        assert(rows.head == "alice rocks", s"Expected 'alice rocks', got: '${rows.head}'")
                }
            }
        }
    }

    // ── onConflictDoNothing is idempotent on MySQL (INSERT IGNORE) ────────────

    "Leaf 21: onConflictDoNothing is idempotent on MySQL via INSERT IGNORE" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                        // Verify the rendered SQL uses INSERT IGNORE on MySQL
                        _ =
                            val rendered = Sql.insert[Person].values(Person(1L, "alice-dup", 99)).onConflictDoNothing()
                                .render(MysqlDialect)
                            assert(
                                rendered.onlySql.get.contains("INSERT IGNORE"),
                                s"Expected INSERT IGNORE in MySQL onConflictDoNothing SQL, got: ${rendered.onlySql.get}"
                            )
                        // Insert duplicate with onConflictDoNothing
                        _ <- Sql
                            .insert[Person]
                            .values(Person(1L, "alice-duplicate", 99))
                            .onConflictDoNothing()
                            .run
                        rows <- Sql.from[Person]("p").run
                    yield
                        assert(rows.size == 1, s"Expected 1 row after idempotent INSERT IGNORE, got: ${rows.size}")
                        assert(rows.head == Person(1L, "alice", 30), s"Expected original Person(1,alice,30), got: ${rows.head}")
                }
            }
        }
    }

    // ── onConflictDoUpdate updates existing row on MySQL ──────────────────────

    "Leaf 23: onConflictDoUpdate updates existing row on MySQL via ON DUPLICATE KEY UPDATE" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                        // Verify the rendered SQL uses ON DUPLICATE KEY UPDATE on MySQL
                        _ =
                            val rendered = Sql.insert[Person]
                                .values(Person(1L, "alice-upserted", 31))
                                .onConflictDoUpdate(_.id)(c => c.age := Sql.Excluded(c.age))
                                .render(MysqlDialect)
                            assert(
                                rendered.onlySql.get.contains("ON DUPLICATE KEY UPDATE"),
                                s"Expected ON DUPLICATE KEY UPDATE in MySQL upsert SQL, got: ${rendered.onlySql.get}"
                            )
                        // Execute the upsert against live MySQL
                        _ <- Sql
                            .insert[Person]
                            .values(Person(1L, "alice-upserted", 31))
                            .onConflictDoUpdate(_.id)(c => c.age := Sql.Excluded(c.age))
                            .run
                        rows <- Sql.from[Person]("p").run
                    yield
                        assert(rows.size == 1, s"Expected 1 row after upsert, got: ${rows.size}")
                        assert(rows.head.age == 31, s"Expected updated age 31, got: ${rows.head.age}")
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
