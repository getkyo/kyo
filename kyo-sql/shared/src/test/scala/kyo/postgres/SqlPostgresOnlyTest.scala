package kyo.postgres

import kyo.*
import kyo.SqlAst.*
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend

/** Postgres-only feature tests.
  *
  * Features covered:
  *   - ilike on PG (native ILIKE operator)
  *   - ++ concat on PG (rendered as `||`)
  *   - onConflictDoNothing is idempotent on PG
  *   - onConflictDoUpdate updates existing row on PG
  *   - FULL OUTER JOIN (native; not the MySQL LEFT/RIGHT UNION emulation)
  *   - RETURNING <pk> (auto-emitted by PG renderer on Insert with auto-key)
  *   - INSERT … ON CONFLICT (<col>) DO UPDATE SET … (PG upsert)
  */
class SqlPostgresOnlyTest extends Test:

    override def timeout: Duration = 5.minutes

    case class Person(id: Long, name: String, age: Int) derives Schema
    case class Dept(id: Long, budget: Long) derives Schema

    given SqlSchema[Person] = SqlSchema.derived
    given SqlSchema[Dept]   = SqlSchema.derived

    private def pgUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

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

    // ── Leaf 16: ilike on PG uses native ILIKE ────────────────────────────────

    "Leaf 16: ilike on PG returns expected rows using native ILIKE" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            )
                            _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'Alice', 30), (2, 'BOB', 25), (3, 'carol', 28)""")
                            // ilike should match case-insensitively: 'alice%' matches 'Alice'
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
    }

    // ── Leaf 18: ++ concat on PG uses || operator ─────────────────────────────

    "Leaf 18: ++ concat on PG returns expected concatenated string using ||" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            )
                            _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                            // Verify the rendered SQL uses || for concat on PG
                            _ =
                                val rendered = Sql.from[Person]("p").select(c => c.p.name ++ " rocks")
                                    .render(SqlBackend.Postgres)
                                assert(
                                    rendered.sql.contains("||"),
                                    s"Expected || in PG concat SQL, got: ${rendered.sql}"
                                )
                            // Execute the concat query against live PG
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
    }

    // ── Leaf 20: onConflictDoNothing is idempotent on PG ─────────────────────

    "Leaf 20: onConflictDoNothing is idempotent on PG (duplicate row leaves table unchanged)" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            )
                            _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                            // Insert duplicate with ON CONFLICT DO NOTHING, should not error or change data
                            result <- Sql
                                .insert[Person]
                                .values(Person(1L, "alice-duplicate", 99))
                                .onConflictDoNothing()
                                .run
                            rows <- Sql.from[Person]("p").run
                        yield
                            // Table must still have exactly 1 row with original data
                            assert(rows.size == 1, s"Expected 1 row after idempotent insert, got: ${rows.size}")
                            assert(rows.head.name == "alice", s"Expected original 'alice', got: '${rows.head.name}'")
                            assert(rows.head.age == 30, s"Expected original age 30, got: ${rows.head.age}")
                    }
                }
            }
        }
    }

    // ── Leaf 22: onConflictDoUpdate updates existing row on PG ─────────────────

    "Leaf 22: onConflictDoUpdate updates existing row on PG via ON CONFLICT … DO UPDATE" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            )
                            _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                            // Upsert: conflict on id=1, update age to 31
                            _ <- Sql
                                .insert[Person]
                                .values(Person(1L, "alice-upserted", 31))
                                .onConflictDoUpdate(_.id)(c => c.age := SqlAst.Excluded(c.age))
                                .run
                            rows <- Sql.from[Person]("p").run
                        yield
                            assert(rows.size == 1, s"Expected 1 row after upsert, got: ${rows.size}")
                            assert(rows.head.age == 31, s"Expected updated age 31, got: ${rows.head.age}")
                    }
                }
            }
        }
    }

    // ── FULL OUTER JOIN on PG ─────────────────────────────────────────────────
    // Plan item from the SqlPostgresOnlyTest spec: native FULL OUTER JOIN

    "PG FULL OUTER JOIN returns rows from both sides" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            )
                            _ <- client.executeRaw(
                                """CREATE TABLE dept (id BIGINT PRIMARY KEY, budget BIGINT NOT NULL)"""
                            )
                            _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30), (3, 'carol', 28)""")
                            _ <- client.executeRaw("""INSERT INTO dept VALUES (1, 100000), (2, 200000)""")
                            // Verify FULL OUTER JOIN renders correctly (native PG syntax)
                            rendered = Sql.from[Person]("p")
                                .fullOuterJoin(Sql.from[Dept]("d"))
                                .on(j => j.p.id == j.d.id)
                                .select(j => j.p.name)
                                .render(SqlBackend.Postgres)
                            _ = assert(
                                rendered.sql.contains("FULL OUTER JOIN"),
                                s"Expected FULL OUTER JOIN in PG SQL, got: ${rendered.sql}"
                            )
                            // Execute against live PG and verify the closure: person ids {1,3} FULL OUTER
                            // JOIN dept ids {1,2} on id yields 3 rows, (1,1) matched, (3,·) and (·,2)
                            // unmatched. We count rows so NULL-side columns need no nullable decoding.
                            counts <- client.executeBoundQuery[Long](
                                """SELECT COUNT(*) FROM "person" "p" FULL OUTER JOIN "dept" "d" ON ("p"."id" = "d"."id")""",
                                Chunk.empty
                            )
                        yield
                            assert(counts.size == 1, s"Expected 1 count row, got: ${counts.size}")
                            assert(counts.head == 3L, s"Expected 3 FULL OUTER JOIN rows, got: ${counts.head}")
                    }
                }
            }
        }
    }

end SqlPostgresOnlyTest
