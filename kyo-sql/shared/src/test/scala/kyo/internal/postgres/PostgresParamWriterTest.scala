package kyo.internal.postgres

import kyo.Instant
import kyo.Maybe
import kyo.Span
import kyo.SqlDecodeException
import kyo.SqlException
import kyo.SqlRequestDurationOverflowException
import kyo.SqlUnsupportedException
import kyo.Test
import kyo.internal.client.TypeRegistry
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresEncoder

/** Verifies that [[PostgresParamWriter]] produces [[BoundParam]] instances whose wire bytes are byte-for-byte identical to the output of
  * the corresponding [[PostgresEncoder]] singletons.
  *
  * Each parity test computes the expected bytes at runtime by calling the encoder through a [[PostgresBufferWriter]], then asserts that the
  * writer produces identical bytes. This ensures the parity invariant is checked without hardcoded byte-array snapshots.
  *
  * Tests 12 and 13 (LocalDate, LocalDateTime) verify the wire format of the underlying encoders directly, since `Codec.Writer` has no
  * `localDate()`/`localDateTime()` abstract methods, those types are handled by the schema layer above the writer.
  */
class PostgresParamWriterTest extends Test:

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Encodes `value` using `enc` and returns the raw wire bytes. */
    private def encode[A](value: A, enc: PostgresEncoder[A]): Array[Byte] =
        val buf = new PostgresBufferWriter
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
    private def singleParam(write: PostgresParamWriter => Unit)(using kyo.test.AssertScope): BoundParam[?] =
        val w = new PostgresParamWriter(TypeRegistry.empty)
        write(w)
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        ps(0)
    end singleParam

    "encodes Int as int4Binary" in {
        val values = Seq(0, -1, 1, Int.MaxValue, Int.MinValue, 42)
        for v <- values do
            val param = singleParam(_.int(v))
            assert(param.oid == PostgresEncoder.OID_INT4, s"OID mismatch for $v")
            assert(param.format == Format.Binary, s"format mismatch for $v")
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.int4Binary), s"int $v")
        end for
        succeed
    }

    "encodes Long as int8Binary" in {
        val values = Seq(0L, -1L, Long.MaxValue, Long.MinValue, 42L, 1_000_000_000_000L)
        for v <- values do
            val param = singleParam(_.long(v))
            assert(param.oid == PostgresEncoder.OID_INT8, s"OID mismatch for $v")
            assert(param.format == Format.Binary, s"format mismatch for $v")
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.int8Binary), s"long $v")
        end for
        succeed
    }

    "encodes Short as int2Binary" in {
        val values = Seq(0.toShort, (-1).toShort, Short.MaxValue, Short.MinValue)
        for v <- values do
            val param = singleParam(_.short(v))
            assert(param.oid == PostgresEncoder.OID_INT2, s"OID mismatch for $v")
            assert(param.format == Format.Binary, s"format mismatch for $v")
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.int2Binary), s"short $v")
        end for
        succeed
    }

    "encodes String as text" in {
        val values = Seq("", "hello", "Hello, 世界", " ", "a" * 10000)
        for v <- values do
            val param = singleParam(_.string(v))
            assert(param.oid == PostgresEncoder.OID_TEXT, s"OID mismatch for '$v'")
            assert(param.format == Format.Text, s"format mismatch for '$v' (must be String, not Binary)")
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.textText), s"string '$v'")
        end for
        succeed
    }

    "encodes Boolean true as boolBinary" in {
        val param = singleParam(_.boolean(true))
        assert(param.oid == PostgresEncoder.OID_BOOL)
        assert(param.format == Format.Binary)
        param.encoded match
            case Maybe.Absent => fail("expected encoded bytes for true")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 1, s"expected 1 byte, got ${bytes.size}")
                assert(bytes(0) == 1.toByte, "true should encode to byte 1")
        end match
    }

    "encodes Boolean false as boolBinary" in {
        val param = singleParam(_.boolean(false))
        assert(param.oid == PostgresEncoder.OID_BOOL)
        assert(param.format == Format.Binary)
        param.encoded match
            case Maybe.Absent => fail("expected encoded bytes for false")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 1, s"expected 1 byte, got ${bytes.size}")
                assert(bytes(0) == 0.toByte, "false should encode to byte 0")
        end match
    }

    "encodes Float as float4Binary" in {
        val values = Seq(0.0f, -0.0f, Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity, 1.5f, -1.5f)
        for v <- values do
            val param = singleParam(_.float(v))
            assert(param.oid == PostgresEncoder.OID_FLOAT4, s"OID mismatch for $v")
            assert(param.format == Format.Binary, s"format mismatch for $v")
            // Compare Span[Byte] only, NaN != NaN in IEEE 754, but bit patterns are deterministic.
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.float4Binary), s"float $v")
        end for
        succeed
    }

    "encodes Double as float8Binary" in {
        val values =
            Seq(0.0d, -0.0d, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, Math.PI)
        for v <- values do
            val param = singleParam(_.double(v))
            assert(param.oid == PostgresEncoder.OID_FLOAT8, s"OID mismatch for $v")
            assert(param.format == Format.Binary, s"format mismatch for $v")
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.float8Binary), s"double $v")
        end for
        succeed
    }

    "encodes BigDecimal via numeric encoder" in {
        val values = Seq(
            BigDecimal("0"),
            BigDecimal("1"),
            BigDecimal("-1"),
            BigDecimal("0.001"),
            BigDecimal("-1234567890.1234"),
            BigDecimal("9999"),
            BigDecimal("10000"),
            BigDecimal("0.00001"),
            BigDecimal("1234567890.12345"),
            BigDecimal("1.23456789012345678901234567890")
        )
        for v <- values do
            val param = singleParam(_.bigDecimal(v))
            assert(param.oid == PostgresEncoder.OID_NUMERIC, s"OID mismatch for $v")
            // STEERING directive: bigDecimal must use numericText (Format.Text), not numericBinary.
            assert(param.format == Format.Text, s"format must be String (not Binary) for $v")
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.numericText), s"bigDecimal $v")
        end for
        succeed
    }

    "encodes Span[Byte] as byteaBinary" in {
        val values = Seq(
            Span.empty[Byte],
            Span.from(Array[Byte](0x00.toByte)),
            Span.from(Array[Byte](0xff.toByte)),
            Span.from(Array[Byte](0x00.toByte, 0xff.toByte, 0x7f.toByte, 0x80.toByte))
        )
        for v <- values do
            val param = singleParam(_.bytes(v))
            assert(param.oid == PostgresEncoder.OID_BYTEA, s"OID mismatch")
            assert(param.format == Format.Binary, s"format mismatch")
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.byteaBinary), s"bytea ${v.toArray.toSeq}")
        end for
        succeed
    }

    "encodes kyo.Instant as timestamptzBinary" in {
        val values = Seq(
            java.time.Instant.EPOCH,
            java.time.Instant.ofEpochSecond(1_000_000L),
            java.time.Instant.ofEpochSecond(-1L),
            java.time.Instant.parse("2000-01-01T00:00:00Z") // PG epoch → pgMicros = 0
        )
        for v <- values do
            val param = singleParam(_.instant(v))
            assert(param.oid == PostgresEncoder.OID_TIMESTAMPTZ, s"OID mismatch for $v")
            assert(param.format == Format.Binary, s"format mismatch for $v")
            // Verify 8-byte wire output
            param.encoded match
                case Maybe.Absent => fail(s"encoded is absent for $v")
                case Maybe.Present(bytes) =>
                    assert(bytes.size == 8, s"expected 8 bytes for $v, got ${bytes.size}")
            end match
            // Parity: PostgresParamWriter converts via Instant.fromJava then uses timestamptzBinary.
            assertBytesMatch(param.encoded, encode(Instant.fromJava(v), PostgresEncoder.timestamptzBinary), s"instant $v")
        end for
        succeed
    }
    // Codec.Writer has no localDate() method; this test verifies the dateBinary encoder's
    // wire format (OID=1082, 4-byte int32 days since PG epoch) directly via BoundParam.

    "encodes LocalDate as dateBinary" in {
        val values = Seq(
            java.time.LocalDate.of(2000, 1, 1), // PG epoch → days = 0
            java.time.LocalDate.of(1970, 1, 1), // Unix epoch → negative days
            java.time.LocalDate.of(2024, 2, 29) // leap day
        )
        for v <- values do
            val param = BoundParam(v, PostgresEncoder.dateBinary)
            assert(param.oid == PostgresEncoder.OID_DATE, s"OID mismatch for $v")
            assert(param.format == Format.Binary, s"format mismatch for $v")
            param.encoded match
                case Maybe.Absent => fail(s"encoded is absent for $v")
                case Maybe.Present(bytes) =>
                    assert(bytes.size == 4, s"expected 4 bytes for $v, got ${bytes.size}")
            end match
        end for
        succeed
    }
    // Codec.Writer has no localDateTime() method; this test verifies the timestampBinary encoder's
    // wire format (OID=1114, 8-byte int64 microseconds since PG epoch) directly via BoundParam.

    "encodes LocalDateTime as timestampBinary" in {
        val values = Seq(
            java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 0), // PG epoch → pgMicros = 0
            java.time.LocalDateTime.of(2024, 6, 15, 12, 30, 0)
        )
        for v <- values do
            val param = BoundParam(v, PostgresEncoder.timestampBinary)
            assert(param.oid == PostgresEncoder.OID_TIMESTAMP, s"OID mismatch for $v")
            assert(param.format == Format.Binary, s"format mismatch for $v")
            param.encoded match
                case Maybe.Absent => fail(s"encoded is absent for $v")
                case Maybe.Present(bytes) =>
                    assert(bytes.size == 8, s"expected 8 bytes for $v, got ${bytes.size}")
            end match
        end for
        succeed
    }

    "encodes nil (Maybe.Absent) as null param" in {
        val w = new PostgresParamWriter(TypeRegistry.empty)
        w.nil()
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        val param = ps(0)
        assert(!param.value.isDefined, "nil() should produce Maybe.Absent value")
        assert(!param.encoded.isDefined, "nil() encoded should be Maybe.Absent (SQL NULL)")
        // OID is a sentinel (PG ignores OID for NULL Bind params); just verify the field is accessible.
        val _ = param.oid
        succeed
    }

    "custom with builtin type name 'date' resolves to OID=1082 without registry" in {
        val w     = new PostgresParamWriter(TypeRegistry.empty)    // empty registry
        val bytes = Span.from(Array[Byte](0x00, 0x00, 0x00, 0x0a)) // 10 days
        w.custom("date", bytes, Format.Binary)
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        assert(ps(0).oid == PostgresEncoder.OID_DATE, s"expected OID=${PostgresEncoder.OID_DATE} got ${ps(0).oid}")
        assert(ps(0).format == Format.Binary)
    }

    "custom with builtin type name 'timestamp' resolves to OID=1114 without registry" in {
        val w     = new PostgresParamWriter(TypeRegistry.empty) // empty registry
        val bytes = Span.from(Array[Byte](0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a))
        w.custom("timestamp", bytes, Format.Binary)
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        assert(ps(0).oid == PostgresEncoder.OID_TIMESTAMP, s"expected OID=${PostgresEncoder.OID_TIMESTAMP} got ${ps(0).oid}")
        assert(ps(0).format == Format.Binary)
    }

    "duration encodes as INTERVAL binary, OID=1186, 16 bytes" in {
        // Duration.ofHours(1): µs = 3_600_000_000 = 0x00000000D693A400, days=0, months=0
        // Expected 16-byte hex: 00000000D693A4000000000000000000
        val expected = Array[Byte](
            0x00,
            0x00,
            0x00,
            0x00,
            0xd6.toByte,
            0x93.toByte,
            0xa4.toByte,
            0x00, // µs Int64 BE
            0x00,
            0x00,
            0x00,
            0x00, // days Int32 BE
            0x00,
            0x00,
            0x00,
            0x00 // months Int32 BE
        )
        val param = singleParam(_.duration(java.time.Duration.ofHours(1)))
        assert(param.oid == PostgresEncoder.OID_INTERVAL, s"expected OID=1186 got ${param.oid}")
        assert(param.format == Format.Binary, s"expected Binary format got ${param.format}")
        param.encoded match
            case Maybe.Absent => fail("expected encoded bytes for Duration.ofHours(1)")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 16, s"expected 16 bytes, got ${bytes.size}")
                assert(bytes.toArray.sameElements(expected), s"byte mismatch: got ${bytes.toArray.toSeq}, expected ${expected.toSeq}")
        end match
    }

    "duration ZERO encodes as 16 zero bytes" in {
        val expected = Array.fill[Byte](16)(0)
        val param    = singleParam(_.duration(java.time.Duration.ZERO))
        assert(param.oid == PostgresEncoder.OID_INTERVAL)
        assert(param.format == Format.Binary)
        param.encoded match
            case Maybe.Absent => fail("expected encoded bytes for Duration.ZERO")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 16, s"expected 16 bytes, got ${bytes.size}")
                assert(bytes.toArray.sameElements(expected), s"byte mismatch: ${bytes.toArray.toSeq}")
        end match
    }

    "duration ofSeconds(-30) encodes as INTERVAL binary with negative µs" in {
        // µs = -30_000_000 = 0xFFFFFFFFFE363C80; days=0, months=0
        // 16-byte hex: FFFFFFFFFE363C800000000000000000
        val expected = Array[Byte](
            0xff.toByte,
            0xff.toByte,
            0xff.toByte,
            0xff.toByte,
            0xfe.toByte,
            0x36.toByte,
            0x3c.toByte,
            0x80.toByte, // µs Int64 BE
            0x00,
            0x00,
            0x00,
            0x00, // days
            0x00,
            0x00,
            0x00,
            0x00 // months
        )
        val param = singleParam(_.duration(java.time.Duration.ofSeconds(-30)))
        assert(param.oid == PostgresEncoder.OID_INTERVAL)
        assert(param.format == Format.Binary)
        param.encoded match
            case Maybe.Absent => fail("expected encoded bytes for Duration.ofSeconds(-30)")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 16, s"expected 16 bytes, got ${bytes.size}")
                assert(bytes.toArray.sameElements(expected), s"byte mismatch: ${bytes.toArray.toSeq}")
        end match
    }

    "duration encoding raises SqlDecodeException on overflow (seconds exceed µs Int64 range)" in {
        val w = new PostgresParamWriter(TypeRegistry.empty)
        // 9_223_372_036_855L seconds × 1_000_000 overflows Int64
        val overflowDuration = java.time.Duration.ofSeconds(9_223_372_036_855L)
        val ex = intercept[SqlRequestDurationOverflowException] {
            w.duration(overflowDuration)
        }
        val expectedDays = overflowDuration.getSeconds / 86_400L
        assert(ex.totalDays == expectedDays, s"expected totalDays $expectedDays, got: ${ex.totalDays}")
    }

    "custom with unknown type name throws SqlUnsupportedException" in {
        val w = new PostgresParamWriter(TypeRegistry.empty) // registry is empty
        val ex = intercept[SqlUnsupportedException] {
            w.custom("geometry", Span.empty, Format.Binary)
        }
        assert(ex.message.contains("geometry"), s"error message should mention type name: ${ex.message}")
    }

    "custom with populated TypeRegistry uses the correct OID in the resulting BoundParam" in {
        val geomOid = 12345
        val reg     = TypeRegistry(Map("geometry" -> geomOid))
        val w       = new PostgresParamWriter(reg)
        val bytes   = Span.from(Array[Byte](0x01, 0x02, 0x03))
        w.custom("geometry", bytes, Format.Binary)
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        assert(ps(0).oid == geomOid, s"expected OID=$geomOid but got ${ps(0).oid}")
    }

    "custom with populated TypeRegistry throws SqlUnsupportedException for unregistered type names" in {
        val reg = TypeRegistry(Map("geometry" -> 12345))
        val w   = new PostgresParamWriter(reg)
        val ex = intercept[SqlUnsupportedException] {
            w.custom("hstore", Span.empty, Format.Binary)
        }
        assert(ex.message.contains("hstore"), s"error message should mention type name: ${ex.message}")
    }

    // ── Bonus: multiple params accumulate in order ────────────────────────────

    "accumulates multiple params in write order" in {
        val w = new PostgresParamWriter(TypeRegistry.empty)
        w.int(1)
        w.string("hello")
        w.nil()
        val ps = w.params
        assert(ps.size == 3, s"expected 3 params, got ${ps.size}")
        assert(ps(0).oid == PostgresEncoder.OID_INT4)
        assert(ps(1).oid == PostgresEncoder.OID_TEXT)
        assert(!ps(2).value.isDefined)
        succeed
    }

end PostgresParamWriterTest
