package kyo

import kyo.SqlAst.*
import kyo.internal.SqlRender
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend

/** End-to-end integration tests against real PG + MySQL containers.
  *
  * Covers the full pipeline: DSL → AST → SqlRender → SqlClient.executeBound* → real DB → assertion.
  *
  * InsertResult contract (InsertResult.scala):
  *   - affectedRows: Long, row count from CommandComplete / OK packet
  *   - generatedKey: Maybe[Long], Present when autoKey detected + DB reported one; Absent when no autoKey column or DB reports 0.
  * Auto-key detection: case class whose FIRST field is Long-typed. PG: auto-appends RETURNING <pk>; MySQL: reads last_insert_id from OK
  * packet.
  *
  * Note on .runStatic: the static reducer lands the FromExpr reducer. All live assertions use .run (runtime renderer fallback) or
  * .runDynamic. Static rendering correctness is verified separately via SqlStatic.staticSql in SqlStaticTest and SqlRenderTest.
  */
class SqlEndToEndTest extends Test:

    override def timeout: Duration = 5.minutes

    // ── Shared case classes ────────────────────────────────────────────────────

    case class Person(id: Long, name: String, age: Int) derives Schema, CanEqual
    case class Dept(id: Long, name: String) derives Schema, CanEqual
    case class Tag(name: String) derives Schema, CanEqual

    inline given SqlSchema[Person] = SqlSchema.derived
    inline given SqlSchema[Dept]   = SqlSchema.derived
    inline given SqlSchema[Tag]    = SqlSchema.derived

    // ── Helper: build a SqlClient from a SchemaCtx ────────────────────────────

    private def pgUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def withPgClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(f: SqlClient => A < (S & Async & Abort[SqlException]))(using
        Frame
    ): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlException.Connection](SqlClient.init(pgUrl(ctx))).flatMap {
            case Result.Success(client) =>
                Scope.ensure(client.close).andThen(SqlClient.let(client)(f(client)))
            case Result.Failure(e) =>
                Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                Abort.error(Result.Panic(t))
        }

    private def withMyClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(f: SqlClient => A < (S & Async & Abort[SqlException]))(using
        Frame
    ): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlException.Connection](SqlClient.initMy(myUrl(ctx))).flatMap {
            case Result.Success(client) =>
                Scope.ensure(client.close).andThen(SqlClient.let(client)(f(client)))
            case Result.Failure(e) =>
                Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                Abort.error(Result.Panic(t))
        }

    // ── Leaf 1: SELECT + WHERE round-trip on PG ───────────────────────────────

    "Leaf 1: SELECT + WHERE round-trip on PG" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── Leaf 2: SELECT + WHERE round-trip on MySQL ────────────────────────────

    "Leaf 2: SELECT + WHERE round-trip on MySQL" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── Leaf 3: JOIN + SELECT round-trip on PG ────────────────────────────────

    "Leaf 3: JOIN + SELECT round-trip on PG" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── Leaf 4: JOIN + SELECT round-trip on MySQL ─────────────────────────────

    "Leaf 4: JOIN + SELECT round-trip on MySQL" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── Leaf 5: GROUP BY + HAVING on PG ──────────────────────────────────────

    "Leaf 5: GROUP BY + HAVING round-trip on PG" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── Leaf 6: GROUP BY + HAVING on MySQL ────────────────────────────────────

    "Leaf 6: GROUP BY + HAVING round-trip on MySQL" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── Leaf 7: CTE round-trip on PG (recursive) ─────────────────────────────

    "Leaf 7: CTE + recursive CTE round-trip on PG" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        // Recursive CTE: generate numbers 1..5
                        // Cast the CTE seed to BIGINT so SqlSchema[Long] (int8 reader) decodes correctly.
                        client
                            .executeBoundQuery[Long](
                                """WITH RECURSIVE cte (n) AS (
                                  |  SELECT 1::BIGINT
                                  |  UNION ALL
                                  |  SELECT cte.n + 1 FROM cte WHERE cte.n < $1
                                  |)
                                  |SELECT n FROM cte""".stripMargin,
                                Chunk(SqlSchema.BoundValue(5L, summon[SqlSchema[Long]]))
                            )
                            .map { rows =>
                                assert(rows.size == 5, s"Expected 5 rows from recursive CTE, got: ${rows.size}")
                                assert(rows.toSeq.sorted == Seq(1L, 2L, 3L, 4L, 5L), s"Expected 1..5, got: $rows")
                            }
                    }
                }
            }
        }
    }

    // ── Leaf 8: CTE round-trip on MySQL (non-recursive) ──────────────────────

    "Leaf 8: CTE round-trip on MySQL (non-recursive)" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                    withMyClient(ctx) { client =>
                        // Non-recursive CTE on MySQL 8+
                        client
                            .executeBoundQuery[Long](
                                "WITH vals (n) AS (SELECT ? UNION ALL SELECT ? UNION ALL SELECT ?) SELECT n FROM vals",
                                Chunk(
                                    SqlSchema.BoundValue(1L, summon[SqlSchema[Long]]),
                                    SqlSchema.BoundValue(2L, summon[SqlSchema[Long]]),
                                    SqlSchema.BoundValue(3L, summon[SqlSchema[Long]])
                                )
                            )
                            .map { rows =>
                                assert(rows.size == 3, s"Expected 3 rows from CTE, got: ${rows.size}")
                                assert(rows.toSeq.sorted == Seq(1L, 2L, 3L), s"Expected 1,2,3, got: $rows")
                            }
                    }
                }
            }
        }
    }

    // ── Leaves 9-11: InsertResult ─────────────────────────────────────────────
    //
    // InsertResult contract for auto-key INSERTs:
    //   - affectedRows: Long
    //   - generatedKey: Maybe[Long], Present when first field is Long AND DB reports a key.
    //     PG auto-appends RETURNING <pk>; MySQL reads last_insert_id from OK packet.

    "Leaf 9: INSERT with auto-key returns InsertResult.generatedKey = Present on PG" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
                                GeneratedKey.isPresent(result.generatedKey),
                                s"Expected Value generatedKey for PG auto-RETURNING, got: ${result.generatedKey}"
                            )
                            assert(
                                GeneratedKey.foldKey(result.generatedKey)(-1L)(identity) == 42L,
                                s"Expected generatedKey == Value(42L), got: ${result.generatedKey}"
                            )
                    }
                }
            }
        }
    }

    "Leaf 10: INSERT with auto-key returns InsertResult.generatedKey = Present on MySQL" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
                                GeneratedKey.isPresent(result.generatedKey),
                                s"Expected Value generatedKey for MySQL AUTO_INCREMENT, got: ${result.generatedKey}"
                            )
                            assert(
                                GeneratedKey.foldKey(result.generatedKey)(false)(_ > 0L),
                                s"Expected positive generated key, got: ${result.generatedKey}"
                            )
                    }
                }
            }
        }
    }

    "Leaf 11: INSERT without auto-key column returns InsertResult.generatedKey = Absent" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
                                result.generatedKey == GeneratedKey.NoAutoKey,
                                s"Expected NoAutoKey generatedKey for non-auto-key table, got: ${result.generatedKey}"
                            )
                    }
                }
            }
        }
    }

    // ── Leaf 12: UPDATE affected-row count on PG ──────────────────────────────

    "Leaf 12: UPDATE returns correct affected-row count on PG" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── Leaf 13: UPDATE affected-row count on MySQL ───────────────────────────

    "Leaf 13: UPDATE returns correct affected-row count on MySQL" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── Leaf 14: DELETE affected-row count on PG ──────────────────────────────

    "Leaf 14: DELETE returns correct affected-row count on PG" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── Leaf 15: DELETE affected-row count on MySQL ───────────────────────────

    "Leaf 15: DELETE returns correct affected-row count on MySQL" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── Leaf 24: Transaction rollback leaves table unchanged (PG + MySQL) ──────
    // Plan leaf 24 says "one leaf per backend, counted here as one leaf, split to two
    // if the test runner reports them separately." We use two named leaves.

    "Leaf 24a: transaction rollback leaves table unchanged on PG" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
                                            Abort.fail[SqlException](SqlException.Request(
                                                "intentional rollback",
                                                Maybe.Absent,
                                                summon[Frame]
                                            ))
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
    }

    "Leaf 24b: transaction rollback leaves table unchanged on MySQL" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
                                            Abort.fail[SqlException](SqlException.Request(
                                                "intentional rollback",
                                                Maybe.Absent,
                                                summon[Frame]
                                            ))
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
    }

    // ── Leaf 25: sql"..." raw interpolator round-trip on both backends ─────────
    // The sql"..." interpolator returns Fragment[Any] (a Term[Any]) that can be
    // embedded in .where() / .select() predicates. We embed it in a WHERE predicate
    // and execute against a live DB to verify the full interpolation pipeline.

    "Leaf 25a: sql raw interpolator round-trip on PG" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    "Leaf 25b: sql raw interpolator round-trip on MySQL" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── SqlClient factory-chain and close-triad ────────────────────────────────

    "initWith(url)(f) creates a client, runs f, registers Scope cleanup" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    // initWith registers Scope.ensure(close); query succeeds inside `f`.
                    SqlClient.initWith(pgUrl(ctx)) { client =>
                        SqlClient.let(client) {
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
    }

    "use(url)(f) creates a client with bracket semantics, no Scope in effect set" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    // use registers Sync.ensure(close); Scope is absent from the returned effect row.
                    // The body uses executeRaw which carries Abort[SqlException] (supertype);
                    // the factory propagates that upward as-is, no Scope leak.
                    SqlClient.use(pgUrl(ctx)) { client =>
                        SqlClient.let(client) {
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
    }

    "initUnscoped(url) creates a client with no cleanup; manual close completes without error" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    // initUnscoped leaves cleanup to the caller; close the client manually.
                    SqlClient.initUnscoped(pgUrl(ctx)).flatMap { client =>
                        SqlClient.let(client) {
                            client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            ).andThen {
                                client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                                    .andThen(client.close)
                                    .map(_ => succeed)
                            }
                        }
                    }
                }
            }
        }
    }

    "close(gracePeriod), close, and closeNow all complete without error" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
    }

    // ── Phase 2 W-2: INSERT/SELECT a Duration column on PG (container-gated) ────

    "Phase 2: INSERT/SELECT a java.time.Duration round-trips through PG INTERVAL" in {
        case class Trip(id: Long, label: String, span: java.time.Duration) derives Schema, CanEqual
        inline given SqlSchema[Trip] = SqlSchema.derived

        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE trip (id BIGINT PRIMARY KEY, label TEXT NOT NULL, span INTERVAL NOT NULL)"""
                            )
                            // Exercise the µs-only round-trip across boundary values: zero, sub-second precision,
                            // negative, and the multi-day case Phase 2 W-1 added at the codec layer.
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
    }

end SqlEndToEndTest
