package kyo.postgres

import kyo.*
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend

/** Container-gated integration tests for the PostgreSQL transaction surface — fills the integration coverage gap left in #514 by Phases 69
  * (isolation level coverage), 70 (READ ONLY transactions), and 71 (savepoints / nested transactions).
  *
  * Each test wraps a fresh schema and asserts the transaction executes end-to-end with the requested isolation / read-only / nesting
  * configuration. Gated on Docker availability via [[SqlSharedContainers]] — local runs without Docker abort at container init; CI is the
  * source of truth.
  */
class PostgresTransactionIntegrationTest extends Test:

    override def timeout: Duration = 5.minutes

    case class Person(id: Long, name: String, age: Int) derives Schema, CanEqual

    given SqlSchema[Person] = SqlSchema.derived

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
            case Result.Failure(e) => Abort.fail(e: SqlException)
            case Result.Panic(t)   => Abort.error(Result.Panic(t))
        }

    // ── Phase 69: isolation level coverage (PG) ────────────────────────────────

    private def isolationLeaf(level: SqlIsolationLevel)(using kyo.test.AssertScope) =
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            )
                            result <- client.transaction(Maybe.Present(level), readOnly = false) {
                                for
                                    _    <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                                    rows <- Sql.from[Person]("p").run
                                yield rows.size
                            }
                        yield assert(result == 1, s"expected 1 row visible inside $level transaction, got $result")
                    }
                }
            }
        }

    "PG transaction with ReadUncommitted isolation completes end-to-end" in {
        isolationLeaf(SqlIsolationLevel.ReadUncommitted)
    }

    "PG transaction with ReadCommitted isolation completes end-to-end" in {
        isolationLeaf(SqlIsolationLevel.ReadCommitted)
    }

    "PG transaction with RepeatableRead isolation completes end-to-end" in {
        isolationLeaf(SqlIsolationLevel.RepeatableRead)
    }

    "PG transaction with Serializable isolation completes end-to-end" in {
        isolationLeaf(SqlIsolationLevel.Serializable)
    }

    // ── Phase 70: READ ONLY transactions (PG) ──────────────────────────────────

    "PG transaction(readOnly=true) commits SELECT-only work" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            )
                            _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                            rows <- client.transaction(Maybe.Absent, readOnly = true) {
                                Sql.from[Person]("p").run
                            }
                        yield assert(rows.size == 1)
                    }
                }
            }
        }
    }

    "PG transaction(readOnly=true) rejects INSERT" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            )
                            // PG raises 25006 (read_only_sql_transaction) for any write attempt inside a read-only tx.
                            attempt <- Abort.run[SqlException](
                                client.transaction(Maybe.Absent, readOnly = true) {
                                    client.executeRaw("INSERT INTO person VALUES (2, 'bob', 25)").unit
                                }
                            )
                        yield assert(attempt.isFailure, s"expected Failure for INSERT inside read-only tx, got $attempt")
                    }
                }
            }
        }
    }

    // ── Phase 71: PG savepoints / nested transactions ─────────────────────────

    "PG nested transaction commits inner work when both succeed" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            )
                            _ <- client.transaction {
                                for
                                    _ <- client.executeRaw("INSERT INTO person VALUES (1, 'outer', 40)")
                                    _ <- client.transaction {
                                        client.executeRaw("INSERT INTO person VALUES (2, 'inner', 35)").unit
                                    }
                                yield ()
                            }
                            rows <- Sql.from[Person]("p").run
                        yield assert(rows.size == 2, s"expected both rows committed, got ${rows.size}")
                    }
                }
            }
        }
    }

    "PG nested transaction inner SAVEPOINT rollback preserves outer writes" in {
        Scope.run {
            Async.timeout(120.seconds) {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    withPgClient(ctx) { client =>
                        for
                            _ <- client.executeRaw(
                                """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                            )
                            _ <- client.transaction {
                                for
                                    _ <- client.executeRaw("INSERT INTO person VALUES (1, 'outer', 40)")
                                    // Inner transaction fails — savepoint rolls back to the outer state, outer continues.
                                    innerAttempt <- Abort.run[SqlException](
                                        client.transaction {
                                            for
                                                _ <- client.executeRaw("INSERT INTO person VALUES (2, 'inner', 35)")
                                                // Trigger an integrity violation that aborts the savepoint.
                                                _ <- client.executeRaw("INSERT INTO person VALUES (2, 'duplicate', 99)")
                                            yield ()
                                        }
                                    )
                                yield assert(innerAttempt.isFailure, "inner tx expected to fail on PK conflict")
                            }
                            rows <- Sql.from[Person]("p").run
                        yield
                            // After the savepoint rollback, only the outer INSERT (id=1) survives — the inner one (id=2) was undone.
                            assert(rows.exists(_.id == 1L), s"outer row should survive savepoint rollback, got $rows")
                            assert(!rows.exists(_.id == 2L), s"inner row should NOT survive, got $rows")
                    }
                }
            }
        }
    }

end PostgresTransactionIntegrationTest
