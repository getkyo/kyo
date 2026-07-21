package kyo.mysql

import kyo.*
import kyo.Sql.render
import kyo.SqlAst.*
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend

/** MySQL-only feature tests.
  *
  * Features covered:
  *   - ilike on MySQL (emulated as LOWER(x) LIKE LOWER(p))
  *   - ++ concat on MySQL (rendered as CONCAT(…))
  *   - onConflictDoNothing is idempotent on MySQL (INSERT IGNORE)
  *   - onConflictDoUpdate updates existing row on MySQL (ON DUPLICATE KEY UPDATE)
  */
class SqlMysqlOnlyTest extends Test:

    override def timeout: Duration = 5.minutes

    case class Person(id: Long, name: String, age: Int) derives Schema, CanEqual

    given SqlSchema[Person] = SqlSchema.derived

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

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

    // ── Leaf 17: ilike on MySQL uses LOWER(x) LIKE LOWER(p) emulation ─────────

    "Leaf 17: ilike on MySQL returns expected rows using LOWER(…) LIKE LOWER(…)" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
                                    .render(SqlBackend.Mysql)
                                assert(
                                    rendered.sql.contains("LOWER"),
                                    s"Expected LOWER in MySQL ilike SQL, got: ${rendered.sql}"
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
    }

    // ── Leaf 19: ++ concat on MySQL uses CONCAT(…) ────────────────────────────

    "Leaf 19: ++ concat on MySQL returns expected concatenated string using CONCAT" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
                                    .render(SqlBackend.Mysql)
                                assert(
                                    rendered.sql.contains("CONCAT"),
                                    s"Expected CONCAT in MySQL concat SQL, got: ${rendered.sql}"
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
    }

    // ── Leaf 21: onConflictDoNothing is idempotent on MySQL (INSERT IGNORE) ───

    "Leaf 21: onConflictDoNothing is idempotent on MySQL via INSERT IGNORE" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
                                    .render(SqlBackend.Mysql)
                                assert(
                                    rendered.sql.contains("INSERT IGNORE"),
                                    s"Expected INSERT IGNORE in MySQL onConflictDoNothing SQL, got: ${rendered.sql}"
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
    }

    // ── Leaf 23: onConflictDoUpdate updates existing row on MySQL ─────────────

    "Leaf 23: onConflictDoUpdate updates existing row on MySQL via ON DUPLICATE KEY UPDATE" in {
        Scope.run {
            Async.timeout(120.seconds) {
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
                                    .onConflictDoUpdate(_.id)(c => c.age := SqlAst.Excluded(c.age))
                                    .render(SqlBackend.Mysql)
                                assert(
                                    rendered.sql.contains("ON DUPLICATE KEY UPDATE"),
                                    s"Expected ON DUPLICATE KEY UPDATE in MySQL upsert SQL, got: ${rendered.sql}"
                                )
                            // Execute the upsert against live MySQL
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

end SqlMysqlOnlyTest
