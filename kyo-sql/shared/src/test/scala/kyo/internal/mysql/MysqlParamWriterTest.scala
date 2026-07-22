package kyo.internal.mysql

import kyo.Instant
import kyo.Maybe
import kyo.Span
import kyo.SqlDecodeException
import kyo.SqlException
import kyo.SqlRequestDurationOverflowException
import kyo.SqlUnsupportedCustomTypeException
import kyo.SqlUnsupportedException
import kyo.Test
import kyo.internal.mysql.types.MysqlEncoder
import kyo.internal.postgres.types.Format

/** Verifies that [[MysqlParamWriter]] produces [[BoundMysqlParam]] instances whose wire bytes are byte-for-byte identical to the output of
  * the corresponding [[MysqlEncoder]] singletons.
  *
  * Each parity test computes the expected bytes at runtime by calling the encoder through a [[MysqlBufferWriter]], then asserts that the
  * writer produces identical bytes. This ensures the parity invariant is checked without hardcoded byte-array snapshots.
  */
class MysqlParamWriterTest extends Test:

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Encodes `value` using `enc` and returns the raw wire bytes. */
    private def encode[A](value: A, enc: MysqlEncoder[A]): Array[Byte] =
        val buf = new MysqlBufferWriter
        enc.write(value, buf)
        buf.toSpan.toArray
    end encode

    /** Asserts that `actual` is Present and its bytes match `expectedBytes`. */
    private def assertBytesMatch(actual: Maybe[Span[Byte]], expectedBytes: Array[Byte], hint: String = "")(using
        kyo.test.AssertScope
    ): Unit =
        actual match
            case Maybe.Absent =>
                fail(s"Expected Present bytes but got Absent${if hint.nonEmpty then s" ($hint)" else ""}")
            case Maybe.Present(span) =>
                val actualArr = span.toArray
                assert(
                    actualArr.sameElements(expectedBytes),
                    s"byte mismatch${if hint.nonEmpty then s" ($hint)" else ""}: got ${actualArr.toSeq}, expected ${expectedBytes.toSeq}"
                )

    /** Writes via `write`, asserts exactly one param was accumulated, and returns it. */
    private def singleParam(write: MysqlParamWriter => Unit)(using kyo.test.AssertScope): BoundMysqlParam[?] =
        val w = new MysqlParamWriter(Map.empty)
        write(w)
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        ps(0)
    end singleParam

    "encodes Boolean true as tinyint(1)" in {
        val param = singleParam(_.boolean(true))
        assert(param.mysqlType == MysqlEncoder.TYPE_TINY, s"mysqlType mismatch: got 0x${param.mysqlType.toHexString}")
        param.encoded match
            case Maybe.Absent => fail("expected encoded bytes for true")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 1, s"expected 1 byte, got ${bytes.size}")
                assert(bytes(0) == 1.toByte, "true should encode to byte 1")
        end match
    }

    "encodes Boolean false as tinyint(1)" in {
        val param = singleParam(_.boolean(false))
        assert(param.mysqlType == MysqlEncoder.TYPE_TINY, s"mysqlType mismatch: got 0x${param.mysqlType.toHexString}")
        param.encoded match
            case Maybe.Absent => fail("expected encoded bytes for false")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 1, s"expected 1 byte, got ${bytes.size}")
                assert(bytes(0) == 0.toByte, "false should encode to byte 0")
        end match
    }

    "encodes Short via inline encoder" in {
        val values = Seq(0.toShort, (-1).toShort, Short.MaxValue, Short.MinValue)
        for v <- values do
            val param = singleParam(_.short(v))
            assert(param.mysqlType == MysqlEncoder.TYPE_SHORT, s"mysqlType mismatch for $v: got 0x${param.mysqlType.toHexString}")
            param.encoded match
                case Maybe.Absent => fail(s"expected encoded bytes for short $v")
                case Maybe.Present(bytes) =>
                    assert(bytes.size == 2, s"expected 2 bytes for short $v, got ${bytes.size}")
            end match
        end for
        succeed
    }

    "encodes Int as int4" in {
        val values = Seq(0, -1, 1, Int.MaxValue, Int.MinValue, 42)
        for v <- values do
            val param = singleParam(_.int(v))
            assert(param.mysqlType == MysqlEncoder.TYPE_LONG, s"mysqlType mismatch for $v: got 0x${param.mysqlType.toHexString}")
            assertBytesMatch(param.encoded, encode(v, MysqlEncoder.intEncoder), s"int $v")
        end for
        succeed
    }

    "encodes Long as int8" in {
        val values = Seq(0L, -1L, Long.MaxValue, Long.MinValue, 42L, 1_000_000_000_000L)
        for v <- values do
            val param = singleParam(_.long(v))
            assert(param.mysqlType == MysqlEncoder.TYPE_LONGLONG, s"mysqlType mismatch for $v: got 0x${param.mysqlType.toHexString}")
            assertBytesMatch(param.encoded, encode(v, MysqlEncoder.longEncoder), s"long $v")
        end for
        succeed
    }

    "encodes Float as float4" in {
        val values = Seq(0.0f, -0.0f, Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity, 1.5f, -1.5f)
        for v <- values do
            val param = singleParam(_.float(v))
            assert(param.mysqlType == MysqlEncoder.TYPE_FLOAT, s"mysqlType mismatch for $v: got 0x${param.mysqlType.toHexString}")
            // Compare Span[Byte] only, NaN != NaN in IEEE 754, but bit patterns are deterministic.
            assertBytesMatch(param.encoded, encode(v, MysqlEncoder.floatEncoder), s"float $v")
        end for
        succeed
    }

    "encodes Double as float8" in {
        val values = Seq(0.0d, -0.0d, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, Math.PI)
        for v <- values do
            val param = singleParam(_.double(v))
            assert(param.mysqlType == MysqlEncoder.TYPE_DOUBLE, s"mysqlType mismatch for $v: got 0x${param.mysqlType.toHexString}")
            assertBytesMatch(param.encoded, encode(v, MysqlEncoder.doubleEncoder), s"double $v")
        end for
        succeed
    }

    "encodes BigDecimal as decimal text" in {
        val values = Seq(
            BigDecimal("0"),
            BigDecimal("1"),
            BigDecimal("-1"),
            BigDecimal("123.45"),
            BigDecimal("-1234567890.1234"),
            BigDecimal("0.00001"),
            BigDecimal("1.23456789012345678901234567890")
        )
        for v <- values do
            val param = singleParam(_.bigDecimal(v))
            assert(param.mysqlType == MysqlEncoder.TYPE_NEWDECIMAL, s"mysqlType mismatch for $v: got 0x${param.mysqlType.toHexString}")
            assertBytesMatch(param.encoded, encode(v, MysqlEncoder.bigDecimalEncoder), s"bigDecimal $v")
        end for
        succeed
    }

    "encodes String as varString" in {
        val values = Seq("", "hello", "Hello, 世界", " ", "a" * 1000)
        for v <- values do
            val param = singleParam(_.string(v))
            assert(param.mysqlType == MysqlEncoder.TYPE_VAR_STRING, s"mysqlType mismatch for '$v': got 0x${param.mysqlType.toHexString}")
            assertBytesMatch(param.encoded, encode(v, MysqlEncoder.stringEncoder), s"string '$v'")
        end for
        succeed
    }

    "encodes Span[Byte] as blob" in {
        val values = Seq(
            Span.empty[Byte],
            Span.from(Array[Byte](0x00.toByte)),
            Span.from(Array[Byte](0xff.toByte)),
            Span.from(Array[Byte](0x01.toByte, 0x02.toByte, 0x03.toByte))
        )
        for v <- values do
            val param = singleParam(_.bytes(v))
            assert(param.mysqlType == MysqlEncoder.TYPE_BLOB, s"mysqlType mismatch: got 0x${param.mysqlType.toHexString}")
            assertBytesMatch(param.encoded, encode(v, MysqlEncoder.bytesEncoder), s"bytes ${v.toArray.toSeq}")
        end for
        succeed
    }
    // instantEncoder uses TYPE_TIMESTAMP (0x07), not TYPE_DATETIME (0x0c).

    "encodes kyo.Instant as timestamp" in {
        val values = Seq(
            java.time.Instant.EPOCH,
            java.time.Instant.ofEpochSecond(1_000_000L),
            java.time.Instant.ofEpochSecond(-1L),
            java.time.Instant.parse("2024-06-15T12:30:00Z")
        )
        for v <- values do
            val param = singleParam(_.instant(v))
            assert(
                param.mysqlType == MysqlEncoder.TYPE_TIMESTAMP,
                s"mysqlType mismatch for $v: got 0x${param.mysqlType.toHexString}, expected 0x07"
            )
            // Parity: MysqlParamWriter converts via Instant.fromJava then uses instantEncoder.
            assertBytesMatch(param.encoded, encode(Instant.fromJava(v), MysqlEncoder.instantEncoder), s"instant $v")
        end for
        succeed
    }

    "encodes Byte via inline encoder" in {
        val values = Seq(0.toByte, 1.toByte, (-1).toByte, Byte.MaxValue, Byte.MinValue)
        for v <- values do
            val param = singleParam(_.byte(v))
            assert(param.mysqlType == MysqlEncoder.TYPE_TINY, s"mysqlType mismatch for $v: got 0x${param.mysqlType.toHexString}")
            param.encoded match
                case Maybe.Absent => fail(s"expected encoded bytes for byte $v")
                case Maybe.Present(bytes) =>
                    assert(bytes.size == 1, s"expected 1 byte for byte $v, got ${bytes.size}")
                    assert(bytes(0) == v, s"byte value mismatch: got ${bytes(0)}, expected $v")
            end match
        end for
        succeed
    }

    "encodes Char via stringEncoder" in {
        val values = Seq('A', 'z', '0', '中') // ASCII and multi-byte UTF-8
        for v <- values do
            val param = singleParam(_.char(v))
            assert(param.mysqlType == MysqlEncoder.TYPE_VAR_STRING, s"mysqlType mismatch for '$v': got 0x${param.mysqlType.toHexString}")
            // char delegates via v.toString to stringEncoder
            assertBytesMatch(param.encoded, encode(v.toString, MysqlEncoder.stringEncoder), s"char '$v'")
        end for
        succeed
    }

    "encodes BigInt via bigDecimalEncoder" in {
        val values = Seq(BigInt(0), BigInt(1), BigInt(-1), BigInt("123456789012345678901234567890"))
        for v <- values do
            val param = singleParam(_.bigInt(v))
            assert(param.mysqlType == MysqlEncoder.TYPE_NEWDECIMAL, s"mysqlType mismatch for $v: got 0x${param.mysqlType.toHexString}")
            // bigInt delegates to bigDecimalEncoder via BigDecimal(v)
            assertBytesMatch(param.encoded, encode(BigDecimal(v), MysqlEncoder.bigDecimalEncoder), s"bigInt $v")
        end for
        succeed
    }

    "encodes nil (Maybe.Absent) as null param" in {
        val w = new MysqlParamWriter(Map.empty)
        w.nil()
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        val param = ps(0)
        assert(!param.value.isDefined, "nil() should produce Maybe.Absent value")
        assert(!param.encoded.isDefined, "nil() encoded should be Maybe.Absent (SQL NULL)")
        // mysqlType is from the sentinel encoder; just verify the field is accessible.
        val _ = param.mysqlType
        succeed
    }

    "duration encodes as TIME binary, length=0 for ZERO" in {
        val param = singleParam(_.duration(java.time.Duration.ZERO))
        assert(param.mysqlType == MysqlEncoder.TYPE_TIME, s"mysqlType mismatch: got 0x${param.mysqlType.toHexString}")
        assertBytesMatch(param.encoded, encode(java.time.Duration.ZERO, MysqlEncoder.durationEncoder), "ZERO")
        // Verify the encoded byte sequence: just a single 0x00 length byte.
        param.encoded match
            case Maybe.Absent => fail("expected Present bytes for ZERO duration")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 1, s"expected 1 byte for ZERO, got ${bytes.size}")
                assert(bytes(0) == 0x00.toByte, s"expected length=0 byte, got 0x${(bytes(0) & 0xff).toHexString}")
        end match
    }

    "duration encodes as TIME binary, length=8 for whole hours/minutes/seconds" in {
        val value = java.time.Duration.ofHours(1).plusMinutes(30).plusSeconds(15)
        val param = singleParam(_.duration(value))
        assert(param.mysqlType == MysqlEncoder.TYPE_TIME, s"mysqlType mismatch: got 0x${param.mysqlType.toHexString}")
        assertBytesMatch(param.encoded, encode(value, MysqlEncoder.durationEncoder), "1h30m15s")
        param.encoded match
            case Maybe.Absent => fail("expected Present bytes")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 9, s"expected 9 bytes (1 length + 8 body), got ${bytes.size}")
                assert(bytes(0) == 0x08.toByte, s"expected length=8 byte, got 0x${(bytes(0) & 0xff).toHexString}")
                assert(bytes(1) == 0x00.toByte, "is_negative should be 0 for positive duration")
        end match
    }

    "duration encodes as TIME binary, length=12 for fractional" in {
        // Duration with 500ms = 500_000 microseconds
        val value = java.time.Duration.ofSeconds(3, 500_000_000L)
        val param = singleParam(_.duration(value))
        assert(param.mysqlType == MysqlEncoder.TYPE_TIME, s"mysqlType mismatch: got 0x${param.mysqlType.toHexString}")
        assertBytesMatch(param.encoded, encode(value, MysqlEncoder.durationEncoder), "3s500ms")
        param.encoded match
            case Maybe.Absent => fail("expected Present bytes")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 13, s"expected 13 bytes (1 length + 12 body), got ${bytes.size}")
                assert(bytes(0) == 0x0c.toByte, s"expected length=12 byte, got 0x${(bytes(0) & 0xff).toHexString}")
                assert(bytes(1) == 0x00.toByte, "is_negative should be 0")
        end match
    }

    "duration encodes negative duration with isNegative byte = 1" in {
        val value = java.time.Duration.ofHours(-8)
        val param = singleParam(_.duration(value))
        assert(param.mysqlType == MysqlEncoder.TYPE_TIME, s"mysqlType mismatch: got 0x${param.mysqlType.toHexString}")
        assertBytesMatch(param.encoded, encode(value, MysqlEncoder.durationEncoder), "-8h")
        param.encoded match
            case Maybe.Absent => fail("expected Present bytes")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 9, s"expected 9 bytes (1 length + 8 body), got ${bytes.size}")
                assert(bytes(0) == 0x08.toByte, "length should be 8")
                assert(bytes(1) == 0x01.toByte, "is_negative should be 1 for negative duration")
        end match
    }

    "duration encoding raises Decode on day-count overflow" in {
        // Duration with more than Int.MaxValue days, MysqlParamWriter.duration() guards
        // day-count overflow eagerly and raises the typed SqlRequestDurationOverflowException leaf.
        val hugeSeconds = (Int.MaxValue.toLong + 1L) * 86400L
        val value       = java.time.Duration.ofSeconds(hugeSeconds)
        val ex = intercept[SqlRequestDurationOverflowException] {
            singleParam(_.duration(value))
        }
        assert(ex.totalDays > Int.MaxValue.toLong, s"expected totalDays > Int.MaxValue, got: ${ex.totalDays}")
    }

    "custom() with unregistered type name throws SqlUnsupportedException with message" in {
        val w  = new MysqlParamWriter(Map.empty)
        val ex = intercept[SqlUnsupportedCustomTypeException] { w.custom("geometry", Span.empty, Format.Binary) }
        assert(ex.typeName == "geometry", s"expected typeName 'geometry', got: ${ex.typeName}")
    }

    // ── Bonus: multiple params accumulate in order ────────────────────────────

    "accumulates multiple params in write order" in {
        val w = new MysqlParamWriter(Map.empty)
        w.int(1)
        w.string("hello")
        w.nil()
        val ps = w.params
        assert(ps.size == 3, s"expected 3 params, got ${ps.size}")
        assert(ps(0).mysqlType == MysqlEncoder.TYPE_LONG)
        assert(ps(1).mysqlType == MysqlEncoder.TYPE_VAR_STRING)
        assert(!ps(2).value.isDefined)
        succeed
    }

end MysqlParamWriterTest
