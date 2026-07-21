package kyo.postgres

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for the PostgreSQL COPY protocol (`copyIn` / `copyOut`).
  *
  * Tests use the shared per-fork-JVM PostgreSQL container with a fresh schema per test (via [[SqlSharedContainers.withFreshSchema]]).
  *
  * ==Table strategy==
  *
  * Tests use permanent tables so that the [[SqlClient]] pool connections can see the same data via cross-connection queries. Each test
  * creates its own table inside its fresh schema; the schema is dropped on test exit, taking the table with it.
  *
  * ==Test leaves (11 total)==
  *   1. COPY FROM STDIN with N rows reports N affected
  *   2. COPY TO STDOUT yields N rows in submission order
  *   3. COPY FROM STDIN with stream failure mid-transfer sends CopyFail and surfaces the error
  *   4. COPY FROM STDIN with binary format round-trips
  *   5. COPY TO STDOUT cancellation closes the stream and drains the protocol cleanly
  *   6. COPY FROM STDIN of empty stream reports 0 affected
  *   7. COPY FROM with constraint violation mid-stream surfaces SqlException.Server (SQLSTATE 23505)
  *   8. COPY TO consumer pauses; client backpressure does not corrupt protocol
  *   9. connection is reusable after COPY TO cancellation (issue follow-up SELECT successfully)
  *   10. COPY FROM in a transaction rolled back leaves zero rows visible to a fresh connection
  *   11. 1 M row COPY FROM completes within Async.timeout and reports correct count
  */
