package kyo.mysql

import kyo.*
import kyo.OwnContainer

/** Integration tests for MySQL LOAD DATA LOCAL INFILE via stream upload.
  *
  * Leaves 1-7, 9: use the `local_infile=1` container. Leaf 8: uses a separate `local_infile=0` container.
  *
  * Both containers are lazily started by per-class CAS-singletons (see [[localInfileOnRef]] and [[localInfileOffRef]]) and survive the test
  * class. Orphan reaping is handled by the sbt `Test / testOptions` setup task that removes containers labelled `kyo-sql-singleton` between
  * test invocations. MySQL startup costs ~30-60 s; reusing the container amortises that cost.
  */
class LocalInfileIntegrationTest extends kyo.Test:

    override def timeout: Duration = 3.minutes

    import LocalInfileIntegrationTest.*

    // ── Connection helpers ────────────────────────────────────────────────────

    private def myUrl(ctx: MysqlCtx): String =
        s"mysql://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}"

    private def withMyClient[A, S](
        f: SqlClient.Mysql => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException | ContainerException]) =
        withLocalInfileOn { ctx =>
            SqlClient.initMysqlWith(
                myUrl(ctx),
                SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
            )(f)
        }

    /** Creates the target table and returns its name.  Drops the table on scope exit. */
    private def withLoadTable[A, S](
        client: SqlClient,
        columnDefs: String = "id INT, name VARCHAR(255)"
    )(f: String => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Scope & Abort[SqlException]) =
        Random.nextLong.flatMap { rnd =>
            val tableName = s"infile_test_${rnd.toHexString}"
            client.executeRaw(s"CREATE TABLE $tableName ($columnDefs)").flatMap { _ =>
                Scope.ensure(Abort.run(client.executeRaw(s"DROP TABLE IF EXISTS $tableName")).unit).andThen {
                    f(tableName)
                }
            }
        }
    end withLoadTable

    /** Builds an in-memory CSV `Stream[Byte, Any]` from a sequence of rows (already newline-terminated). */
    private def csvStream(rows: Seq[String]): Stream[Byte, Any] =
        val bytes = rows.mkString.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        Stream.init(Chunk.from(bytes).toSeq)

    // ── Leaf 1: 10 MB stream ──────────────────────────────────────────────────

    "streams a 10 MB span into a target table".tagged("kyo.OwnContainer") in {
        Async.timeout(60.seconds) {
            Scope.run {
                withMyClient { client =>
                    withLoadTable(client) { tbl =>
                        // 10 MB CSV: 10000 rows, each ~1 KB of data
                        val rowCount = 10000
                        val rows     = (1 to rowCount).map(i => s"$i,${("x" * 1020)}\n")
                        val sql =
                            s"LOAD DATA LOCAL INFILE 'data.csv' INTO TABLE $tbl FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n' (id, name)"
                        client.loadLocalInfile(sql, csvStream(rows)).flatMap { affected =>
                            assert(affected == rowCount.toLong, s"Expected $rowCount affected rows, got $affected")
                            client.query(s"SELECT COUNT(*) FROM $tbl").flatMap { result =>
                                result.head.decode[Long](0).map { count =>
                                    assert(count == rowCount.toLong, s"Expected $rowCount rows in table, got $count")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 2: empty stream ──────────────────────────────────────────────────

    "streams an empty stream and reports 0 affected rows".tagged("kyo.OwnContainer") in {
        Async.timeout(60.seconds) {
            Scope.run {
                withMyClient { client =>
                    withLoadTable(client) { tbl =>
                        val sql =
                            s"LOAD DATA LOCAL INFILE 'empty.csv' INTO TABLE $tbl FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n' (id, name)"
                        client.loadLocalInfile(sql, Stream.empty[Byte]).map { affected =>
                            assert(affected == 0L, s"Expected 0 affected rows for empty stream, got $affected")
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 3: Path.readBytesStream ─────────────────────────────────────────

    "streams from Path.readBytes".tagged("kyo.OwnContainer") in {
        Async.timeout(60.seconds) {
            Scope.run {
                withMyClient { client =>
                    withLoadTable(client) { tbl =>
                        // Create a temp file with CSV data, use Path.readBytesStream to upload it.
                        Path.temp(prefix = "kyo-infile", suffix = ".csv").flatMap { tmpPath =>
                            Scope.ensure(Abort.run(tmpPath.remove).unit).andThen {
                                val csv = "1,alice\n2,bob\n3,carol\n"
                                tmpPath.writeBytes(Span.from(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))).flatMap { _ =>
                                    val sql =
                                        s"LOAD DATA LOCAL INFILE '${tmpPath.toString}' INTO TABLE $tbl FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n' (id, name)"
                                    Scope.run {
                                        client.loadLocalInfile(sql, tmpPath.readBytesStream)
                                    }.flatMap { affected =>
                                        assert(affected == 3L, s"Expected 3 affected rows, got $affected")
                                        client.query(s"SELECT COUNT(*) FROM $tbl").flatMap { result =>
                                            result.head.decode[Long](0).map { count =>
                                                assert(count == 3L, s"Expected 3 rows in table, got $count")
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
    }

    // ── Leaf 4: stream failure mid-transfer ───────────────────────────────────

    "stream failure mid-transfer aborts the load and surfaces the underlying error".tagged("kyo.OwnContainer") in {
        Async.timeout(60.seconds) {
            Scope.run {
                withMyClient { client =>
                    withLoadTable(client) { tbl =>
                        // Build a stream that emits some data and then fails.
                        case class UploadError(msg: String)
                        val goodBytes = "1,alice\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        // Stream: emit good bytes, then abort with UploadError.
                        val failingStream: Stream[Byte, Abort[UploadError]] =
                            Stream.init[Byte, Abort[UploadError]](Chunk.from(goodBytes).toSeq)
                                .concat(Stream[Byte, Abort[UploadError]](Abort.fail(UploadError("synthetic mid-transfer error"))))
                        val sql =
                            s"LOAD DATA LOCAL INFILE 'fail.csv' INTO TABLE $tbl FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n' (id, name)"
                        // Run loadLocalInfile; the UploadError should propagate.
                        Abort.run[UploadError](
                            Abort.run[SqlException](
                                client.loadLocalInfile(sql, failingStream)
                            )
                        ).flatMap {
                            case Result.Failure(err: UploadError) =>
                                assert(err.msg == "synthetic mid-transfer error", s"Expected UploadError, got: $err")
                            case Result.Success(Result.Failure(sqle)) =>
                                // Server may reject the upload if it received no data, also acceptable.
                                // But a SqlException is not the primary expected outcome; the stream error is.
                                assert(false, s"Expected UploadError to propagate, got SqlException: $sqle")
                            case Result.Success(Result.Success(_)) =>
                                // If load succeeded, the stream error wasn't surfaced, fail the test.
                                assert(false, "Expected stream error to propagate, but upload succeeded")
                            case Result.Success(Result.Panic(t)) =>
                                Log.error(s"[test] Unexpected panic: ${t.getMessage}").andThen(
                                    assert(false, s"Unexpected panic: ${t.getMessage}")
                                )
                            case Result.Panic(t) =>
                                Log.error(s"[test] Unexpected outer panic: ${t.getMessage}").andThen(
                                    assert(false, s"Unexpected outer panic: ${t.getMessage}")
                                )
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 5: server-side rejection (bad column in column list) ───────────

    "server-side rejection (bad column reference) surfaces SqlException.Server".tagged("kyo.OwnContainer") in {
        Async.timeout(60.seconds) {
            Scope.run {
                withMyClient { client =>
                    // Reference a non-existent column in the LOAD DATA column list.
                    // MySQL resolves the column list at parse / validation time, before sending the
                    // LOCAL_INFILE_REQUEST (0xFB), so it returns an ERR packet immediately.
                    // This exercises the 0xFF branch in SimpleQueryExchange.runLocalInfile and
                    // surfaces a SqlException.Server with ER_BAD_FIELD_ERROR (error 1054).
                    withLoadTable(client) { tbl =>
                        val sql =
                            s"LOAD DATA LOCAL INFILE 'x.csv' INTO TABLE $tbl " +
                                "FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n' (id, name, nonexistent_column)"
                        Abort.run[SqlException](
                            client.loadLocalInfile(sql, csvStream(Seq("1,row,extra\n")))
                        ).flatMap {
                            case Result.Failure(e: SqlException.Server) =>
                                // ER_BAD_FIELD_ERROR (1054), unknown column reference.
                                val code = e.extra.getOrElse("code", "")
                                assert(
                                    code == "1054",
                                    s"Expected MySQL error 1054 (ER_BAD_FIELD_ERROR) " +
                                        s"but got code=$code, message=${e.message}"
                                )
                            case Result.Failure(e) =>
                                assert(false, s"Expected SqlException.Server but got ${e.getClass.getSimpleName}: $e")
                            case Result.Success(affected) =>
                                assert(
                                    false,
                                    s"Expected SqlException.Server from bad column, but upload succeeded with affected=$affected"
                                )
                            case Result.Panic(t) =>
                                Log.error(s"[test] Unexpected panic: ${t.getMessage}").andThen(
                                    assert(false, s"Unexpected panic: ${t.getMessage}")
                                )
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 6: 50 MB multi-packet boundary ──────────────────────────────────

    "50 MB stream splits into multiple LOCAL_INFILE_DATA packets and reports correct row count".tagged("kyo.OwnContainer") in {
        Async.timeout(60.seconds) {
            Scope.run {
                withMyClient { client =>
                    withLoadTable(client) { tbl =>
                        // Build a ~50 MB stream: ~50000 rows each ~1 KB
                        // MaxPayload = 16777215 bytes (~16 MB), so 50 MB forces 4 packets
                        val rowCount = 50000
                        val rows     = (1 to rowCount).map(i => s"$i,${("y" * 1020)}\n")
                        val sql =
                            s"LOAD DATA LOCAL INFILE 'big.csv' INTO TABLE $tbl FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n' (id, name)"
                        client.loadLocalInfile(sql, csvStream(rows)).flatMap { affected =>
                            assert(affected == rowCount.toLong, s"Expected $rowCount affected rows for 50 MB upload, got $affected")
                            client.query(s"SELECT COUNT(*) FROM $tbl").flatMap { result =>
                                result.head.decode[Long](0).map { count =>
                                    assert(count == rowCount.toLong, s"Expected $rowCount rows in table after 50 MB upload, got $count")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 7: cancellation mid-transfer ────────────────────────────────────

    "cancellation mid-transfer terminates load; connection is reusable for follow-up SELECT".tagged("kyo.OwnContainer") in {
        Async.timeout(60.seconds) {
            Scope.run {
                withMyClient { client =>
                    withLoadTable(client) { tbl =>
                        // Build an effectively-infinite stream: repeated 1 MB chunks.
                        // The upload will never finish on its own.
                        val oneMB = Chunk.fill(1024 * 1024)(0x41.toByte) // 1 MB of 'A'
                        val infStream: Stream[Byte, Any] = Stream[Byte, Any](Loop.foreach {
                            Emit.valueWith(oneMB)(Loop.continue)
                        })
                        val sql =
                            s"LOAD DATA LOCAL INFILE 'inf.csv' INTO TABLE $tbl FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n' (id, name)"
                        // Use a 2-second timeout, shorter than the infinite upload.
                        // LocalInfileExchange.run registers a Scope.ensure that sends the empty terminator
                        // and drains the server response on any error exit, including Timeout cancellation.
                        // Scope.run inside MysqlConnection.loadLocalInfile discharges that finalizer before
                        // the Timeout propagates, leaving the connection in a clean state.
                        val uploadResult = Abort.run[SqlException | Timeout](
                            Async.timeout(2.seconds)(
                                client.loadLocalInfile(sql, infStream)
                            )
                        )
                        uploadResult.flatMap { result =>
                            // The upload must have timed out, Async.timeout(2.seconds) over an infinite stream
                            // MUST fire Timeout. Panic here would indicate a production bug.
                            val checkTimeout: Unit < (Sync & Async) =
                                result match
                                    case Result.Failure(_: Timeout) => Kyo.unit
                                    case Result.Failure(e) =>
                                        Sync.defer(assert(false, s"Expected Timeout but got ${e.getClass.getSimpleName}: $e")).unit
                                    case Result.Success(_) =>
                                        Sync.defer(assert(false, "Expected Timeout but upload succeeded")).unit
                                    case Result.Panic(t) =>
                                        Log.error(s"[test] Panic after cancellation: ${t.getMessage}").andThen(
                                            Sync.defer(assert(false, s"Panic after cancellation: ${t.getMessage}")).unit
                                        )
                            checkTimeout.andThen {
                                // After cancellation, try the SAME conn for a follow-up SELECT.
                                //
                                // Kyo's Async.timeout interrupts the inner fiber and returns as soon as the
                                // interrupt is acknowledged, without waiting for Scope.ensure finalizers to
                                // complete. The finalizer that sends the empty terminator and drains the server
                                // response may still be running concurrently when we arrive here.
                                //
                                // Three valid outcomes for the follow-up SELECT on the SAME conn:
                                //   (a) Cleanup succeeded (before or concurrently) → connection is clean →
                                //       SELECT 42 returns "42".
                                //   (b) Cleanup failed → channel marked corrupted → SELECT 42 fails with
                                //       SqlException.Connection containing "unusable".
                                //   (c) Cleanup is racing concurrently → protocol state unknown → SELECT 42
                                //       fails with SqlException.Server or SqlException.Connection (e.g.,
                                //       "Got packets out of order", "Connection closed while reading", etc.).
                                //
                                // The ONLY banned outcome is a Panic, an unexpected exception indicates a
                                // production bug. Any SqlException is an expected, honest failure.
                                Abort.run[SqlException](client.query("SELECT 42")).flatMap {
                                    case Result.Success(rows) =>
                                        rows.head.decode[Long](0).map { v =>
                                            assert(v == 42L, s"Connection reused cleanly after Timeout, got $v")
                                        }
                                    case Result.Failure(e: SqlException.Connection)
                                        if e.getMessage != null && e.getMessage.contains("unusable") =>
                                        // Cleanup failed → channel marked corrupted via markCorrupted() → connection is
                                        // explicitly NOT reusable. This is a SAFE failure: the caller knows the connection
                                        // is bad. The pool must evict it.
                                        succeed
                                    case Result.Failure(other) =>
                                        // Any other SqlException indicates the production code allowed observable protocol
                                        // corruption (race between Async.timeout interrupt and Scope.ensure cleanup). This
                                        // is UNSAFE, the caller sees a confusing error from a known-broken state instead
                                        // of either a clean connection or an explicit "unusable" signal.
                                        // Fix: ensure cleanup runs to completion before the cancellation propagates, OR
                                        // ensure markCorrupted always fires when cleanup is racing.
                                        assert(false, s"Unsafe race: expected success or Connection('unusable'), got: $other")
                                    case Result.Panic(t) =>
                                        Log.error(s"[test] Unexpected panic on follow-up SELECT: ${t.getMessage}").andThen(
                                            assert(false, s"Unexpected panic on follow-up SELECT: ${t.getMessage}")
                                        )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 8: server with local_infile=OFF ──────────────────────────────────

    "server with local_infile=OFF rejects LOCAL INFILE with SqlException.Server".tagged("kyo.OwnContainer") in {
        Async.timeout(120.seconds) {
            Scope.run {
                withLocalInfileOff { ctx =>
                    SqlClient.initMysqlWith(
                        myUrl(ctx),
                        SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                    ) { client =>
                        val sql = s"LOAD DATA LOCAL INFILE 'x.csv' INTO TABLE t (id)"
                        Abort.run[SqlException](
                            client.loadLocalInfile(sql, csvStream(Seq("1\n")))
                        ).flatMap {
                            case Result.Failure(e: SqlException.Server) =>
                                // MySQL rejects LOAD DATA LOCAL when local_infile=OFF:
                                //   MySQL 5.x: error 1148 (ER_NOT_ALLOWED_COMMAND)
                                //   MySQL 8+:  error 3948 (ER_CLIENT_LOCAL_INFILES_NOT_ALLOWED)
                                // Both are correct; assert one of the two known codes.
                                val code = e.extra.getOrElse("code", "")
                                assert(
                                    code == "1148" || code == "3948",
                                    s"Expected MySQL error 1148 or 3948 (local_infile disabled) but got code=$code, message=${e.message}"
                                )
                            case Result.Failure(e) =>
                                assert(
                                    false,
                                    s"Expected SqlException.Server (ER_NOT_ALLOWED_COMMAND / code 1148) but got ${e.getClass.getSimpleName}: $e"
                                )
                            case Result.Success(_) =>
                                assert(false, "Expected server to reject LOCAL INFILE when local_infile=OFF")
                            case Result.Panic(t) =>
                                Log.error(s"[test] Unexpected panic: ${t.getMessage}").andThen(
                                    assert(false, s"Unexpected panic: ${t.getMessage}")
                                )
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 9: mid-stream failure leaves connection reusable ─────────────────

    "mid-stream failure leaves the connection reusable".tagged("kyo.OwnContainer") in {
        Async.timeout(60.seconds) {
            Scope.run {
                withMyClient { client =>
                    withLoadTable(client) { tbl =>
                        // Build a stream that emits one row then fails.
                        case class MidFailure(msg: String)
                        val headerBytes = "1,test\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        val failingStream: Stream[Byte, Abort[MidFailure]] =
                            Stream.init[Byte, Abort[MidFailure]](Chunk.from(headerBytes).toSeq)
                                .concat(Stream[Byte, Abort[MidFailure]](Abort.fail(MidFailure("mid-stream failure"))))
                        val sql =
                            s"LOAD DATA LOCAL INFILE 'mid.csv' INTO TABLE $tbl FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n' (id, name)"
                        // Run the failing upload.
                        Abort.run[MidFailure](
                            Abort.run[SqlException](
                                client.loadLocalInfile(sql, failingStream)
                            )
                        ).flatMap { uploadResult =>
                            // The stream error must have propagated.
                            assert(
                                uploadResult.isFailure,
                                s"Expected MidFailure to propagate, got: $uploadResult"
                            )
                            // Now verify the connection is still reusable for a follow-up SELECT.
                            client.query("SELECT 99").flatMap { rows =>
                                rows.head.decode[Long](0).map { v =>
                                    assert(v == 99L, s"Connection should be reusable after mid-stream failure, got $v")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end LocalInfileIntegrationTest

object LocalInfileIntegrationTest:

    /** Connection details for a shared MySQL container. */
    final case class MysqlCtx(host: String, port: Int, user: String, password: String, db: String)

    private type MysqlPromise = Promise[MysqlCtx, Abort[ContainerException]]

    // Unsafe boundary: module-load AtomicRef init (no live Frame yet).
    private val localInfileOnRef: AtomicRef[Maybe[MysqlPromise]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Maybe[MysqlPromise]](Maybe.empty).safe

    // Unsafe boundary: module-load AtomicRef init (no live Frame yet).
    private val localInfileOffRef: AtomicRef[Maybe[MysqlPromise]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Maybe[MysqlPromise]](Maybe.empty).safe

    /** Acquires the shared `local_infile=1` MySQL container, lazily starting it on first call. */
    def withLocalInfileOn[A, S](f: MysqlCtx => A < (S & Async & Abort[ContainerException]))(using
        Frame
    ): A < (S & Async & Abort[ContainerException]) =
        getOrInit(localInfileOnRef, "mysql-local-infile-on", "--local-infile=1")(f)

    /** Acquires the shared `local_infile=0` MySQL container, lazily starting it on first call. */
    def withLocalInfileOff[A, S](f: MysqlCtx => A < (S & Async & Abort[ContainerException]))(using
        Frame
    ): A < (S & Async & Abort[ContainerException]) =
        getOrInit(localInfileOffRef, "mysql-local-infile-off", "--local-infile=0")(f)

    private def getOrInit[A, S](
        ref: AtomicRef[Maybe[MysqlPromise]],
        labelSuffix: String,
        localInfileFlag: String
    )(f: MysqlCtx => A < (S & Async & Abort[ContainerException]))(using
        Frame
    ): A < (S & Async & Abort[ContainerException]) =
        ref.use {
            case Maybe.Present(p) => p.get.flatMap(f)
            case Maybe.Absent =>
                Promise.init[MysqlCtx, Abort[ContainerException]].flatMap { p =>
                    ref.compareAndSet(Maybe.empty, Maybe.Present(p)).flatMap {
                        case false =>
                            // Lost the race; await the winner (or recurse if the slot was reset on failure).
                            ref.use {
                                case Maybe.Present(winner) => winner.get.flatMap(f)
                                case Maybe.Absent          => getOrInit(ref, labelSuffix, localInfileFlag)(f)
                            }
                        case true =>
                            Fiber.initUnscoped(initContainer(labelSuffix, localInfileFlag)).flatMap { fiber =>
                                fiber.getResult.flatMap {
                                    case Result.Success(ctx) =>
                                        p.completeDiscard(Result.succeed(ctx)).andThen(f(ctx))
                                    case Result.Failure(e: ContainerException) =>
                                        // Reset slot first so the next caller retries instead of seeing a poisoned Promise.
                                        ref.set(Maybe.empty)
                                            .andThen(p.completeDiscard(Result.fail(e)))
                                            .andThen(p.get)
                                            .flatMap(f)
                                    case Result.Panic(t) =>
                                        ref.set(Maybe.empty)
                                            .andThen(p.completeDiscard(Result.panic(t)))
                                            .andThen(p.get)
                                            .flatMap(f)
                                }
                            }
                    }
                }
        }

    private def initContainer(labelSuffix: String, localInfileFlag: String)(using
        Frame
    ): MysqlCtx < (Async & Abort[ContainerException]) =
        val predef = ContainerPredef.MySQL.Config.default
            .appendServerArgs("--default-authentication-plugin=mysql_native_password", localInfileFlag)
        val cfg = ContainerPredef.MySQL
            .buildContainerConfig(predef)
            .label("kyo-sql-singleton", labelSuffix)
        Container.initUnscoped(cfg).flatMap { container =>
            val mysql = new ContainerPredef.MySQL(container, predef)
            mysql.container.mappedPort(mysql.config.port).map { port =>
                MysqlCtx(mysql.container.host, port, mysql.username, mysql.password, mysql.database)
            }
        }
    end initContainer

end LocalInfileIntegrationTest
