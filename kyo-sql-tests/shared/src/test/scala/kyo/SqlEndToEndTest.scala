package kyo

import kyo.Sql.*
import kyo.SqlConnectionException
import kyo.SqlServerException
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend

/** End-to-end integration tests against real PG + MySQL containers.
  *
  * Covers the full pipeline: DSL → AST → Idiom → SqlClient.internalExecute* → Connection → real DB → assertion.
  *
  * SqlClient.InsertOutcome contract (SqlClient.InsertOutcome.scala):
  *   - affectedRows: Long, row count from CommandComplete / OK packet
  *   - generatedKey: SqlClient.InsertOutcome.GeneratedKey, Value(id) when an auto-key was detected and the server reported one, NoAutoKey
  *     when the renderer emitted no RETURNING because the table has no such column, and Unavailable when the server's answer cannot tell
  *     those two apart.
  * Auto-key detection: case class whose FIRST field is Long-typed. PG: auto-appends RETURNING <pk>; MySQL: reads last_insert_id from OK
  * packet.
  *
  * Every live assertion here runs through .run or .runDynamic. Which SQL each flavor renders is asserted separately, in each dialect's own
  * render suites.
  */
class SqlEndToEndTest extends SqlContainerTest:

    override def timeout: Duration = 5.minutes

    // ── Shared case classes ────────────────────────────────────────────────────

    case class Person(id: Long, name: String, age: Int) derives SqlSchema, CanEqual
    case class Dept(id: Long, name: String) derives SqlSchema, CanEqual
    case class Tag(name: String) derives SqlSchema, CanEqual

    // ── Helper: build a SqlClient from a SchemaCtx ────────────────────────────

    private def pgUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def withPgClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(f: SqlClient => A < (S & Async & Abort[SqlException] & DB))(using
        Frame
    ): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlConnectionException](SqlClient.init(pgUrl(ctx))).flatMap {
            case Result.Success(client) =>
                Scope.ensure(client.close).andThen(DB.run(client)(f(client)))
            case Result.Failure(e) =>
                Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                Abort.error(Result.Panic(t))
        }

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

    // ── Leaf 1: SELECT + WHERE round-trip on PG ───────────────────────────────

    "Leaf 1: SELECT + WHERE round-trip on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 25)""")
                        rows <- Sql
                            .from[Person]("p")
                            .where(c => c.p.age >= 30)
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 row, got: ${rows.size}")
                        assert(rows.head == Person(1L, "alice", 30), s"Expected Person(1,alice,30), got: ${rows.head}")
                }
            }
        }
    }

    // ── Leaf 2: SELECT + WHERE round-trip on MySQL ────────────────────────────

    "Leaf 2: SELECT + WHERE round-trip on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 25)")
                        rows <- Sql
                            .from[Person]("p")
                            .where(c => c.p.age >= 30)
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 row, got: ${rows.size}")
                        assert(rows.head == Person(1L, "alice", 30), s"Expected Person(1,alice,30), got: ${rows.head}")
                }
            }
        }
    }

    // ── Leaf 3: JOIN + SELECT round-trip on PG ────────────────────────────────

    "Leaf 3: JOIN + SELECT round-trip on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE dept (id BIGINT PRIMARY KEY, name TEXT NOT NULL)"""
                        )
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- client.executeRaw("""INSERT INTO dept VALUES (1, 'engineering')""")
                        _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                        // 2-column join projection: (person.name, dept.name). INNER JOIN means only
                        // alice (id=1) matches dept id=1. Decoded positionally into (String, String).
                        rows <- Sql
                            .from[Person]("p")
                            .innerJoin(Sql.from[Dept]("d"))
                            .on(j => j.p.id == j.d.id)
                            .select(j => (j.p.name, j.d.name))
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 join row (person.id=1 matches dept.id=1), got: ${rows.size}")
                        assert(rows.head == ("alice", "engineering"), s"Expected (alice,engineering), got: ${rows.head}")
                }
            }
        }
    }

    // ── Leaf 4: JOIN + SELECT round-trip on MySQL ─────────────────────────────

    "Leaf 4: JOIN + SELECT round-trip on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE dept (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL)"
                        )
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw("INSERT INTO dept VALUES (1, 'engineering')")
                        _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                        // 2-column join projection: (person.name, dept.name). INNER JOIN means only
                        // alice (id=1) matches dept id=1. Decoded positionally into (String, String).
                        rows <- Sql
                            .from[Person]("p")
                            .innerJoin(Sql.from[Dept]("d"))
                            .on(j => j.p.id == j.d.id)
                            .select(j => (j.p.name, j.d.name))
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 join row, got: ${rows.size}")
                        assert(rows.head == ("alice", "engineering"), s"Expected (alice,engineering), got: ${rows.head}")
                }
            }
        }
    }

    // ── Leaf 5: GROUP BY + HAVING on PG ──────────────────────────────────────

    "Leaf 5: GROUP BY + HAVING round-trip on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- client.executeRaw(
                            """INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 30), (3, 'carol', 25)"""
                        )
                        // GROUP BY age HAVING COUNT(*) >= 2, DSL grouped view. COUNT(*) is int8/BIGINT
                        // on both engines, so it decodes as Long with no cast. Only age=30 (2 rows) qualifies.
                        rows <- Sql
                            .from[Person]("p")
                            .groupBy(_.p.age)
                            .having(view => view.age.count >= 2L)
                            .select(view => view.age.count)
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 group (age=30 has 2 rows), got: ${rows.size}")
                        assert(rows.head == 2L, s"Expected count 2 for the age=30 group, got: ${rows.head}")
                }
            }
        }
    }

    // ── Leaf 6: GROUP BY + HAVING on MySQL ────────────────────────────────────

    "Leaf 6: GROUP BY + HAVING round-trip on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw(
                            "INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 30), (3, 'carol', 25)"
                        )
                        // GROUP BY age HAVING COUNT(*) >= 2, DSL grouped view. COUNT(*) is BIGINT
                        // on MySQL, so it decodes as Long with no cast. Only age=30 (2 rows) qualifies.
                        rows <- Sql
                            .from[Person]("p")
                            .groupBy(_.p.age)
                            .having(view => view.age.count >= 2L)
                            .select(view => view.age.count)
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 group (age=30 has 2 rows), got: ${rows.size}")
                        assert(rows.head == 2L, s"Expected count 2 for the age=30 group, got: ${rows.head}")
                }
            }
        }
    }

    // ── Leaf 7: CTE round-trip on PG (recursive) ─────────────────────────────

    "Leaf 7: CTE + recursive CTE round-trip on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { _ =>
                    // Recursive CTE: generate numbers 1..5, through the raw-SQL fragment a caller writes.
                    // The seed is cast to BIGINT so the Long column codec (an int8 reader) decodes it.
                    val limit = 5L
                    sql"WITH RECURSIVE cte (n) AS (SELECT 1::BIGINT UNION ALL SELECT cte.n + 1 FROM cte WHERE cte.n < $limit) SELECT n FROM cte"
                        .as[Long]
                        .run
                        .map { rows =>
                            assert(rows.size == 5, s"Expected 5 rows from recursive CTE, got: ${rows.size}")
                            assert(rows.toSeq.sorted == Seq(1L, 2L, 3L, 4L, 5L), s"Expected 1..5, got: $rows")
                        }
                }
            }
        }
    }

    // ── Leaf 8: CTE round-trip on MySQL (non-recursive) ──────────────────────

    "Leaf 8: CTE round-trip on MySQL (non-recursive)" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { _ =>
                    // Non-recursive CTE on MySQL 8+, through the raw-SQL fragment a caller writes. Each bind is
                    // an interpolated argument, so the fragment carries three parameters in the order written.
                    val (first, second, third) = (1L, 2L, 3L)
                    sql"WITH vals (n) AS (SELECT $first UNION ALL SELECT $second UNION ALL SELECT $third) SELECT n FROM vals"
                        .as[Long]
                        .run
                        .map { rows =>
                            assert(rows.size == 3, s"Expected 3 rows from CTE, got: ${rows.size}")
                            assert(rows.toSeq.sorted == Seq(1L, 2L, 3L), s"Expected 1,2,3, got: $rows")
                        }
                }
            }
        }
    }

    // ── Leaves 9-11: SqlClient.InsertOutcome ─────────────────────────────────────────────
    //
    // SqlClient.InsertOutcome contract for auto-key INSERTs:
    //   - affectedRows: Long
    //   - generatedKey: SqlClient.InsertOutcome.GeneratedKey, Value(id) when the first field is Long AND the
    //     server reports a key. PG auto-appends RETURNING <pk>; MySQL reads last_insert_id from the OK packet.

    "Leaf 9: INSERT with auto-key returns SqlClient.InsertOutcome.generatedKey = Present on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    // Person(id: Long, ...), first field is Long → autoKey detection fires.
                    // PG renderer auto-appends RETURNING "id". We insert with an explicit id so the
                    // RETURNING value is deterministic: Person(42L, ...) → generatedKey == Present(42L).
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        result <- Sql
                            .insert[Person]
                            .values(Person(42L, "alice", 30))
                            .run
                    yield
                        assert(result.affectedRows == 1L, s"Expected 1 affected row, got: ${result.affectedRows}")
                        assert(
                            SqlClient.InsertOutcome.GeneratedKey.isPresent(result.generatedKey),
                            s"Expected Value generatedKey for PG auto-RETURNING, got: ${result.generatedKey}"
                        )
                        assert(
                            SqlClient.InsertOutcome.GeneratedKey.foldKey(result.generatedKey)(-1L)(identity) == 42L,
                            s"Expected generatedKey == Value(42L), got: ${result.generatedKey}"
                        )
                }
            }
        }
    }

    // client.executeInsert is the non-macro way to reach the same outcome: `execute` discards the generated key
    // because its return type has no slot for one, so an Insert AST in hand needs this entry to keep it.
    "client.executeInsert reports the same outcome as the .run path for the same Insert" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        result <- client.executeInsert(Sql.insert[Person].values(Person(43L, "bob", 25)))
                    yield
                        assert(result.affectedRows == 1L, s"Expected 1 affected row, got: ${result.affectedRows}")
                        assert(
                            SqlClient.InsertOutcome.GeneratedKey.foldKey(result.generatedKey)(-1L)(identity) == 43L,
                            s"Expected generatedKey == Value(43L), got: ${result.generatedKey}"
                        )
                }
            }
        }
    }

    "Leaf 10: INSERT with auto-key returns SqlClient.InsertOutcome.generatedKey = Present on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        result <- Sql
                            .insert[Person]
                            .values(Person(0L, "alice", 30))
                            .run
                    yield
                        assert(result.affectedRows == 1L, s"Expected 1 affected row, got: ${result.affectedRows}")
                        assert(
                            SqlClient.InsertOutcome.GeneratedKey.isPresent(result.generatedKey),
                            s"Expected Value generatedKey for MySQL AUTO_INCREMENT, got: ${result.generatedKey}"
                        )
                        assert(
                            SqlClient.InsertOutcome.GeneratedKey.foldKey(result.generatedKey)(false)(_ > 0L),
                            s"Expected positive generated key, got: ${result.generatedKey}"
                        )
                }
            }
        }
    }

    "Leaf 11: INSERT without auto-key column returns SqlClient.InsertOutcome.generatedKey = Absent" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    // Tag(name: String), first field is String → no auto-key detection.
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE tag (name TEXT PRIMARY KEY)"""
                        )
                        result <- Sql
                            .insert[Tag]
                            .values(Tag("urgent"))
                            .run
                    yield
                        assert(result.affectedRows == 1L, s"Expected 1 affected row, got: ${result.affectedRows}")
                        assert(
                            result.generatedKey == SqlClient.InsertOutcome.GeneratedKey.NoAutoKey,
                            s"Expected NoAutoKey generatedKey for non-auto-key table, got: ${result.generatedKey}"
                        )
                }
            }
        }
    }

    // ── Leaf 12: UPDATE affected-row count on PG ──────────────────────────────

    "Leaf 12: UPDATE returns correct affected-row count on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 30), (3, 'carol', 25)""")
                        count <- Sql
                            .update[Person]
                            .set(_.age := 31)
                            .where(_.age == 30)
                            .run
                    yield assert(count == 2L, s"Expected 2 updated rows (age=30), got: $count")
                }
            }
        }
    }

    // ── Leaf 13: UPDATE affected-row count on MySQL ───────────────────────────

    "Leaf 13: UPDATE returns correct affected-row count on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 30), (3, 'carol', 25)")
                        count <- Sql
                            .update[Person]
                            .set(_.age := 31)
                            .where(_.age == 30)
                            .run
                    yield assert(count == 2L, s"Expected 2 updated rows (age=30), got: $count")
                }
            }
        }
    }

    // ── Leaf 14: DELETE affected-row count on PG ──────────────────────────────

    "Leaf 14: DELETE returns correct affected-row count on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 25)""")
                        count <- Sql
                            .delete[Person]
                            .where(_.age == 25)
                            .run
                    yield assert(count == 1L, s"Expected 1 deleted row, got: $count")
                }
            }
        }
    }

    // ── Leaf 15: DELETE affected-row count on MySQL ───────────────────────────

    "Leaf 15: DELETE returns correct affected-row count on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 25)")
                        count <- Sql
                            .delete[Person]
                            .where(_.age == 25)
                            .run
                    yield assert(count == 1L, s"Expected 1 deleted row, got: $count")
                }
            }
        }
    }

    // ── Transaction rollback leaves the table unchanged, one leaf per backend ──

    "Leaf 24a: transaction rollback leaves table unchanged on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        // Run a transaction that inserts then aborts, should roll back.
                        txResult <- Abort.run[SqlException](
                            client.transaction {
                                Sql.insert[Person]
                                    .values(Person(1L, "alice", 30))
                                    .run
                                    .flatMap { _ =>
                                        Abort.fail[SqlException](SqlServerException("XX000", "ERROR", "intentional rollback"))
                                    }
                            }
                        )
                        _ = assert(txResult.isFailure, s"Expected transaction failure (rollback), got: $txResult")
                        // Table must be empty, rollback removed the inserted row.
                        rows <- Sql.from[Person]("p").run
                    yield assert(rows.isEmpty, s"Expected empty table after rollback, got: $rows")
                }
            }
        }
    }

    "Leaf 24b: transaction rollback leaves table unchanged on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        txResult <- Abort.run[SqlException](
                            client.transaction {
                                Sql.insert[Person]
                                    .values(Person(1L, "alice", 30))
                                    .run
                                    .flatMap { _ =>
                                        Abort.fail[SqlException](SqlServerException("XX000", "ERROR", "intentional rollback"))
                                    }
                            }
                        )
                        _ = assert(txResult.isFailure, s"Expected transaction failure (rollback), got: $txResult")
                        rows <- Sql.from[Person]("p").run
                    yield assert(rows.isEmpty, s"Expected empty table after rollback, got: $rows")
                }
            }
        }
    }

    // ── Leaf 25: sql"..." raw interpolator round-trip on both backends ─────────
    // The sql"..." interpolator returns Fragment[Any] (a Term[Any]) that can be
    // embedded in .where() / .select() predicates. We embed it in a WHERE predicate
    // and execute against a live DB to verify the full interpolation pipeline.

    "Leaf 25a: sql raw interpolator round-trip on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    val minAge = 26
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 25)""")
                        // sql"..." interpolator embeds column reference + bound literal in WHERE
                        rows <- Sql
                            .from[Person]("p")
                            .where(c => sql"${c.p.age} >= $minAge".as[Boolean])
                            .select(c => c.p.name)
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 row (age >= 26), got: ${rows.size}")
                        assert(rows.head == "alice", s"Expected alice, got: ${rows.head}")
                    end for
                }
            }
        }
    }

    "Leaf 25b: sql raw interpolator round-trip on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    val minAge = 26
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30), (2, 'bob', 25)")
                        rows <- Sql
                            .from[Person]("p")
                            .where(c => sql"${c.p.age} >= $minAge".as[Boolean])
                            .select(c => c.p.name)
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 row (age >= 26), got: ${rows.size}")
                        assert(rows.head == "alice", s"Expected alice, got: ${rows.head}")
                    end for
                }
            }
        }
    }

    // ── SqlClient factory-chain and close-triad ────────────────────────────────

    "initWith(url)(f) creates a client, runs f, registers Scope cleanup" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                // initWith registers Scope.ensure(close); query succeeds inside `f`.
                SqlClient.initWith(pgUrl(ctx)) { client =>
                    DB.run(client) {
                        client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        ).andThen {
                            client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                                .map(n => assert(n == 1L, s"Expected 1 affected row, got $n"))
                        }
                    }
                }
            }
        }
    }

    "Scope.run(initWith(url)(f)) gives bracket semantics, with no Scope in the effect set" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                // initWith binds close to the enclosing Scope; running that Scope inline discharges it, so the
                // ascription below (no Scope in the row) is the assertion: close is still guaranteed, and the
                // caller inherits no Scope requirement.
                val bracketed: Unit < (Async & Abort[SqlException]) =
                    Scope.run {
                        SqlClient.initWith(pgUrl(ctx)) { client =>
                            client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            ).andThen {
                                client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                                    .map(n => assert(n == 1L, s"Expected 1 affected row, got $n"))
                            }
                        }
                    }
                bracketed
            }
        }
    }

    "initUnscoped(url) creates a client with no cleanup; manual close completes without error" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                // initUnscoped leaves cleanup to the caller; close the client manually.
                SqlClient.initUnscoped(pgUrl(ctx)).flatMap { client =>
                    DB.run(client) {
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            )
                            _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                            // The write is read back before the manual close: an unscoped client that accepts a
                            // statement and never serves the row back is not a working client.
                            rows <- Sql.from[Person]("p").run
                            _    <- client.close
                        yield
                            assert(rows.size == 1, s"Expected 1 row, got: ${rows.size}")
                            assert(rows.head == Person(1L, "alice", 30), s"Expected Person(1,alice,30), got: ${rows.head}")
                    }
                }
            }
        }
    }

    "close(gracePeriod), close, and closeNow all complete without error" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                for
                    // Three independent clients, one per close variant.
                    // close(30.seconds) on an idle client must complete in < 5 seconds
                    // (grace period is "up to", not "exactly").
                    c1 <- SqlClient.initUnscoped(pgUrl(ctx))
                    t1 <- Clock.nowMonotonic
                    _  <- c1.close(30.seconds)
                    e1 <- Clock.nowMonotonic
                    elapsed1 = e1 - t1
                    _        = assert(elapsed1 < 5.seconds, s"close(30.seconds) on idle client took $elapsed1, expected < 5.seconds")
                    // close (default 30s) on an idle client must complete in < 5 seconds.
                    c2 <- SqlClient.initUnscoped(pgUrl(ctx))
                    t2 <- Clock.nowMonotonic
                    _  <- c2.close
                    e2 <- Clock.nowMonotonic
                    elapsed2 = e2 - t2
                    _        = assert(elapsed2 < 5.seconds, s"close on idle client took $elapsed2, expected < 5.seconds")
                    // closeNow (Duration.Zero) on an idle client must complete in < 1 second.
                    c3 <- SqlClient.initUnscoped(pgUrl(ctx))
                    t3 <- Clock.nowMonotonic
                    _  <- c3.closeNow
                    e3 <- Clock.nowMonotonic
                    elapsed3 = e3 - t3
                    _        = assert(elapsed3 < 1.seconds, s"closeNow on idle client took $elapsed3, expected < 1.seconds")
                yield succeed
            }
        }
    }

    // ── INSERT/SELECT a Duration column on PG (container-gated) ─────────────────

    "INSERT/SELECT a java.time.Duration round-trips through PG INTERVAL" in {
        case class Trip(id: Long, label: String, span: java.time.Duration) derives SqlSchema, CanEqual

        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE trip (id BIGINT PRIMARY KEY, label TEXT NOT NULL, span INTERVAL NOT NULL)"""
                        )
                        // Exercise the µs-only round-trip across boundary values: zero, sub-second precision,
                        // negative, and a span of several calendar days.
                        inputs = Seq(
                            Trip(1L, "zero", java.time.Duration.ZERO),
                            Trip(2L, "1h", java.time.Duration.ofHours(1)),
                            Trip(3L, "1h1m1.5s", java.time.Duration.ofSeconds(3661, 500_000_000L)),
                            Trip(4L, "neg-30s", java.time.Duration.ofSeconds(-30)),
                            Trip(5L, "1d2h", java.time.Duration.ofDays(1).plusHours(2))
                        )
                        _    <- Kyo.foreachDiscard(inputs)(t => Sql.insert[Trip].values(t).run)
                        rows <- Sql.from[Trip]("t").orderBy(_.t.id.asc).run
                    yield
                        assert(rows.size == inputs.size, s"expected ${inputs.size} rows, got ${rows.size}")
                        inputs.zip(rows).foreach { case (expected, actual) =>
                            assert(actual.id == expected.id, s"id mismatch: ${actual.id} vs ${expected.id}")
                            assert(actual.label == expected.label, s"label mismatch: ${actual.label}")
                            assert(
                                actual.span.equals(expected.span),
                                s"span round-trip mismatch for ${expected.label}: ${actual.span} vs ${expected.span}"
                            )
                        }
                        succeed
                }
            }
        }
    }

    // ── A narrow Scala type over a wide column, end to end on both engines ────
    //
    // This suite's other count(*) sites either type the result Long (the DSL types `.count` that way) or cast to text
    // before decoding, so the leaves below are the ones that place a narrow Scala type over a wide column. That is the
    // mismatch a user writing raw SQL hits first: count(*) is int8 on PostgreSQL and BIGINT on MySQL, every
    // extended-protocol result column is requested in binary, and reading four big-endian bytes of an eight-byte value
    // returns its high word, which is 0 for every count under 2^32. Decoding count(*) into Int against a table with a
    // known row count is what makes that visible: it answers 0 where the table holds 7.

    "a count(*) decoded into Int returns the count on PG, not the high word of its int8" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _    <- client.executeRaw("""CREATE TABLE counted (id BIGINT PRIMARY KEY)""")
                        _    <- client.executeRaw("""INSERT INTO counted VALUES (1), (2), (3), (4), (5), (6), (7)""")
                        rows <- client.query("SELECT count(*) FROM counted")
                        n    <- rows.head.decode[Int]
                    yield assert(n == 7, s"count(*) over 7 rows must decode into Int as 7, got $n")
                }
            }
        }
    }

    "a count(*) decoded into Int returns the count on MySQL, and a BIGINT id fits Int" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _       <- client.executeRaw("""CREATE TABLE counted (id BIGINT PRIMARY KEY)""")
                        _       <- client.executeRaw("""INSERT INTO counted VALUES (1), (2), (3), (4), (5), (6), (7)""")
                        rows    <- client.query("SELECT count(*) FROM counted")
                        n       <- rows.head.decode[Int]
                        ids     <- client.query("SELECT id FROM counted ORDER BY id")
                        firstId <- ids.head.decode[Int]
                    yield
                        assert(n == 7, s"count(*) over 7 rows must decode into Int as 7, got $n")
                        assert(firstId == 1, s"a BIGINT id of 1 must decode into Int as 1, got $firstId")
                }
            }
        }
    }

    "a MySQL simpleQuery row decodes its text values, which share no representation with the binary protocol" in {
        // simpleQuery is public and documented for one-off SQL, and its rows are Format.Text: every value is its ASCII
        // rendering. The digits of 1234 parsed as a little-endian LONG are 875770417, and the ASCII 0 of a false boolean
        // is the nonzero byte 0x30.
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        numeric <- client.simpleQuery("SELECT 1234")
                        n       <- numeric.head.decode[Int]
                        falsy   <- client.simpleQuery("SELECT 0")
                        f       <- falsy.head.decode[Boolean]
                        truthy  <- client.simpleQuery("SELECT 1")
                        t       <- truthy.head.decode[Boolean]
                    yield
                        assert(n == 1234, s"a text-protocol 1234 must decode as 1234, got $n")
                        assert(!f, "a text-protocol 0 must decode as false")
                        assert(t, "a text-protocol 1 must decode as true")
                }
            }
        }
    }

    "a generated key comes back as the server's own value on PG, and an int4 RETURNING payload widens into it" in {
        // Two properties in one leaf, because both need a SERIAL pk and neither is observable without a live server.
        //
        // The generated key: `values(row)` sends every column, so the row's own key travels unless the caller says
        // otherwise, and PostgreSQL accepts an explicit value in a SERIAL column without advancing the sequence. An
        // insert that sent the row's 5 would therefore land a 5, report 5 back, and collide with itself on the second
        // insert. `.overriding(_.id := Sql.default)` is the documented way to ask the server instead, and the keys
        // asserted here are 1 and 2: the row's 5 and 6 never reach the server, and the sequence advances.
        //
        // The widening: the auto-key rule picks the column from the SCALA type (first field Long) and nothing constrains
        // the DDL, so a `SERIAL` pk hands the generated-key decode a four-byte int4 where it expects eight. That read
        // sits outside the typed decode boundary, so a width mismatch there surfaces as a panic rather than an Abort.
        // SERIAL is the commoner spelling, which is why it is the one under test.
        case class Widget(id: Long, label: String) derives SqlSchema, CanEqual

        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _      <- client.executeRaw("""CREATE TABLE widget (id SERIAL PRIMARY KEY, label TEXT NOT NULL)""")
                        first  <- Sql.insert[Widget].values(Widget(5L, "five")).overriding(_.id := Sql.default).run
                        second <- Sql.insert[Widget].values(Widget(6L, "six")).overriding(_.id := Sql.default).run
                        rows   <- client.query("SELECT id, label FROM widget ORDER BY id")
                    yield
                        assert(first.affectedRows == 1L, s"expected one inserted row, got ${first.affectedRows}")
                        assert(
                            first.generatedKey == SqlClient.InsertOutcome.GeneratedKey.Value(1L),
                            s"the server assigns the first key and an int4 RETURNING payload widens into the Long, got ${first.generatedKey}"
                        )
                        assert(
                            second.generatedKey == SqlClient.InsertOutcome.GeneratedKey.Value(2L),
                            s"the sequence must advance, so the second key is 2, got ${second.generatedKey}"
                        )
                        assert(rows.size == 2, s"both rows must be present, got ${rows.size}")
                }
            }
        }
    }

    "a generated key comes back as the server's own value on MySQL" in {
        // The same property on the other flavor, where the mechanism differs: MySQL has no RETURNING, so the key is read
        // off the OK packet. `DEFAULT` in the key's cell is what both flavors accept, which is what makes this the
        // documented spelling rather than the explicit 0 that happens to work only here.
        case class Widget(id: Long, label: String) derives SqlSchema, CanEqual

        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE widget (id BIGINT AUTO_INCREMENT PRIMARY KEY, label VARCHAR(64) NOT NULL)"
                        )
                        first  <- Sql.insert[Widget].values(Widget(5L, "five")).overriding(_.id := Sql.default).run
                        second <- Sql.insert[Widget].values(Widget(6L, "six")).overriding(_.id := Sql.default).run
                    yield
                        assert(first.affectedRows == 1L, s"expected one inserted row, got ${first.affectedRows}")
                        assert(
                            first.generatedKey == SqlClient.InsertOutcome.GeneratedKey.Value(1L),
                            s"the server assigns the first key, not the row's 5, got ${first.generatedKey}"
                        )
                        assert(
                            second.generatedKey == SqlClient.InsertOutcome.GeneratedKey.Value(2L),
                            s"the auto-increment must advance, so the second key is 2, got ${second.generatedKey}"
                        )
                }
            }
        }
    }

    // ── A VALUES source is a query both servers can execute ───────────────────
    //
    // A rendered-text assertion cannot see whether this entry point produces executable SQL: a VALUES list names its own
    // columns (`column1` on PostgreSQL, `column_0` on MySQL), so a projection of `"v"."x"` above it resolves against
    // nothing unless the alias renames them. These two leaves run the query and read the values back.

    "a query over a VALUES source returns its rows on PG" in {
        case class Point(x: Int, y: Int) derives SqlSchema, CanEqual

        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    Sql.values[Point]("v", Point(1, 2), Point(3, 4)).run.map { rows =>
                        assert(rows.size == 2, s"a two-row VALUES source must return two rows, got ${rows.size}")
                        assert(rows.head == Point(1, 2), s"expected Point(1,2), got ${rows.head}")
                        assert(rows(1) == Point(3, 4), s"expected Point(3,4), got ${rows(1)}")
                    }
                }
            }
        }
    }

    "a query over a VALUES source returns its rows on MySQL" in {
        case class Point(x: Int, y: Int) derives SqlSchema, CanEqual

        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    Sql.values[Point]("v", Point(1, 2), Point(3, 4)).run.map { rows =>
                        assert(rows.size == 2, s"a two-row VALUES source must return two rows, got ${rows.size}")
                        assert(rows.head == Point(1, 2), s"expected Point(1,2), got ${rows.head}")
                        assert(rows(1) == Point(3, 4), s"expected Point(3,4), got ${rows(1)}")
                    }
                }
            }
        }
    }

    // ── Leaf 26: a String value carrying SQL metacharacters round-trips byte-for-byte ────
    //
    // An INSERT path that wrote String cells into the statement text with `'` doubling as their only transformation
    // would be unsafe on MySQL, whose default sql_mode treats a backslash as an escape: `\'` becomes `\''`, the
    // backslash escapes the first quote, the second closes the literal, and the rest of the value lands in statement
    // position. These two leaves assert on the value that comes back rather than on the statement succeeding, because
    // a succeeding statement is precisely that defect's failure mode.

    /** Values whose text is SQL syntax if it ever leaves the bind list.
      *
      * The second is the injection payload, shaped so that it lands rather than raising. It carries one quote (doubling cannot disarm it,
      * and MySQL's backslash escape is what closes the literal early), then completes the row the renderer had opened, then opens a second
      * complete row whose text value is a hex literal so the payload needs no quotes of its own, then comments out the renderer's own tail.
      * Interpolated into the statement text it parses as a valid two-row insert on MySQL, so the row count is what catches it.
      */
    private val metacharacterNames: Seq[String] = Seq(
        """o'brien""",
        """x\', 0), (999, 0x70776e6564, 1) -- """,
        """ends with a backslash \""",
        """two \\ backslashes""",
        """a "double" quote and a `backtick`"""
    )

    "Leaf 26a: String values carrying SQL metacharacters round-trip byte-for-byte on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- Kyo.foreachIndexed(metacharacterNames)((i, name) =>
                            Sql.insert[Person].values(Person(i.toLong, name, 30)).run
                        )
                        rows <- Sql.from[Person]("p").run
                    yield
                        // Row count first: an escaped literal injects extra rows, so a count over the inserted
                        // total is the injection itself rather than a corrupted value.
                        assert(
                            rows.size == metacharacterNames.size,
                            s"expected exactly ${metacharacterNames.size} rows, got ${rows.size}: ${rows.toSeq.map(_.name)}"
                        )
                        val byId = rows.toSeq.map(p => p.id -> p.name).toMap
                        metacharacterNames.zipWithIndex.foreach { (name, i) =>
                            assert(
                                byId.get(i.toLong).contains(name),
                                s"row $i must hold the value byte-for-byte, expected [$name], got [${byId.get(i.toLong)}]"
                            )
                        }
                        succeed
                }
            }
        }
    }

    "Leaf 26b: String values carrying SQL metacharacters round-trip byte-for-byte on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, age INT NOT NULL)"
                        )
                        _ <- Kyo.foreachIndexed(metacharacterNames)((i, name) =>
                            Sql.insert[Person].values(Person(i.toLong, name, 30)).run
                        )
                        rows <- Sql.from[Person]("p").run
                    yield
                        assert(
                            rows.size == metacharacterNames.size,
                            s"expected exactly ${metacharacterNames.size} rows, got ${rows.size}: ${rows.toSeq.map(_.name)}"
                        )
                        val byId = rows.toSeq.map(p => p.id -> p.name).toMap
                        metacharacterNames.zipWithIndex.foreach { (name, i) =>
                            assert(
                                byId.get(i.toLong).contains(name),
                                s"row $i must hold the value byte-for-byte, expected [$name], got [${byId.get(i.toLong)}]"
                            )
                        }
                        succeed
                }
            }
        }
    }

    // ── Leaf 27: a NULL in a Maybe field decodes, on both engines ─────────────
    //
    // `Maybe` is the DSL's only nullability vocabulary, and it is the one place a row read depends on the reader's
    // null contract: the nullable column reads as `if r.isNil() then Maybe.empty else Present(read(...))`, so a
    // reader answering `isNil` without consuming the null column would leave it in front of the next field's read
    // and shift every value after it. The mock readers pin the contract; these two leaves pin that both real
    // readers implement it.

    case class Contact(id: Long, name: String, email: Maybe[String]) derives SqlSchema, CanEqual

    "Leaf 27a: a NULL in a Maybe field decodes as Absent on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE contact (id BIGINT PRIMARY KEY, name TEXT NOT NULL, email TEXT)"""
                        )
                        _    <- client.executeRaw("""INSERT INTO contact VALUES (1, 'alice', NULL), (2, 'bob', 'bob@example.com')""")
                        rows <- Sql.from[Contact]("c").run
                    yield
                        assert(rows.size == 2, s"expected 2 rows, got ${rows.size}")
                        val byId = rows.toSeq.map(c => c.id -> c).toMap
                        assert(byId(1L) == Contact(1L, "alice", Absent), s"a NULL email must decode as Absent, got ${byId(1L)}")
                        assert(
                            byId(2L) == Contact(2L, "bob", Present("bob@example.com")),
                            s"a present email must decode as Present, got ${byId(2L)}"
                        )
                }
            }
        }
    }

    "Leaf 27b: a NULL in a Maybe field decodes as Absent on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE contact (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, email VARCHAR(128))"
                        )
                        _    <- client.executeRaw("INSERT INTO contact VALUES (1, 'alice', NULL), (2, 'bob', 'bob@example.com')")
                        rows <- Sql.from[Contact]("c").run
                    yield
                        assert(rows.size == 2, s"expected 2 rows, got ${rows.size}")
                        val byId = rows.toSeq.map(c => c.id -> c).toMap
                        assert(byId(1L) == Contact(1L, "alice", Absent), s"a NULL email must decode as Absent, got ${byId(1L)}")
                        assert(
                            byId(2L) == Contact(2L, "bob", Present("bob@example.com")),
                            s"a present email must decode as Present, got ${byId(2L)}"
                        )
                }
            }
        }
    }

    // ── Leaf 28: naming resolution round-trips, on both engines ───────────────
    //
    // The three naming suites assert rendered SQL only, so the read direction needs its own coverage: a row codec's
    // field names are the Scala names (post `@column`) while a cased query's server columns carry the resolved
    // ones, so the casing has to reach the decode or the read fails on a field the write direction filled happily.
    // One leaf per engine covers both mechanisms, the query-scoped `SqlNaming` and the per-field `@column`,
    // to keep it to one container each. The table name comes from the table-name parameter, since casing governs
    // columns only.

    case class UserProfile(id: Long, firstName: String, createdAt: Long) derives SqlSchema, CanEqual

    case class Alias(id: Long, @column("nick") nickname: String) derives SqlSchema, CanEqual

    "Leaf 28a: a cased query and a renamed field both round-trip on PG" in {
        given SqlNaming = SqlNaming.SnakeCase
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE user_profile (id BIGINT PRIMARY KEY, first_name TEXT NOT NULL, created_at BIGINT NOT NULL)"""
                        )
                        _        <- client.executeRaw("""CREATE TABLE alias (id BIGINT PRIMARY KEY, nick TEXT NOT NULL)""")
                        _        <- Sql.insert[UserProfile]("user_profile").values(UserProfile(1L, "ada", 1700000000L)).run
                        _        <- Sql.insert[Alias].values(Alias(1L, "countess")).run
                        profiles <- Sql.from[UserProfile]("u", "user_profile").run
                        aliases  <- Sql.from[Alias]("a").run
                    yield
                        assert(
                            profiles == Chunk(UserProfile(1L, "ada", 1700000000L)),
                            s"a cased query must read back what it wrote, got $profiles"
                        )
                        assert(aliases == Chunk(Alias(1L, "countess")), s"a renamed field must read back from its column, got $aliases")
                }
            }
        }
    }

    "Leaf 28b: a cased query and a renamed field both round-trip on MySQL" in {
        given SqlNaming = SqlNaming.SnakeCase
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE user_profile (id BIGINT PRIMARY KEY, first_name VARCHAR(128) NOT NULL, created_at BIGINT NOT NULL)"
                        )
                        _        <- client.executeRaw("CREATE TABLE alias (id BIGINT PRIMARY KEY, nick VARCHAR(128) NOT NULL)")
                        _        <- Sql.insert[UserProfile]("user_profile").values(UserProfile(1L, "ada", 1700000000L)).run
                        _        <- Sql.insert[Alias].values(Alias(1L, "countess")).run
                        profiles <- Sql.from[UserProfile]("u", "user_profile").run
                        aliases  <- Sql.from[Alias]("a").run
                    yield
                        assert(
                            profiles == Chunk(UserProfile(1L, "ada", 1700000000L)),
                            s"a cased query must read back what it wrote, got $profiles"
                        )
                        assert(aliases == Chunk(Alias(1L, "countess")), s"a renamed field must read back from its column, got $aliases")
                }
            }
        }
    }

    // ── Leaf 29: a projection into a case class, written the way a caller writes it ─
    //
    // No `.as` labels on the projected terms and a target whose field names deliberately differ from the projected
    // columns'. A decode that matched by name alone would make the labels mandatory and report a missing field that is
    // right there in the case class.

    case class Summary(fullName: String, years: Int) derives SqlSchema, CanEqual

    "Leaf 29: a tuple projection decodes into a case class whose field names differ from the columns" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _    <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                        rows <- Sql.from[Person]("p").select(c => (c.p.name, c.p.age)).to[Summary].run
                    yield assert(rows == Chunk(Summary("alice", 30)), s"expected one Summary(alice, 30), got $rows")
                }
            }
        }
    }

    // The property positional matching must not cost: a caller's own SELECT, whose column order is theirs and not the
    // schema's, still lands each value in its own field.
    "Leaf 30: raw SQL whose columns are in a different order than the case class still decodes by name" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _    <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                        rows <- client.query("SELECT age, name, id FROM person")
                        row  <- Abort.run[SqlDecodeException](rows.head.decode[Person]).map(_.getOrThrow)
                    yield assert(row == Person(1L, "alice", 30), s"columns out of schema order must still decode by name, got $row")
                }
            }
        }
    }

    // ── Leaf 31: aggregate and division VALUES, on both engines ───────────────
    //
    // These are the value assertions for `.sum` / `.avg` / `.min` / `.max`; a rendered-text assertion or a type-rejection
    // check cannot see the result type at all. `SUM` over an `INT` is an `int8` on PostgreSQL and a `DECIMAL` on MySQL,
    // so typing the aggregate as its operand reads a PostgreSQL total under 2^32 as its high word (zero) and a MySQL one
    // as four ASCII digits taken for a little-endian integer. `AVG` at the operand's type is a semantic error rather than
    // a width one, and an untyped `/` answers 3 on PostgreSQL where MySQL answers 3.5 from the same source line.
    //
    // One leaf per engine, covering the widths, the empty-input NULL, the two divisions, and the rollup key's NULL, so
    // the whole class costs one container per backend.

    case class Metric(id: Long, region: String, amount: Int, quantity: Long, divisor: Int) derives SqlSchema, CanEqual
    case class RegionTotal(region: Maybe[String], total: Long) derives SqlSchema, CanEqual

    private val metricRows = "(1, 'north', 10, 100, 4), (2, 'north', 20, 200, 8), (3, 'south', 30, 300, 3), (4, 'south', 41, 400, 2)"

    "Leaf 31a: aggregates, division, and a rollup key all return the values their types promise on PG" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    val metrics = Sql.from[Metric]("m")
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE metric (id BIGINT PRIMARY KEY, region TEXT NOT NULL, amount INT NOT NULL,
                                   quantity BIGINT NOT NULL, divisor INT NOT NULL)"""
                        )
                        _             <- client.executeRaw(s"INSERT INTO metric VALUES $metricRows")
                        amountTotal   <- metrics.sum(_.m.amount).run
                        amountAverage <- metrics.avg(_.m.amount).run
                        amountLow     <- metrics.min(_.m.amount).run
                        amountHigh    <- metrics.max(_.m.amount).run
                        quantityTotal <- metrics.sum(_.m.quantity).run
                        emptyTotal    <- metrics.where(c => c.m.amount > 1000).sum(_.m.amount).run
                        quotient      <- metrics.where(c => c.m.id == 1L).select(c => c.m.amount / c.m.divisor).run
                        truncated     <- metrics.where(c => c.m.id == 1L).select(c => c.m.amount.divideTruncating(c.m.divisor)).run
                        rolledUp <- metrics.groupByRollup(c => c.m.region).select(v => (v.region, v.amount.sum)).to[
                            RegionTotal
                        ].run
                    yield
                        // SUM over an INT column is an int8 on PG, so the total is a Long. At type Int the read would
                        // take the high word of the eight-byte value and answer 0.
                        assert(amountTotal.head == Present(101L), s"SUM(amount) must be 101, got ${amountTotal.head}")
                        // AVG is not the operand's type in any flavor: 101/4 is not an Int.
                        assert(
                            amountAverage.head == Present(BigDecimal("25.25")),
                            s"AVG(amount) must be 25.25, got ${amountAverage.head}"
                        )
                        assert(amountLow.head == Present(10), s"MIN(amount) must be 10, got ${amountLow.head}")
                        assert(amountHigh.head == Present(41), s"MAX(amount) must be 41, got ${amountHigh.head}")
                        // SUM over a BIGINT is a numeric on PG, which is the overflow headroom the widening exists for.
                        assert(
                            quantityTotal.head == Present(BigDecimal(1000)),
                            s"SUM(quantity) must be 1000, got ${quantityTotal.head}"
                        )
                        // A predicate matching nothing still returns one row, holding NULL.
                        assert(emptyTotal.head == Absent, s"SUM over no rows must be Absent, got ${emptyTotal.head}")
                        // 10 / 4 is 2.5 on both engines: PG's own integer division would truncate it to 2.
                        assert(quotient == Chunk(BigDecimal("2.5")), s"10 / 4 must be 2.5, got $quotient")
                        assert(truncated == Chunk(2), s"10 divideTruncating 4 must be 2, got $truncated")
                        // The ROLLUP subtotal row carries NULL for the key it is not grouping by.
                        val byRegion = rolledUp.toSeq.map(r => r.region -> r.total).toMap
                        assert(byRegion.size == 3, s"ROLLUP must add one subtotal row to the two regions, got $rolledUp")
                        assert(byRegion(Present("north")) == 30L, s"north total must be 30, got $rolledUp")
                        assert(byRegion(Present("south")) == 71L, s"south total must be 71, got $rolledUp")
                        assert(byRegion(Absent) == 101L, s"the subtotal row must carry Absent and 101, got $rolledUp")
                    end for
                }
            }
        }
    }

    "Leaf 31b: aggregates, division, and a rollup key all return the values their types promise on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    val metrics = Sql.from[Metric]("m")
                    for
                        _ <- client.executeRaw(
                            "CREATE TABLE metric (id BIGINT PRIMARY KEY, region VARCHAR(128) NOT NULL, amount INT NOT NULL, " +
                                "quantity BIGINT NOT NULL, divisor INT NOT NULL)"
                        )
                        _             <- client.executeRaw(s"INSERT INTO metric VALUES $metricRows")
                        amountTotal   <- metrics.sum(_.m.amount).run
                        amountAverage <- metrics.avg(_.m.amount).run
                        amountLow     <- metrics.min(_.m.amount).run
                        amountHigh    <- metrics.max(_.m.amount).run
                        quantityTotal <- metrics.sum(_.m.quantity).run
                        emptyTotal    <- metrics.where(c => c.m.amount > 1000).sum(_.m.amount).run
                        quotient      <- metrics.where(c => c.m.id == 1L).select(c => c.m.amount / c.m.divisor).run
                        truncated     <- metrics.where(c => c.m.id == 1L).select(c => c.m.amount.divideTruncating(c.m.divisor)).run
                        rolledUp <- metrics.groupByRollup(c => c.m.region).select(v => (v.region, v.amount.sum)).to[
                            RegionTotal
                        ].run
                    yield
                        // MySQL's SUM and AVG over an exact operand are DECIMAL, which arrives as a length-encoded ASCII
                        // string. At type Int, "101" read as a little-endian int4 is 3223601, and a two-digit result
                        // raises an insufficient-bytes failure instead.
                        assert(amountTotal.head == Present(101L), s"SUM(amount) must be 101, got ${amountTotal.head}")
                        assert(
                            amountAverage.head == Present(BigDecimal("25.25")),
                            s"AVG(amount) must be 25.25, got ${amountAverage.head}"
                        )
                        assert(amountLow.head == Present(10), s"MIN(amount) must be 10, got ${amountLow.head}")
                        assert(amountHigh.head == Present(41), s"MAX(amount) must be 41, got ${amountHigh.head}")
                        assert(
                            quantityTotal.head == Present(BigDecimal(1000)),
                            s"SUM(quantity) must be 1000, got ${quantityTotal.head}"
                        )
                        assert(emptyTotal.head == Absent, s"SUM over no rows must be Absent, got ${emptyTotal.head}")
                        // MySQL's `/` is fractional on its own, so this is the assertion that pins the two engines to
                        // the same answer.
                        assert(quotient == Chunk(BigDecimal("2.5")), s"10 / 4 must be 2.5, got $quotient")
                        // And DIV is the operator that keeps truncation expressible here.
                        assert(truncated == Chunk(2), s"10 divideTruncating 4 must be 2, got $truncated")
                        val byRegion = rolledUp.toSeq.map(r => r.region -> r.total).toMap
                        assert(byRegion.size == 3, s"WITH ROLLUP must add one subtotal row to the two regions, got $rolledUp")
                        assert(byRegion(Present("north")) == 30L, s"north total must be 30, got $rolledUp")
                        assert(byRegion(Present("south")) == 71L, s"south total must be 71, got $rolledUp")
                        assert(byRegion(Absent) == 101L, s"the subtotal row must carry Absent and 101, got $rolledUp")
                    end for
                }
            }
        }
    }

end SqlEndToEndTest