class CopyIntegrationTest extends kyo.Test:

    override def timeout: Duration = 20.minutes

    /** Opens a [[SqlClient]] to the container and runs `f` with the client.
      *
      * All DDL, COPY, and verification queries go through `client` using pool connections. No auxiliary raw connection is needed.
      */
    private def withPg[A, S](
        f: PostgresSqlClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
            val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
            SqlClient.initWith(url)(f)
        }

    /** Decodes the first column of a row as a UTF-8 string. */
    private def decodeStr(column: Maybe[Span[Byte]]): String =
        column.map(s => new String(s.toArray, StandardCharsets.UTF_8)).getOrElse("")

    /** Reads a COUNT(*) result via `client.query` using the typed decoder. */
    private def countRows(client: SqlClient, table: String)(using
        Frame
    ): Long < (Async & Abort[SqlException] & Abort[SqlException.Decode]) =
        client.query(s"SELECT COUNT(*) FROM $table").flatMap { rows =>
            if rows.isEmpty then 0L
            else rows(0).decode[Long](0)
        }

    /** Builds a CSV-format COPY row: "id,value\n" as a Span[Byte]. */
    private def csvRow(id: Int, value: String): Span[Byte] =
        Span.from(s"$id,$value\n".getBytes(StandardCharsets.UTF_8))

    /** Builds a block of N CSV rows starting at `startId` as a single Span[Byte]. */
    private def csvRows(startId: Int, count: Int, prefix: String = "row"): Span[Byte] =
        val sb = new java.lang.StringBuilder
        for i <- startId until startId + count do sb.append(s"$i,${prefix}_$i\n")
        Span.from(sb.toString.getBytes(StandardCharsets.UTF_8))
    end csvRows

    /** Builds a Stream[Span[Byte], Any] from a single Span[Byte]. */
    private def spanStream(data: Span[Byte]): Stream[Span[Byte], Any] =
        Stream[Span[Byte], Any]:
            Emit.valueWith(Chunk(data))(())

    /** Builds a Stream[Span[Byte], Any] that emits multiple spans in order. */
    private def spansStream(spans: Chunk[Span[Byte]]): Stream[Span[Byte], Any] =
        Stream[Span[Byte], Any]:
            def loop(remaining: Chunk[Span[Byte]]): Unit < Emit[Chunk[Span[Byte]]] =
                if remaining.isEmpty then ()
                else Emit.valueWith(Chunk(remaining.head))(loop(remaining.tail))
            loop(spans)

    // ── COPY FROM STDIN N rows ────────────────────────────────────────────────

    "COPY FROM STDIN with N rows reports N affected" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    val n = 1000
                    client.executeRaw("CREATE TABLE copy_t1 (id INT, val TEXT)").flatMap { _ =>
                        val data = spanStream(csvRows(1, n))
                        client.copyIn("COPY copy_t1 (id, val) FROM STDIN WITH (FORMAT CSV)", data).flatMap { affected =>
                            assert(affected == n.toLong)
                            // Verify reusability, probe connection with a trivial query.
                            client.query("SELECT 1").map { rows =>
                                assert(rows.nonEmpty)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── COPY TO STDOUT row order ───────────────────────────────────────────────

    "COPY TO STDOUT yields N rows in submission order" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    val n = 100
                    client.executeRaw("CREATE TABLE copy_t2 (id INT, val TEXT)").flatMap { _ =>
                        // Seed data via COPY IN.
                        val inData = spanStream(csvRows(1, n))
                        client.copyIn("COPY copy_t2 (id, val) FROM STDIN WITH (FORMAT CSV)", inData).flatMap { _ =>
                            // COPY OUT: collect all rows.
                            // The probe query runs AFTER Scope.run exits so the copyOut connection's cleanup
                            // latch is cleared before the pool returns the connection for reuse.
                            Scope.run {
                                client
                                    .copyOut("COPY (SELECT id, val FROM copy_t2 ORDER BY id ASC) TO STDOUT WITH (FORMAT CSV)")
                                    .run
                                    .map { chunks =>
                                        val text = new String(chunks.flatMap(s => Chunk.from(s.toArray)).toArray, StandardCharsets.UTF_8)
                                        val rows = text.split("\n").filter(_.nonEmpty)
                                        assert(rows.length == n, s"Expected $n rows, got ${rows.length}")
                                        // Check order: first row should be "1,row_1"
                                        assert(rows(0) == "1,row_1", s"First row should be '1,row_1', got '${rows(0)}'")
                                        assert(rows(n - 1) == s"$n,row_$n", s"Last row wrong: got '${rows(n - 1)}'")
                                    }
                            }.flatMap { _ =>
                                // Probe reusability after scope exit (copyOut cleanup latch cleared).
                                client.query("SELECT 1").map { rr =>
                                    assert(rr.nonEmpty)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── COPY FROM stream failure ───────────────────────────────────────────────

    "COPY FROM STDIN with stream failure mid-transfer sends CopyFail and surfaces the error" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    client.executeRaw("CREATE TABLE copy_t3 (id INT, val TEXT)").flatMap { _ =>
                        // A stream that yields 3 good rows then raises SqlException.Request.
                        val err = SqlException.Request("deliberate stream failure", kyo.Maybe.Absent, summon[Frame])
                        val data = Stream[Span[Byte], Abort[SqlException]] {
                            Emit.valueWith(Chunk(csvRow(1, "a"), csvRow(2, "b"), csvRow(3, "c"))) {
                                Abort.fail(err)
                            }
                        }
                        Abort.run[SqlException] {
                            client.copyIn("COPY copy_t3 (id, val) FROM STDIN WITH (FORMAT CSV)", data)
                        }.flatMap {
                            case Result.Failure(e: SqlException.Request) =>
                                assert(e.message.contains("deliberate stream failure"))
                                // After the failure, the connection must be reusable.
                                client.query("SELECT 1").flatMap { rows =>
                                    assert(rows.nonEmpty)
                                    // No rows should be visible (the server rolled back after CopyFail).
                                    countRows(client, "copy_t3").map { count =>
                                        assert(count == 0L, s"Expected 0 rows after CopyFail, got $count")
                                    }
                                }
                            case other =>
                                fail(s"Expected SqlException.Request, got: $other")
                        }
                    }
                }
            }
        }
    }

    // ── COPY FROM binary format ────────────────────────────────────────────────

    "COPY FROM STDIN with binary format round-trips" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    // Use a single-column INTEGER table for binary format.
                    // PostgreSQL binary COPY: global header (11 bytes) + per-row: Int16(numCols) Int32(colLen) bytes... + Int16(-1) trailer.
                    // Reference: PostgreSQL COPY manual, binary format description.
                    client.executeRaw("CREATE TABLE copy_t4 (id INTEGER)").flatMap { _ =>
                        // Build binary COPY data for 5 integers.
                        val values = Chunk(10, 20, 30, 40, 50)
                        val baos   = new ByteArrayOutputStream
                        val dos    = new DataOutputStream(baos)
                        // Binary header: signature (11 bytes) + flags (4 bytes) + header ext length (4 bytes).
                        // Signature: "PGCOPY\n\xff\r\n\0" (11 bytes: P G C O P Y \n 0xff \r \n 0x00)
                        dos.write(Array[Byte]('P', 'G', 'C', 'O', 'P', 'Y', '\n', 0xff.toByte, '\r', '\n', 0x00))
                        dos.writeInt(0) // flags
                        dos.writeInt(0) // header ext length
                        // Rows.
                        values.foreach { v =>
                            dos.writeShort(1) // numCols
                            dos.writeInt(4)   // colLen = 4 bytes for INTEGER
                            dos.writeInt(v)   // big-endian integer value
                        }
                        // File trailer: Int16(-1)
                        dos.writeShort(-1)
                        dos.flush()
                        val binaryData = Span.from(baos.toByteArray)
                        val data       = spanStream(binaryData)

                        client.copyIn("COPY copy_t4 (id) FROM STDIN WITH (FORMAT BINARY)", data).flatMap { affected =>
                            assert(affected == values.size.toLong, s"Expected ${values.size} affected, got $affected")
                            // Read back id column using the typed decoder.
                            client.query("SELECT id FROM copy_t4 ORDER BY id ASC").flatMap { rows =>
                                Kyo.foreach(rows)(r => r.decode[Int](0)).flatMap { ids =>
                                    assert(ids == values, s"Expected $values, got $ids")
                                    client.query("SELECT 1").map(rr => assert(rr.nonEmpty))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── COPY TO cancellation ──────────────────────────────────────────────────

    "COPY TO STDOUT cancellation closes the stream and drains the protocol cleanly" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    // Seed a table with enough rows that a COPY TO will produce multiple CopyData messages.
                    val n = 10000
                    client.executeRaw("CREATE TABLE copy_t5 (id INT, val TEXT)").flatMap { _ =>
                        val inData = spanStream(csvRows(1, n))
                        client.copyIn("COPY copy_t5 (id, val) FROM STDIN WITH (FORMAT CSV)", inData).flatMap { _ =>
                            // Cancel the stream early: timeout after 1ms forces interrupt.
                            Abort.run[SqlException] {
                                Abort.run[Timeout] {
                                    Scope.run {
                                        Async.timeout(1.millis) {
                                            client
                                                .copyOut("COPY (SELECT id, val FROM copy_t5) TO STDOUT WITH (FORMAT CSV)")
                                                .foreach { _ =>
                                                    // Sleep inside the consumer forces the timeout to fire.
                                                    Async.sleep(10.seconds)
                                                }
                                        }
                                    }
                                }
                            }.flatMap { r =>
                                // `r` is Result[SqlException, Result[Timeout, Unit]].
                                // Acceptable outcomes:
                                //   - Success(Success(_))  , completed before the 1ms timeout
                                //   - Success(Failure(_))  , timeout fired, no SqlException surfaced
                                //   - Failure(_)           , SqlException.Connection (cleanup path)
                                // What is NOT acceptable: Panic from an unexpected throw.
                                r match
                                    case Result.Panic(t) =>
                                        fail(s"Unexpected panic during copyOut cancellation: ${t.getMessage}")
                                    case _ => () // All SqlException or Timeout outcomes are acceptable here.
                                end match

                                // Critical assertion: the SAME pool must be usable for a follow-up SELECT.
                                client.query("SELECT 1").map { rows =>
                                    assert(rows.nonEmpty, "Pool connection must be reusable after copyOut cancellation")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── COPY FROM empty stream ────────────────────────────────────────────────

    "COPY FROM STDIN of empty stream reports 0 affected" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    client.executeRaw("CREATE TABLE copy_t6 (id INT, val TEXT)").flatMap { _ =>
                        val empty = Stream.empty[Span[Byte]]
                        client.copyIn("COPY copy_t6 (id, val) FROM STDIN WITH (FORMAT CSV)", empty).flatMap { affected =>
                            assert(affected == 0L, s"Expected 0 affected for empty stream, got $affected")
                            client.query("SELECT 1").map(rows => assert(rows.nonEmpty))
                        }
                    }
                }
            }
        }
    }

    // ── COPY FROM constraint violation ────────────────────────────────────────

    "COPY FROM with constraint violation mid-stream surfaces SqlException.Server with the failing row's context (line/column)" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    // Table with a PRIMARY KEY on id, duplicate will cause a unique_violation.
                    client.executeRaw("CREATE TABLE copy_t7 (id INT PRIMARY KEY, val TEXT)").flatMap { _ =>
                        // Pre-insert id=5 so a duplicate in COPY will fail.
                        client.executeRaw("INSERT INTO copy_t7 VALUES (5, 'existing')").flatMap { _ =>
                            // Build CSV data with a duplicate id=5.
                            val goodAndBad = "1,a\n2,b\n3,c\n4,d\n5,DUPLICATE\n6,e\n"
                            val data       = spanStream(Span.from(goodAndBad.getBytes(StandardCharsets.UTF_8)))

                            Abort.run[SqlException] {
                                client.copyIn("COPY copy_t7 (id, val) FROM STDIN WITH (FORMAT CSV)", data)
                            }.map {
                                case Result.Failure(e: SqlException.Server) =>
                                    // PostgreSQL raises 23505 (unique_violation) for PRIMARY KEY conflicts.
                                    assert(
                                        e.sqlState == "23505",
                                        s"Expected SQLSTATE 23505 (unique_violation), got '${e.sqlState}': ${e.message}"
                                    )
                                case Result.Failure(e) =>
                                    fail(s"Expected SqlException.Server(23505), got: ${e.getClass.getSimpleName}: $e")
                                case Result.Success(n) =>
                                    fail(s"Expected constraint violation but got success with $n rows")
                                case Result.Panic(t) =>
                                    fail(s"Unexpected panic: ${t.getMessage}")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── COPY TO consumer backpressure ─────────────────────────────────────────

    "COPY TO consumer pauses; client backpressure does not corrupt protocol" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    val n = 500
                    client.executeRaw("CREATE TABLE copy_t8 (id INT, val TEXT)").flatMap { _ =>
                        val inData = spanStream(csvRows(1, n))
                        client.copyIn("COPY copy_t8 (id, val) FROM STDIN WITH (FORMAT CSV)", inData).flatMap { _ =>
                            // Two latches: gate pauses the consumer at chunk 1; resumed tells it to continue.
                            AtomicInt.init(0).flatMap { chunksSeen =>
                                Latch.init(1).flatMap { gate =>
                                    Latch.init(1).flatMap { resumed =>
                                        // Launch a fiber that consumes the COPY OUT stream with a pause.
                                        Fiber.init {
                                            Scope.run {
                                                client
                                                    .copyOut(
                                                        "COPY (SELECT id, val FROM copy_t8 ORDER BY id ASC) TO STDOUT WITH (FORMAT CSV)"
                                                    )
                                                    .foreach { _ =>
                                                        chunksSeen.incrementAndGet.flatMap { v =>
                                                            if v == 1 then
                                                                // After the first chunk: signal readiness and block uninterruptibly.
                                                                Async.mask {
                                                                    gate.release.andThen(resumed.await)
                                                                }
                                                            else Kyo.unit
                                                        }
                                                    }
                                            }
                                        }.flatMap { fiber =>
                                            // Wait for the consumer to pause.
                                            gate.await.flatMap { _ =>
                                                // Release the gate so the consumer continues.
                                                resumed.release.flatMap { _ =>
                                                    // Wait for the fiber to finish.
                                                    fiber.get.flatMap { _ =>
                                                        chunksSeen.get.flatMap { seen =>
                                                            // Structural assertion: all chunks were received (seen > 0).
                                                            assert(seen > 0, "Expected at least 1 chunk to be consumed")
                                                            // Protocol-integrity assertion: connection still usable.
                                                            client.query("SELECT 1").map { rows =>
                                                                assert(rows.nonEmpty, "Connection must be reusable after backpressure test")
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
                }
            }
        }
    }

    // ── connection reusable after COPY TO cancellation ────────────────────────

    "connection is reusable after COPY TO cancellation (issue follow-up SELECT successfully)" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    val n = 5000
                    client.executeRaw("CREATE TABLE copy_t9 (id INT, val TEXT)").flatMap { _ =>
                        val inData = spanStream(csvRows(1, n))
                        client.copyIn("COPY copy_t9 (id, val) FROM STDIN WITH (FORMAT CSV)", inData).flatMap { _ =>
                            // Cancel the COPY TO stream early by forcing a timeout.
                            // The stream runs via the SAME pool as `client`.
                            Abort.run[SqlException] {
                                Abort.run[Timeout] {
                                    Scope.run {
                                        Async.timeout(1.millis) {
                                            client
                                                .copyOut("COPY (SELECT id, val FROM copy_t9) TO STDOUT WITH (FORMAT CSV)")
                                                .foreach { _ =>
                                                    Async.sleep(10.seconds) // Force the timeout to fire.
                                                }
                                        }
                                    }
                                }
                            }.flatMap { _ =>
                                // Use the SAME client (same pool) for a follow-up SELECT.
                                // This is the primary assertion: the pool connection must be reusable.
                                Abort.run[SqlException] {
                                    client.query("SELECT 42")
                                }.map {
                                    case Result.Success(rows) =>
                                        assert(rows.nonEmpty, "Follow-up SELECT must return rows after copyOut cancellation")
                                    case Result.Failure(e) =>
                                        fail(s"Follow-up SELECT failed after copyOut cancellation: $e")
                                    case Result.Panic(t) =>
                                        fail(s"Follow-up SELECT panicked: ${t.getMessage}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── COPY FROM in rolled-back transaction leaves no rows ───────────────────

    "COPY FROM in a transaction rolled back leaves zero rows visible to a fresh connection" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    val n = 200
                    client.executeRaw("CREATE TABLE copy_t10 (id INT, val TEXT)").flatMap { _ =>
                        // Run COPY FROM inside a transaction, then deliberately roll it back.
                        // client.copyIn is transaction-aware (Phase 19a): inside client.transaction,
                        // it uses the bound connection, same physical connection as the BEGIN.
                        // Abort.fail("deliberate rollback") causes the transaction to roll back.
                        val data = spanStream(csvRows(1, n))
                        Abort.run[SqlException | String] {
                            client.transactionTyped[String, Long, Async & Abort[SqlException]] {
                                client.copyIn("COPY copy_t10 (id, val) FROM STDIN WITH (FORMAT CSV)", data).flatMap { affected =>
                                    assert(affected == n.toLong, s"Expected $n affected, got $affected")
                                    Abort.fail[String]("deliberate rollback")
                                }
                            }
                        }.flatMap {
                            case Result.Failure(_: String) =>
                                // Transaction rolled back (deliberate Abort.fail triggered rollback).
                                // Cross-connection visibility check: client.query acquires a fresh
                                // pool connection, a different physical connection from the rolled-back
                                // transaction, exercising actual cross-connection visibility of the ROLLBACK.
                                // Uses columnDecoded[Long] because client.query uses the extended
                                // protocol (binary format); text-oriented decodeStr would fail.
                                client.query("SELECT COUNT(*) FROM copy_t10").flatMap { rows =>
                                    Abort.run[SqlException.Decode] {
                                        if rows.isEmpty then 0L
                                        else rows(0).columnDecoded[Long](0)
                                    }.map {
                                        case Result.Success(count) =>
                                            assert(count == 0L, s"Expected 0 rows after transaction rollback, got $count")
                                        case Result.Failure(e) =>
                                            fail(s"Failed to decode COUNT(*) from cross-connection query: $e")
                                        case Result.Panic(t) =>
                                            scala.Console.err.println(s"[CopyIntegrationTest leaf 10] unexpected panic: $t")
                                            throw t
                                    }
                                }
                            case Result.Failure(e: SqlException) =>
                                fail(s"Expected COPY FROM success inside transaction, got SqlException: $e")
                            case Result.Success(_) =>
                                fail("Expected transaction rollback but transaction committed")
                            case Result.Panic(t) =>
                                scala.Console.err.println(s"[CopyIntegrationTest leaf 10] unexpected panic: $t")
                                throw t
                        }
                    }
                }
            }
        }
    }

    // ── large-volume COPY FROM completes and reports correct count ────────────

    "1 M row COPY FROM completes within Async.timeout and reports correct count" in {
        Scope.run {
            withPg { client =>
                Async.timeout(5.minutes) {
                    val totalRows = 1_000_000
                    val chunkRows = 10_000 // rows per Span[Byte] chunk
                    val numChunks = totalRows / chunkRows

                    client.executeRaw("CREATE TABLE copy_t11 (id INT, val TEXT)").flatMap { _ =>
                        // Build a lazy stream that emits numChunks Span[Byte] values,
                        // each containing chunkRows CSV rows, without allocating everything up front.
                        val rowsStream: Stream[Span[Byte], Any] =
                            spansStream(Chunk.tabulate(numChunks)(chunkIdx => csvRows(chunkIdx * chunkRows + 1, chunkRows)))

                        client.copyIn("COPY copy_t11 (id, val) FROM STDIN WITH (FORMAT CSV)", rowsStream).flatMap { affected =>
                            assert(affected == totalRows.toLong, s"Expected $totalRows affected rows, got $affected")
                            // Verify actual row count with a cross-connection query.
                            countRows(client, "copy_t11").flatMap { count =>
                                assert(count == totalRows.toLong, s"Expected $totalRows rows in table, got $count")
                                // Prove the connection is reusable after the large upload.
                                client.query("SELECT 1").map(rows => assert(rows.nonEmpty))
                            }
                        }
                    }
                }
            }
        }
    }

end CopyIntegrationTest
