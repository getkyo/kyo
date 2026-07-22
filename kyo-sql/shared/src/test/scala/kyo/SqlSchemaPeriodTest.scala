package kyo

import kyo.SqlDecodeException
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresDecoder
import kyo.internal.postgres.types.PostgresEncoder

/** Unit tests for [[SqlSchema]] `java.time.Period` mapped to PG INTERVAL (Phase 20, G-Codec-JDK-5).
  *
  * All tests are pure (no database container required). They exercise the schema via in-memory byte buffers.
  *
  * Wire layout: `(µs: Int64, days: Int32, months: Int32)`, for Period, µs is always 0, months = period.toTotalMonths, days =
  * period.getDays.
  */
class SqlSchemaPeriodTest extends Test:

    // ── helpers ────────────────────────────────────────────────────────────────

    private def field(name: String): FieldDescription =
        FieldDescription(name, 0, 0, 0, 0, 0, 1) // formatCode=1 → Binary

    private def pgRow(columns: (String, Span[Byte])*): SqlRow =
        val values = Chunk.from(columns.map { case (_, b) => Maybe.Present(b) })
        val fields = Chunk.from(columns.map { case (n, _) => field(n) })
        new SqlRow(values, fields, Format.Binary)
    end pgRow

    /** Encodes a Period for use in a synthetic PG `INTERVAL` row, driving every byte through the public [[SqlSchema.writePostgres]] +
      * [[BoundParam.encoded]] surface, no test-side reach into internal encoders.
      */
    private def pgPeriodBytes(period: java.time.Period)(using kyo.test.AssertScope): Span[Byte] =
        summon[SqlSchema[java.time.Period]].writePostgres(period).head.encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail("SqlSchema[Period].writePostgres produced a NULL param")
    end pgPeriodBytes

    /** Read the raw Int64 µs from a 16-byte INTERVAL binary payload. */
    private def readMicros(bytes: Span[Byte]): Long =
        ((bytes(0) & 0xffL) << 56) |
            ((bytes(1) & 0xffL) << 48) |
            ((bytes(2) & 0xffL) << 40) |
            ((bytes(3) & 0xffL) << 32) |
            ((bytes(4) & 0xffL) << 24) |
            ((bytes(5) & 0xffL) << 16) |
            ((bytes(6) & 0xffL) << 8) |
            (bytes(7) & 0xffL)
    end readMicros

    /** Read the raw Int32 days from a 16-byte INTERVAL binary payload (offset 8). */
    private def readDays(bytes: Span[Byte]): Int =
        ((bytes(8) & 0xff) << 24) |
            ((bytes(9) & 0xff) << 16) |
            ((bytes(10) & 0xff) << 8) |
            (bytes(11) & 0xff)
    end readDays

    /** Read the raw Int32 months from a 16-byte INTERVAL binary payload (offset 12). */
    private def readMonths(bytes: Span[Byte]): Int =
        ((bytes(12) & 0xff) << 24) |
            ((bytes(13) & 0xff) << 16) |
            ((bytes(14) & 0xff) << 8) |
            (bytes(15) & 0xff)
    end readMonths

    // ── summon ─────────────────────────────────────────────────────────────────

    "summon SqlSchema[Period] compiles" in {
        val s: SqlSchema[java.time.Period] = summon[SqlSchema[java.time.Period]]
        assert(s.fieldCount == 1)
        succeed
    }

    // ── encode: wire layout verification ──────────────────────────────────────

    "Period encodes as INTERVAL with months and days, OID 1186 Binary" in {
        val period = java.time.Period.of(1, 6, 15)
        val params = summon[SqlSchema[java.time.Period]].writePostgres(period)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_INTERVAL)
        assert(params(0).format == Format.Binary)
        succeed
    }

    "Period.ofMonths(13) encodes months=13, days=0, micros=0" in {
        val period = java.time.Period.ofMonths(13)
        val bytes  = pgPeriodBytes(period)
        assert(bytes.size == 16)
        assert(readMicros(bytes) == 0L)
        assert(readDays(bytes) == 0)
        assert(readMonths(bytes) == 13)
        succeed
    }

    "Period.ofDays(5) encodes months=0, days=5, micros=0" in {
        val period = java.time.Period.ofDays(5)
        val bytes  = pgPeriodBytes(period)
        assert(bytes.size == 16)
        assert(readMicros(bytes) == 0L)
        assert(readDays(bytes) == 5)
        assert(readMonths(bytes) == 0)
        succeed
    }

    "Period.of(1, 6, 15) encodes months=18 (1*12+6), days=15, micros=0" in {
        val period = java.time.Period.of(1, 6, 15)
        val bytes  = pgPeriodBytes(period)
        assert(bytes.size == 16)
        assert(readMicros(bytes) == 0L)
        assert(readDays(bytes) == 15)
        assert(readMonths(bytes) == 18) // toTotalMonths: 1*12 + 6 = 18
        succeed
    }

    "negative Period encodes with negative months and days" in {
        val period = java.time.Period.of(-1, -3, -10)
        val bytes  = pgPeriodBytes(period)
        assert(bytes.size == 16)
        assert(readMicros(bytes) == 0L)
        assert(readDays(bytes) == -10)
        assert(readMonths(bytes) == -15) // -1*12 + -3 = -15
        succeed
    }

    "Period.ZERO encodes as all-zeros" in {
        val bytes = pgPeriodBytes(java.time.Period.ZERO)
        assert(bytes.size == 16)
        assert(readMicros(bytes) == 0L)
        assert(readDays(bytes) == 0)
        assert(readMonths(bytes) == 0)
        succeed
    }

    // ── decode: from binary INTERVAL ──────────────────────────────────────────

    "Period decodes from INTERVAL with months and days" in {
        val original = java.time.Period.of(2, 3, 7)
        val bytes    = pgPeriodBytes(original)
        val row      = pgRow("p" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.Period]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toTotalMonths == original.toTotalMonths)
                assert(decoded.getDays == original.getDays)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Period decode raises Decode when µs field is non-zero" in {
        // Manually craft a 16-byte INTERVAL with non-zero microseconds (a shape Period itself can't naturally produce because
        // Period has no µs lane). Drive through the public schema read so the surface-under-test is SqlSchema, not the raw decoder.
        val arr = new Array[Byte](16)
        // Write micros = 1 at offset 0 (big-endian Int64): last byte = 1
        arr(7) = 1.toByte
        // months = 0, days = 0, the non-zero µs alone must trigger the error
        val bytes  = Span.from(arr)
        val row    = pgRow("p" -> bytes)
        val result = Abort.run[SqlDecodeException](summon[SqlSchema[java.time.Period]].readPostgres(row)).eval
        result match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"expected typed Decode failure, got $other")
        end match
    }

    // ── round-trip ────────────────────────────────────────────────────────────

    "Period round-trips, Period.of(1, 6, 15)" in {
        val original = java.time.Period.of(1, 6, 15)
        val bytes    = pgPeriodBytes(original)
        val row      = pgRow("p" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.Period]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toTotalMonths == original.toTotalMonths) // 18
                assert(decoded.getDays == original.getDays)             // 15
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Period round-trips, negative period" in {
        val original = java.time.Period.of(-2, -5, -20)
        val bytes    = pgPeriodBytes(original)
        val row      = pgRow("p" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.Period]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toTotalMonths == original.toTotalMonths)
                assert(decoded.getDays == original.getDays)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Period round-trips, only months" in {
        val original = java.time.Period.ofMonths(13)
        val bytes    = pgPeriodBytes(original)
        val row      = pgRow("p" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.Period]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toTotalMonths == 13)
                assert(decoded.getDays == 0)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Period round-trips, only days" in {
        val original = java.time.Period.ofDays(5)
        val bytes    = pgPeriodBytes(original)
        val row      = pgRow("p" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.Period]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toTotalMonths == 0)
                assert(decoded.getDays == 5)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Period writeMysql produces one string param" in {
        val period = java.time.Period.of(1, 2, 3)
        val params = summon[SqlSchema[java.time.Period]].writeMysql(period)
        assert(params.size == 1)
        // MySQL encodes Period as ISO-8601 text (Period.toString returns e.g. "P1Y2M3D").
        succeed
    }

    // ── Phase 20 audit W-1: MySQL Period decode leaf ────────────────────────
    //
    // Symmetric MySQL round-trip through `SqlSchema[Period].readMysql`, which calls
    // `Period.parse(mr.string())` on the lenenc-stripped UTF-8 bytes. Previously only the write
    // half was covered; the read half went untested.

    private def mysqlPeriodRow(period: java.time.Period): SqlRow =
        val raw = period.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        new SqlRow(
            Chunk(Maybe.Present(Span.from(raw))),
            Chunk(field("p")),
            Format.Binary
        )
    end mysqlPeriodRow

    "Period round-trips through MySQL string codec (writeMysql → readMysql)" in {
        val original = java.time.Period.of(1, 2, 3)
        val row      = mysqlPeriodRow(original)
        val result   = Abort.run(summon[SqlSchema[java.time.Period]].readMysql(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.getYears == 1)
                assert(decoded.getMonths == 2)
                assert(decoded.getDays == 3)
            case other => fail(s"expected Success, got $other")
        end match
    }

    "Period MySQL decode preserves zero / negative periods" in {
        val cases = Seq(
            java.time.Period.ZERO,
            java.time.Period.of(0, 6, 0),
            java.time.Period.of(-1, 0, 0),
            java.time.Period.ofDays(-15)
        )
        cases.foreach { original =>
            val row    = mysqlPeriodRow(original)
            val result = Abort.run(summon[SqlSchema[java.time.Period]].readMysql(row)).eval
            result match
                case Result.Success(decoded) =>
                    assert(decoded.equals(original), s"mismatch for $original: got $decoded")
                case other => fail(s"expected Success for $original, got $other")
            end match
        }
        succeed
    }

    "Period MySQL decode raises a typed failure on malformed text" in {
        val raw    = "not-a-period".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val row    = new SqlRow(Chunk(Maybe.Present(Span.from(raw))), Chunk(field("p")), Format.Binary)
        val result = Abort.run[SqlDecodeException](summon[SqlSchema[java.time.Period]].readMysql(row)).eval
        result match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"expected Failure(SqlDecodeException), got $other")
        end match
    }

end SqlSchemaPeriodTest
