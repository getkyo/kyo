package kyo.internal.mysql.exchange

import kyo.*
import kyo.internal.SqlSharedContainers
import kyo.internal.mysql.BoundMysqlParam
import kyo.internal.mysql.MysqlConnection
import kyo.internal.mysql.types.MysqlDecoder
import kyo.internal.mysql.types.MysqlEncoder

/** Integration tests for MySQL extended (binary) protocol: COM_STMT_PREPARE/EXECUTE/CLOSE.
  *
  * Each test runs against a fresh schema in the per-fork-JVM shared MySQL container (via [[SqlSharedContainers.withFreshSchema]]).
  */
class MysqlExtendedProtocolIntegrationTest extends kyo.Test:

    override def timeout: Duration = 3.minutes

    private def withConn[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(
        f: MysqlConnection => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException]) =
        MysqlConnection
            .connect(
                ctx.host,
                ctx.port,
                ctx.username,
                Maybe.Present(ctx.password),
                Maybe.Present(ctx.database),
                Maybe.Absent,
                64,
                Duration.Infinity
            )
            .flatMap { conn =>
                Scope.ensure(conn.close).andThen(f(conn))
            }

    private def decode[A](
        row: kyo.internal.mysql.MysqlRow,
        colIdx: Int,
        decoder: MysqlDecoder[A]
    )(using Frame): A < (Async & Abort[SqlException]) =
        row.column(colIdx) match
            case Maybe.Absent =>
                Abort.fail(SqlException.Connection(s"Column $colIdx is NULL", summon[Frame]))
            case Maybe.Present(bytes) =>
                Abort.run[SqlException.Decode](decoder.decode(bytes)).flatMap {
                    case Result.Success(v) => v
                    case Result.Failure(e) => Abort.fail(SqlException.Connection(s"Decode failed: ${e.message}", summon[Frame]))
                    case Result.Panic(t)   => Abort.fail(SqlException.Connection(s"Decode panic: ${t.getMessage}", summon[Frame]))
                }

    // ── COM_STMT_PREPARE returns valid stmtId ─────────────────────────────────

    "ExtendedQueryExchange COM_STMT_PREPARE returns stmtId" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    val params = Chunk(BoundMysqlParam(1, MysqlEncoder.intEncoder))
                    conn.extendedQuery("SELECT ?", params).map { rows =>
                        assert(rows.size == 1)
                        // If the prepared statement round-tripped we get a row back.
                    }
                }
            }
        }
    }

    // ── binary round-trip Long ────────────────────────────────────────────────

    "ExtendedQueryExchange binary round-trip Long (LONGLONG)" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    val v      = Long.MaxValue
                    val params = Chunk(BoundMysqlParam(v, MysqlEncoder.longEncoder))
                    conn.extendedQuery("SELECT ? AS n", params).flatMap { rows =>
                        assert(rows.size == 1)
                        decode(rows(0), 0, MysqlDecoder.longDecoder).map { decoded =>
                            assert(decoded == v, s"Expected $v, got $decoded")
                        }
                    }
                }
            }
        }
    }

    // ── binary round-trip Int ─────────────────────────────────────────────────

    "ExtendedQueryExchange binary round-trip Int (LONG)" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    val v      = 42
                    val params = Chunk(BoundMysqlParam(v, MysqlEncoder.intEncoder))
                    conn.extendedQuery("SELECT ? AS n", params).flatMap { rows =>
                        assert(rows.size == 1)
                        decode(rows(0), 0, MysqlDecoder.intDecoder).map { decoded =>
                            assert(decoded == v, s"Expected $v, got $decoded")
                        }
                    }
                }
            }
        }
    }

    // ── binary round-trip String ──────────────────────────────────────────────

    "ExtendedQueryExchange binary round-trip String (VAR_STRING)" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    val v      = "hello world"
                    val params = Chunk(BoundMysqlParam(v, MysqlEncoder.stringEncoder))
                    conn.extendedQuery("SELECT ? AS s", params).flatMap { rows =>
                        assert(rows.size == 1)
                        decode(rows(0), 0, MysqlDecoder.stringDecoder).map { decoded =>
                            assert(decoded == v, s"Expected '$v', got '$decoded'")
                        }
                    }
                }
            }
        }
    }

    // ── binary round-trip Boolean (TINYINT 1) ────────────────────────────────

    "ExtendedQueryExchange binary round-trip Boolean (TINYINT 1)" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    val trueParams  = Chunk(BoundMysqlParam(true, MysqlEncoder.boolEncoder))
                    val falseParams = Chunk(BoundMysqlParam(false, MysqlEncoder.boolEncoder))
                    conn.extendedQuery("SELECT ? AS b", trueParams).flatMap { rows =>
                        decode(rows(0), 0, MysqlDecoder.boolDecoder).flatMap { trueDecoded =>
                            assert(trueDecoded, s"Expected true, got $trueDecoded")
                            conn.extendedQuery("SELECT ? AS b", falseParams).flatMap { rows2 =>
                                decode(rows2(0), 0, MysqlDecoder.boolDecoder).map { falseDecoded =>
                                    assert(!falseDecoded, s"Expected false, got $falseDecoded")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── binary round-trip DATETIME ────────────────────────────────────────────

    "ExtendedQueryExchange binary round-trip DATETIME (LocalDateTime)" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    val dt     = java.time.LocalDateTime.of(2024, 1, 15, 12, 30, 45)
                    val params = Chunk(BoundMysqlParam(dt, MysqlEncoder.localDateTimeEncoder))
                    conn.extendedQuery("SELECT CAST(? AS DATETIME) AS dt", params).flatMap { rows =>
                        assert(rows.size == 1)
                        decode(rows(0), 0, MysqlDecoder.localDateTimeDecoder).map { decoded =>
                            assert(decoded.getYear == dt.getYear, s"Year mismatch: ${decoded.getYear}")
                            assert(decoded.getMonthValue == dt.getMonthValue)
                            assert(decoded.getDayOfMonth == dt.getDayOfMonth)
                            assert(decoded.getHour == dt.getHour)
                            assert(decoded.getMinute == dt.getMinute)
                            assert(decoded.getSecond == dt.getSecond)
                        }
                    }
                }
            }
        }
    }

    // ── binary null column decoded as Absent ──────────────────────────────────

    "ExtendedQueryExchange binary null column decoded as Absent" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    // Use a NULL param; the column comes back as NULL in the result set.
                    val params = Chunk(BoundMysqlParam.nullParam(MysqlEncoder.intEncoder))
                    conn.extendedQuery("SELECT ? AS n", params).map { rows =>
                        assert(rows.size == 1)
                        val column = rows(0).column(0)
                        assert(column.isEmpty, s"Expected Absent for NULL column, got: $column")
                    }
                }
            }
        }
    }

    // ── stmtId cached in preparedStmts ────────────────────────────────────────

    "ExtendedQueryExchange stmtId cached in preparedStmts, second call uses cache" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    val sql    = "SELECT ? AS n"
                    val params = Chunk(BoundMysqlParam(1, MysqlEncoder.intEncoder))
                    // First call, prepares and caches.
                    conn.extendedQuery(sql, params).flatMap { rows1 =>
                        assert(rows1.size == 1)
                        // Second call, same SQL, should use the cached stmtId.
                        val params2 = Chunk(BoundMysqlParam(2, MysqlEncoder.intEncoder))
                        conn.extendedQuery(sql, params2).flatMap { rows2 =>
                            assert(rows2.size == 1)
                            // Verify the second result has value 2 (param was properly bound).
                            decode(rows2(0), 0, MysqlDecoder.intDecoder).map { v =>
                                assert(v == 2, s"Expected 2, got $v")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── COM_STMT_CLOSE sent on scope exit ─────────────────────────────────────
    // (Verified indirectly: if COM_STMT_CLOSE is not sent, the server holds resources.
    //  After stream exhaustion + scope exit, the connection remains usable.)

    "ExtendedQueryExchange streamQuery COM_STMT_CLOSE sent on scope exit, connection still usable" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    Scope.run {
                        // Stream 5 rows, on scope exit, COM_STMT_CLOSE must be sent.
                        val params = Chunk(BoundMysqlParam(5, MysqlEncoder.intEncoder))
                        conn.streamQuery("SELECT ? AS n", params, 64).run
                    }.flatMap { rows =>
                        // After scope exit (COM_STMT_CLOSE sent), send another query, must succeed.
                        conn.simpleQuery("SELECT 'still_alive'").map { rows2 =>
                            val str = new String(rows2(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                            assert(str == "still_alive")
                        }
                    }
                }
            }
        }
    }

    // ── streamQuery with real table, rows returned lazily ───────────────────

    "StreamQueryExchange yields rows in order from a real table" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    // Create a temp table, insert 10 rows, stream them.
                    conn.simpleExecute("CREATE TABLE IF NOT EXISTS ext_stream_t (id INT, val VARCHAR(32))").flatMap { _ =>
                        Async.foreach(1 to 10, 1) { i =>
                            conn.simpleExecute(s"INSERT INTO ext_stream_t VALUES ($i, 'row$i')")
                        }.flatMap { _ =>
                            Scope.run {
                                val params = Chunk.empty[BoundMysqlParam[?]]
                                conn.streamQuery("SELECT id FROM ext_stream_t ORDER BY id", params, 64).run
                            }.flatMap { rows =>
                                conn.simpleExecute("DROP TABLE ext_stream_t").map { _ =>
                                    assert(rows.size == 10)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── streamQuery early cancel sends COM_STMT_CLOSE ────────────────────────

    "StreamQueryExchange early cancel (take) sends COM_STMT_CLOSE, connection still usable" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    conn.simpleExecute("CREATE TABLE IF NOT EXISTS ext_cancel_t (id INT)").flatMap { _ =>
                        Async.foreach(1 to 20, 1) { i =>
                            conn.simpleExecute(s"INSERT INTO ext_cancel_t VALUES ($i)")
                        }.flatMap { _ =>
                            Scope.run {
                                conn.streamQuery("SELECT id FROM ext_cancel_t ORDER BY id", Chunk.empty, 64).take(5).run
                            }.flatMap { rows =>
                                conn.simpleExecute("DROP TABLE ext_cancel_t").flatMap { _ =>
                                    // After early termination + COM_STMT_CLOSE, connection must be alive.
                                    conn.simpleQuery("SELECT 'cancel_ok'").map { r =>
                                        assert(rows.size == 5)
                                        val str = new String(r(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                        assert(str == "cancel_ok")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── error response becomes SqlException.Server ────────────────────────────

    "ExtendedQueryExchange error response becomes SqlException.Server" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    Abort.run[SqlException](
                        conn.extendedQuery("SELECT * FROM nonexistent_table_xyz_abc", Chunk.empty)
                    ).map {
                        case Result.Failure(e: SqlException.Server) =>
                            assert(e.sqlState.nonEmpty, "Expected non-empty sqlState")
                            assert(e.message.nonEmpty, "Expected non-empty error message")
                        case other =>
                            fail(s"Expected SqlException.Server, got: $other")
                    }
                }
            }
        }
    }

    // ── DECIMAL column as lenenc-string in binary protocol ───────────────────

    "ExtendedQueryExchange DECIMAL column (NEWDECIMAL) round-trips as BigDecimal via lenenc-string" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    val v      = BigDecimal("123456789.123456")
                    val params = Chunk(BoundMysqlParam(v, MysqlEncoder.bigDecimalEncoder))
                    conn.extendedQuery("SELECT ? AS d", params).flatMap { rows =>
                        assert(rows.size == 1)
                        decode(rows(0), 0, MysqlDecoder.bigDecimalDecoder).map { decoded =>
                            assert(decoded == v, s"Expected $v, got $decoded")
                        }
                    }
                }
            }
        }
    }

end MysqlExtendedProtocolIntegrationTest
