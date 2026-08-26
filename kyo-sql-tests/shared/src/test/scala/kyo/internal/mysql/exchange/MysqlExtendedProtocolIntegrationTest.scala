package kyo.internal.mysql.exchange

import kyo.*
import kyo.internal.SqlSharedContainers
import kyo.internal.mysql.BoundMysqlParam
import kyo.internal.mysql.MysqlColumnToken
import kyo.internal.mysql.MysqlConnection
import kyo.internal.mysql.MysqlRowCodec
import kyo.internal.mysql.types.MysqlEncoder

/** Integration tests for MySQL extended (binary) protocol: COM_STMT_PREPARE/EXECUTE/CLOSE.
  *
  * Each test runs against a fresh schema in the per-fork-JVM shared MySQL container (via [[SqlSharedContainers.withFreshSchema]]).
  */
class MysqlExtendedProtocolIntegrationTest extends SqlContainerTest:

    override def timeout: Duration = 3.minutes

    private def withConn[A, S](
        ctx: SqlSharedContainers.SchemaCtx,
        preparedStmtCacheSize: Int = 64
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
                preparedStmtCacheSize,
                Duration.Infinity
            )
            .flatMap { conn =>
                Scope.ensure(conn.close).andThen(f(conn))
            }

    /** This session's live server-side prepared statements, over the simple-query protocol.
      *
      * `simpleQuery` because `extendedQuery` would prepare a statement of its own, take a cache slot, and evict a neighbour whose
      * `COM_STMT_CLOSE` does not go out until the next extended request, so a probe built on it reads `cacheSize + 1` and misreports a
      * working eviction path as broken by one. The thread id is selected in a derived table rather than compared in a `WHERE` clause so
      * that `sys.ps_thread_id` is evaluated even when the table is empty: against a server with `performance_schema` off the predicate form
      * is never evaluated and the probe answers a silent 0, while this form raises 1683 and names the reason.
      *
      * The count is read as a `Long` rather than as text. [[decode]] goes through the row's own codec, which resolves the wire format and
      * the column type before reading, so the SQL type is what the leaf asks for; a binary-only decoder would take a simple-query row's
      * ASCII `"2"` to the eight-byte little-endian reader and fail.
      */
    private def liveStmtCount(conn: MysqlConnection)(using Frame): Long < (Async & Abort[SqlException]) =
        conn.simpleQuery(
            "SELECT (SELECT COUNT(*) FROM performance_schema.prepared_statements_instances " +
                "WHERE OWNER_THREAD_ID = t.tid) FROM (SELECT sys.ps_thread_id(connection_id()) AS tid) t"
        ).flatMap { rows =>
            if rows.isEmpty then (0L: Long)
            else decode[Long](rows(0), 0)
        }

    /** Decodes column `colIdx` of `row` the way a query result is decoded.
      *
      * `MysqlRowCodec.row` builds the neutral row the codec layer builds, whose `decode` resolves the wire format and the column's own type
      * and UNSIGNED flag first, so each leaf below asserts what a caller's query returns. A decoder that read a fixed number of
      * little-endian bytes whatever the format was would answer wrongly for a text-protocol row.
      */
    private def decode[A](
        row: kyo.internal.mysql.MysqlRow,
        colIdx: Int
    )(using Frame, SqlSchema[A]): A < (Async & Abort[SqlException]) =
        MysqlRowCodec.row(row).decode[A](colIdx)

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
                        decode[Long](rows(0), 0).map { decoded =>
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
                        decode[Int](rows(0), 0).map { decoded =>
                            assert(decoded == v, s"Expected $v, got $decoded")
                        }
                    }
                }
            }
        }
    }

    // ── the column definitions a row carries ──────────────────────────────────

    /** A row must carry the column definitions the EXECUTE response sent, not the ones COM_STMT_PREPARE sent.
      *
      * MySQL describes the result column of a bare placeholder at prepare time, before any parameter is bound, and reports `VAR_STRING`
      * for it. At execute time it resends the definitions and sends the value in the bound parameter's own binary form: an `Int` parameter
      * comes back as the eight little-endian bytes `2a 00 00 00 00 00 00 00` under a `LONGLONG` definition. A row built from the
      * prepare-time definitions therefore announces `VAR_STRING` over a binary integer, and every decoder that resolves the wire
      * representation from the column's type resolves it from a type the value does not have. The row payload was already parsed with the
      * execute-time types, so the two disagreed inside one row.
      */
    "ExtendedQueryExchange rows carry the execute-time column definitions" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    val params = Chunk(BoundMysqlParam(42, MysqlEncoder.intEncoder))
                    conn.extendedQuery("SELECT ? AS declared", params).flatMap { rows =>
                        val neutral   = MysqlRowCodec.row(rows(0))
                        val typeByte  = MysqlColumnToken.columnType(neutral.columns(0).typeToken)
                        val byteCount = neutral.column(0).fold(0)(_.size)
                        assert(
                            typeByte != MysqlEncoder.TYPE_VAR_STRING,
                            s"the column announced VAR_STRING over a $byteCount-byte binary payload, which is the prepare-time definition"
                        )
                        assert(
                            typeByte == MysqlEncoder.TYPE_LONGLONG,
                            s"expected the execute-time LONGLONG definition, got type byte 0x${typeByte.toHexString}"
                        )
                        // The consequence a caller sees: an integer column read as text is refused rather than answering its bytes.
                        Abort.run[SqlException](neutral.decode[String](0)).map {
                            case Result.Failure(e: SqlDecodeColumnTypeMismatchException) =>
                                assert(e.scalaType == "String", s"the failure must name the type that asked, got ${e.scalaType}")
                                assert(e.columnType == "BIGINT", s"the failure must name the column's type, got ${e.columnType}")
                            case other => assert(false, s"expected a type-mismatch abort reading the integer column as String, got $other")
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
                        decode[String](rows(0), 0).map { decoded =>
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
                        decode[Boolean](rows(0), 0).flatMap { trueDecoded =>
                            assert(trueDecoded, s"Expected true, got $trueDecoded")
                            conn.extendedQuery("SELECT ? AS b", falseParams).flatMap { rows2 =>
                                decode[Boolean](rows2(0), 0).map { falseDecoded =>
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
                        decode[java.time.LocalDateTime](rows(0), 0).map { decoded =>
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
                            decode[Int](rows2(0), 0).map { v =>
                                assert(v == 2, s"Expected 2, got $v")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── the connection survives a fully consumed stream ───────────────────────
    // What this pins is that scope exit leaves the wire on a clean boundary. It says nothing about
    // COM_STMT_CLOSE: connection usability cannot distinguish a close that was sent from one that was
    // not. The statement's own lifetime is asserted by the cache-reuse leaf below.

    "StreamQueryExchange scope exit leaves the connection usable after a fully consumed stream" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    Scope.run {
                        val params = Chunk(BoundMysqlParam(5, MysqlEncoder.intEncoder))
                        conn.streamQuery("SELECT ? AS n", params, 64).run
                    }.flatMap { rows =>
                        // After scope exit the wire must be on a clean boundary, so a further command succeeds.
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

    // ── early termination drains the unread rows ──────────────────────────────
    // Nothing here observes COM_STMT_CLOSE either. What the body verifies is the drain: `.take(5)` over 20
    // rows leaves no unread packets behind, which is what makes the following command succeed.

    "StreamQueryExchange early termination drains the unread rows, connection still usable" in {
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
                                    // The drain consumed the 15 unread rows, so the wire is on a clean boundary.
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

    // ── a terminator larger than the old size heuristic ───────────────────────

    "ExtendedQueryExchange reads a binary result set whose OK terminator exceeds the old 9-byte heuristic" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    // The terminator of a binary result set is an OK packet under CLIENT_DEPRECATE_EOF, and it grows
                    // past 7 bytes when it carries warnings, an info string or a session-state block. A reader that
                    // identified it with `firstByte == 0xfe && payload.size < 9` takes an oversized one for a row and
                    // rejects it with "expected OK / ERR / row, received byte 0xfe".
                    //
                    // This query is a KNOWN producer of that shape rather than a guess: it is the probe
                    // `PreparedStmtEvictionIntegrationTest` uses, and reading a `sys` routine is what pushes the
                    // terminator over the threshold. So the leaf asserts the read succeeds rather than asserting the
                    // count, which belongs to the eviction suite.
                    conn.extendedQuery(
                        "SELECT COUNT(*) FROM performance_schema.prepared_statements_instances " +
                            "WHERE OWNER_THREAD_ID = sys.ps_thread_id(connection_id())",
                        Chunk.empty
                    ).flatMap { rows =>
                        assert(rows.size == 1, s"the probe returns exactly one COUNT(*) row, got ${rows.size}")
                        decode[Long](rows(0), 0).flatMap { count =>
                            assert(count >= 0L, s"COUNT(*) is non-negative, got $count")
                            // The connection must remain usable: a terminator misread as a row leaves the wire dirty.
                            conn.simpleQuery("SELECT 'terminator_ok'").map { r =>
                                val str = new String(r(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                assert(str == "terminator_ok", s"the wire must be on a clean boundary, got $str")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── a stream must not destroy the statement the cache still advertises ────

    "StreamQueryExchange leaves the cached statement usable, on the stream path and the query path" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    val sql            = "SELECT ? AS n"
                    def params(v: Int) = Chunk(BoundMysqlParam(v, MysqlEncoder.intEncoder))
                    // The first stream prepares the statement and `prepareStmt` caches it under this SQL.
                    Scope.run(conn.streamQuery(sql, params(1), 64).run).flatMap { first =>
                        assert(first.size == 1, s"the first stream must yield its row, got ${first.size}")
                        // The second stream of the SAME SQL resolves that cache entry, so it executes the id the
                        // first stream prepared. A scope exit that closed that id would make the server answer
                        // "Unknown prepared statement handler" here, in the default configuration, with no early
                        // termination involved.
                        Scope.run(conn.streamQuery(sql, params(2), 64).run).flatMap { second =>
                            assert(second.size == 1, s"streaming the same SQL twice must work, got ${second.size}")
                            decode[Int](second(0), 0).flatMap { streamed =>
                                assert(streamed == 2, s"the second stream must bind its own parameter, got $streamed")
                                // The reach is not stream-specific: `extendedQuery` resolves the same entry through
                                // the same `prepareStmt`, so a plain query reusing streamed SQL meets the same closed
                                // id, which is what this second observation pins.
                                conn.extendedQuery(sql, params(3)).flatMap { rows =>
                                    assert(rows.size == 1, s"a query reusing streamed SQL must work, got ${rows.size}")
                                    decode[Int](rows(0), 0).map { queried =>
                                        assert(queried == 3, s"the query must bind its own parameter, got $queried")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "cache eviction closes statements the stream path prepared, which is what replaced the stream's own COM_STMT_CLOSE" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                // The leaf above proves the stream does not close a statement the cache still advertises. This one
                // proves the other half, that something still closes it, because those two together are the whole
                // claim: a stream's statement is released by cache eviction and by nothing else. The eviction
                // suite covers the same mechanism reached from `extendedQuery`; this reaches it from the stream, and
                // the entry point is the only difference, since `StreamQueryExchange` resolves through the same
                // `ExtendedQueryExchange.prepareStmt` against the same per-connection cache.
                withConn(ctx, preparedStmtCacheSize = 2) { conn =>
                    val streamed = Chunk("SELECT 1", "SELECT 2", "SELECT 3", "SELECT 4", "SELECT 5", "SELECT 6")
                    // Each stream is scoped so it finishes and releases before the next begins, and each is drained
                    // to completion so nothing here depends on early termination.
                    Kyo.foreachDiscard(streamed) { sql =>
                        Scope.run(conn.streamQuery(sql, Chunk.empty, 64).run).unit
                    }.andThen(
                        // Two extended requests to flush: the first sends the closes the stream loop queued and
                        // evicts once more, the second sends that one. `drainPendingCloses` runs at the START of an
                        // extended request, so a queued close needs a following request to reach the wire.
                        conn.extendedQuery("SELECT 99", Chunk.empty)
                    ).andThen(
                        conn.extendedQuery("SELECT 99", Chunk.empty)
                    ).andThen(liveStmtCount(conn)).map { live =>
                        assert(
                            live == 2,
                            s"six streamed statements against a size-2 cache must leave exactly 2 alive on the server, got $live"
                        )
                    }
                }
            }
        }
    }

    // ── mid-stream server error surfaces promptly ─────────────────────────────

    "StreamQueryExchange mid-stream server error is a prompt typed failure, not a hung cleanup" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    conn.simpleExecute("CREATE TABLE IF NOT EXISTS stream_err_t (id INT)").flatMap { _ =>
                        Async.foreach(1 to 5, 1) { i =>
                            conn.simpleExecute(s"INSERT INTO stream_err_t VALUES ($i)")
                        }.flatMap { _ =>
                            // The correlated scalar subquery is single-row for t.id = 1 and multi-row from t.id = 2
                            // on, so the server streams one row and then replaces the next row packet with ERR 1242.
                            // An ERR terminates the result set: nothing follows it on the wire, so the failure must
                            // reach the caller promptly. The 3 second ceiling converts the regression (the cleanup
                            // draining an idle wire until socketTimeout, which defaults to Infinity) into a typed
                            // failure instead of a suite hang.
                            val sql =
                                "SELECT (SELECT s.id FROM stream_err_t s WHERE s.id <= t.id) FROM stream_err_t t ORDER BY t.id"
                            Abort.run[Timeout](
                                Abort.run[SqlException](Scope.run(conn.streamQuery(sql, Chunk.empty, 64).run))
                            ).flatMap {
                                case Result.Failure(_: Timeout) =>
                                    fail("a mid-stream ERR must surface as a typed failure; the cleanup is draining an idle wire")
                                case Result.Success(Result.Failure(e: SqlServerException)) =>
                                    assert(e.extra.get("code").contains("1242"), s"expected MySQL error 1242, got ${e.extra}")
                                    // The ERR ended the result set, so the wire is clean and the session usable.
                                    conn.simpleQuery("SELECT 'err_ok'").flatMap { r =>
                                        conn.simpleExecute("DROP TABLE stream_err_t").map { _ =>
                                            val str =
                                                new String(r(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                            assert(str == "err_ok")
                                        }
                                    }
                                case other =>
                                    fail(s"expected a typed 1242 from the stream, got: $other")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── error response becomes SqlServerException ────────────────────────────

    "ExtendedQueryExchange error response becomes SqlServerException" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withConn(ctx) { conn =>
                    Abort.run[SqlException](
                        conn.extendedQuery("SELECT * FROM nonexistent_table_xyz_abc", Chunk.empty)
                    ).map {
                        case Result.Failure(e: SqlServerException) =>
                            // A missing table is ER_NO_SUCH_TABLE, fixed by the engine rather than by server state,
                            // so the exact SQLSTATE and code are knowable here. Asserting only that some SQLSTATE
                            // arrived would pass for a connection that failed for an entirely different reason.
                            assert(e.sqlState == "42S02", s"expected SQLSTATE 42S02 (ER_NO_SUCH_TABLE), got '${e.sqlState}'")
                            assert(e.extra.get("code").contains("1146"), s"expected MySQL error 1146, got ${e.extra}")
                        case other =>
                            fail(s"Expected SqlServerException, got: $other")
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
                        decode[BigDecimal](rows(0), 0).map { decoded =>
                            assert(decoded == v, s"Expected $v, got $decoded")
                        }
                    }
                }
            }
        }
    }

end MysqlExtendedProtocolIntegrationTest
