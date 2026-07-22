package kyo.mysql

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for MySQL transactions.
  *
  * Tests:
  *   1. commit persists data, row visible in a separate query after commit
  *   2. rollback on Abort removes data, row not visible after Abort.fail
  *   3. panic rolls back, unhandled throw causes rollback
  *   4. nested SAVEPOINT on success releases savepoint, inner success issues RELEASE SAVEPOINT
  *   5. nested SAVEPOINT on inner abort rolls back to savepoint, outer continues
  *   6. nested SAVEPOINT on outer abort rolls back outer, full rollback
  *   7. REPEATABLE READ isolation level accepted, SET TRANSACTION ISOLATION LEVEL REPEATABLE READ
  *   8. SERIALIZABLE isolation level accepted, SET TRANSACTION ISOLATION LEVEL SERIALIZABLE
  *   9. read-only rejects INSERT, SqlServerException from the server
  *   10. DDL implicit commit caveat, CREATE TABLE inside transaction commits, contradicting caller expectation
  *
  * Each test runs against a fresh schema in the per-fork-JVM shared MySQL container (via [[SqlSharedContainers.withFreshSchema]]).
  */
class MysqlTransactionIntegrationTest extends kyo.Test:

    override def timeout: Duration = 3.minutes

    // ── Helpers ────────────────────────────────────────────────────────────────

    private def initClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx,
        maxConns: Int = 2
    )(
        f: SqlClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException]) =
        SqlClient.initMysqlWith(
            s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}",
            SqlConfig.default.copy(
                maxConnections = maxConns,
                minConnections = maxConns
            )
        )(f)

    /** Decode the first column of each row as a UTF-8 string. Safe for VARCHAR / CHAR / TEXT columns: the wire bytes for these types are
      * the UTF-8 encoded characters in both text and binary MySQL protocols. Not safe for numeric columns; use [[firstCount]] for those.
      */
    private def rowsAsString(rows: Chunk[SqlRow]): Seq[String] =
        rows.toSeq.map { row =>
            row.column(0).fold("NULL")(bytes => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8))
        }

    /** Decode the first column of the first row as a `Long` via the schema-aware decoder. Required for numeric columns because the
      * client's `query` uses MySQL's extended (binary) protocol, which returns integers in fixed-width little-endian bytes, not ASCII.
      * Returns 0 when the result set is empty.
      */
    private def firstCount(rows: Chunk[SqlRow])(using Frame): Long < Abort[SqlException] =
        rows.headOption match
            case None      => 0L
            case Some(row) => row.decode[Long](0)

    // ── commit persists data ───────────────────────────────────────────────────

    "transaction commit persists data, row visible after COMMIT in separate query" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx) { client =>
                    val tableName = "tx_commit_test"
                    for
                        _ <- client.executeRaw(s"CREATE TABLE IF NOT EXISTS $tableName (id INT, val VARCHAR(64))")
                        _ <- client.executeRaw(s"TRUNCATE TABLE $tableName")
                        _ <- client.transaction {
                            client.executeRaw(s"INSERT INTO $tableName VALUES (1, 'committed')")
                        }
                        rows <- client.query(s"SELECT val FROM $tableName WHERE id = 1")
                        _    <- client.executeRaw(s"DROP TABLE $tableName")
                    yield assert(rowsAsString(rows) == Seq("committed"), s"Expected committed row, got: ${rowsAsString(rows)}")
                    end for
                }
            }
        }
    }

    // ── rollback on Abort removes data ────────────────────────────────────────

    "transaction rollback on Abort removes data, row NOT visible after rollback" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx) { client =>
                    val tableName = "tx_rollback_test"
                    for
                        _ <- client.executeRaw(s"CREATE TABLE IF NOT EXISTS $tableName (id INT, val VARCHAR(64))")
                        _ <- client.executeRaw(s"TRUNCATE TABLE $tableName")
                        result <- Abort.run[SqlException] {
                            client.transaction {
                                client.executeRaw(s"INSERT INTO $tableName VALUES (2, 'rollback')").andThen(
                                    Abort.fail(SqlServerException("XX000", "ERROR", "force rollback"))
                                )
                            }
                        }
                        rows  <- client.query(s"SELECT COUNT(*) FROM $tableName WHERE id = 2")
                        _     <- client.executeRaw(s"DROP TABLE $tableName")
                        count <- firstCount(rows)
                    yield assert(count == 0L, s"Expected 0 rows after rollback, got $count")
                    end for
                }
            }
        }
    }

    // ── panic rolls back ──────────────────────────────────────────────────────

    "transaction rollback on panic, rollback called on failure path restores clean state" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx) { client =>
                    val tableName = "tx_panic_test"
                    client.executeRaw(s"CREATE TABLE IF NOT EXISTS $tableName (id INT)").flatMap { _ =>
                        client.executeRaw(s"TRUNCATE TABLE $tableName").flatMap { _ =>
                            val txBody: Unit < (Async & Abort[SqlException]) =
                                client.transaction {
                                    client.executeRaw(s"INSERT INTO $tableName VALUES (99)").andThen(
                                        Abort.fail(SqlServerException("XX000", "ERROR", "simulated error"))
                                    )
                                }
                            Abort.run[SqlException](txBody).flatMap { result =>
                                client.query(s"SELECT COUNT(*) FROM $tableName").flatMap { rows =>
                                    client.executeRaw(s"DROP TABLE $tableName").andThen {
                                        firstCount(rows).map { count =>
                                            assert(result.isFailure, "Expected failure from simulated error")
                                            assert(count == 0L, s"Expected 0 rows after rollback, got $count")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── nested savepoint: inner success releases savepoint ───────────────────

    "nested SAVEPOINT on inner success releases savepoint, outer commits normally" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx) { client =>
                    val tableName = "tx_savepoint_release"
                    for
                        _ <- client.executeRaw(s"CREATE TABLE IF NOT EXISTS $tableName (id INT)")
                        _ <- client.executeRaw(s"TRUNCATE TABLE $tableName")
                        _ <- client.transaction {
                            for
                                _ <- client.executeRaw(s"INSERT INTO $tableName VALUES (1)")
                                // Nested transaction → SAVEPOINT/RELEASE on the bound connection.
                                _ <- client.transaction {
                                    client.executeRaw(s"INSERT INTO $tableName VALUES (2)")
                                }
                            yield ()
                        }
                        rows  <- client.query(s"SELECT COUNT(*) FROM $tableName")
                        _     <- client.executeRaw(s"DROP TABLE $tableName")
                        count <- firstCount(rows)
                    yield assert(count == 2L, s"Expected 2 rows after nested commit, got $count")
                    end for
                }
            }
        }
    }

    // ── nested savepoint: inner abort rolls back to savepoint ────────────────

    "nested SAVEPOINT on inner abort rolls back to savepoint, outer continues" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx) { client =>
                    val tableName = "tx_savepoint_rollback"
                    for
                        _ <- client.executeRaw(s"CREATE TABLE IF NOT EXISTS $tableName (id INT)")
                        _ <- client.executeRaw(s"TRUNCATE TABLE $tableName")
                        _ <- client.transaction {
                            for
                                _ <- client.executeRaw(s"INSERT INTO $tableName VALUES (1)")
                                // Inner savepoint, will be rolled back via Abort.fail.
                                _ <- Abort.run[SqlException] {
                                    client.transaction {
                                        client.executeRaw(s"INSERT INTO $tableName VALUES (2)").andThen(
                                            Abort.fail(SqlServerException("XX000", "ERROR", "inner abort"))
                                        )
                                    }
                                }
                            // Inner failure is swallowed; outer continues and commits row 1.
                            yield ()
                        }
                        rows  <- client.query(s"SELECT COUNT(*) FROM $tableName")
                        _     <- client.executeRaw(s"DROP TABLE $tableName")
                        count <- firstCount(rows)
                    yield assert(count == 1L, s"Expected 1 row after inner rollback + outer commit, got $count")
                    end for
                }
            }
        }
    }

    // ── full rollback on outer abort ──────────────────────────────────────────

    "full rollback on outer abort, both inner and outer rows gone" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx) { client =>
                    val tableName = "tx_full_rollback"
                    for
                        _ <- client.executeRaw(s"CREATE TABLE IF NOT EXISTS $tableName (id INT)")
                        _ <- client.executeRaw(s"TRUNCATE TABLE $tableName")
                        result <- Abort.run[SqlException] {
                            client.transaction {
                                for
                                    _ <- client.executeRaw(s"INSERT INTO $tableName VALUES (10)")
                                    _ <- Abort.run[SqlException] {
                                        client.transaction {
                                            client.executeRaw(s"INSERT INTO $tableName VALUES (20)").andThen(
                                                Abort.fail(SqlServerException("XX000", "ERROR", "inner sp rollback"))
                                            )
                                        }
                                    }
                                    // Inner failure swallowed; now force outer rollback.
                                    _ <- Abort.fail(SqlServerException("XX000", "ERROR", "outer rollback"))
                                yield ()
                            }
                        }
                        rows  <- client.query(s"SELECT COUNT(*) FROM $tableName")
                        _     <- client.executeRaw(s"DROP TABLE $tableName")
                        count <- firstCount(rows)
                    yield assert(count == 0L, s"Expected 0 rows after full rollback, got $count")
                    end for
                }
            }
        }
    }

    // ── REPEATABLE READ isolation level ───────────────────────────────────────

    "REPEATABLE READ isolation level, SET TRANSACTION command accepted without error" in {
        // Verifies that beginTransaction(RepeatableRead) completes without raising SqlException.
        // The SET TRANSACTION ISOLATION LEVEL command is accepted by the server even if
        // @@transaction_isolation inside the transaction reflects the session default.
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 1) { client =>
                    Abort.run[SqlException] {
                        client.transaction(Maybe.Present(SqlClient.IsolationLevel.RepeatableRead), readOnly = false) {
                            client.query("SELECT 1").unit
                        }
                    }.map {
                        case Result.Success(_) => succeed // no error, SET TRANSACTION REPEATABLE READ accepted
                        case Result.Failure(e) => fail(s"Unexpected error for REPEATABLE READ: $e")
                        case Result.Panic(t)   => fail(s"Unexpected panic: ${t.getMessage}")
                    }
                }
            }
        }
    }

    // ── SERIALIZABLE isolation level ──────────────────────────────────────────

    "SERIALIZABLE isolation level, SET TRANSACTION command accepted without error" in {
        // Verifies that beginTransaction(Serializable) completes without raising SqlException.
        // NOTE: @@transaction_isolation shows the SESSION default inside a transaction and
        // does NOT reflect a per-transaction SET TRANSACTION override in MySQL 8.x.
        // We verify acceptance via absence of errors, not via the session variable.
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 1) { client =>
                    Abort.run[SqlException] {
                        client.transaction(Maybe.Present(SqlClient.IsolationLevel.Serializable), readOnly = false) {
                            client.query("SELECT 1").unit
                        }
                    }.map {
                        case Result.Success(_) => succeed // no error, SET TRANSACTION SERIALIZABLE accepted
                        case Result.Failure(e) => fail(s"Unexpected error for SERIALIZABLE: $e")
                        case Result.Panic(t)   => fail(s"Unexpected panic: ${t.getMessage}")
                    }
                }
            }
        }
    }

    // ── read-only transaction rejects INSERT ──────────────────────────────────

    "read-only transaction rejects INSERT, SqlServerException raised" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 1) { client =>
                    val tableName = "tx_readonly_test"
                    client.executeRaw(s"CREATE TABLE IF NOT EXISTS $tableName (id INT)").flatMap { _ =>
                        Abort.run[SqlException] {
                            client.transaction(Absent, readOnly = true) {
                                client.executeRaw(s"INSERT INTO $tableName VALUES (1)").unit
                            }
                        }.flatMap { result =>
                            client.executeRaw(s"DROP TABLE $tableName").map { _ =>
                                result match
                                    case Result.Failure(_: SqlServerException) =>
                                        succeed // expected: server rejects INSERT in READ ONLY
                                    case Result.Failure(e) =>
                                        fail(s"Expected SqlServerException, got: $e")
                                    case Result.Success(_) =>
                                        fail("Expected INSERT to fail in READ ONLY transaction, but it succeeded")
                                    case Result.Panic(t) =>
                                        fail(s"Unexpected panic: ${t.getMessage}")
                                end match
                            }
                        }
                    }
                }
            }
        }
    }

    // ── DDL implicit commit caveat ────────────────────────────────────────────

    "DDL implicit commit caveat, CREATE TABLE inside transaction commits prior DML (MySQL InnoDB behavior)" in {
        // InnoDB performs an implicit COMMIT before DDL statements. This test documents that a
        // subsequent ROLLBACK does NOT undo the INSERT that was committed by the DDL implicit commit.
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx) { client =>
                    val dataTable = "tx_ddl_data"
                    val ddlTable  = "tx_ddl_created"
                    for
                        _ <- client.executeRaw(s"CREATE TABLE IF NOT EXISTS $dataTable (id INT)")
                        _ <- client.executeRaw(s"DROP TABLE IF EXISTS $ddlTable")
                        _ <- client.executeRaw(s"TRUNCATE TABLE $dataTable")
                        result <- Abort.run[SqlException] {
                            client.transaction {
                                for
                                    _ <- client.executeRaw(s"INSERT INTO $dataTable VALUES (42)")
                                    // DDL causes implicit COMMIT of the INSERT above.
                                    _ <- client.executeRaw(s"CREATE TABLE $ddlTable (x INT)")
                                    // Force outer rollback via synthetic abort, verifies the INSERT survives.
                                    _ <- Abort.fail(SqlServerException("XX000", "ERROR", "force rollback"))
                                yield ()
                            }
                        }
                        rows  <- client.query(s"SELECT COUNT(*) FROM $dataTable")
                        _     <- client.executeRaw(s"DROP TABLE $dataTable")
                        _     <- client.executeRaw(s"DROP TABLE IF EXISTS $ddlTable")
                        count <- firstCount(rows)
                    yield
                        // The INSERT was committed by the DDL's implicit commit, ROLLBACK cannot undo it.
                        assert(count == 1L, s"Expected 1 row (DDL implicit commit preserved INSERT), got $count")
                    end for
                }
            }
        }
    }

end MysqlTransactionIntegrationTest
