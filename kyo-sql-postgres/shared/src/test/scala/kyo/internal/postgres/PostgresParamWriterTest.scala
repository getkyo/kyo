package kyo.internal.postgres

import kyo.Instant
import kyo.Maybe
import kyo.Span
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlDecodeException
import kyo.SqlException
import kyo.SqlRequestDurationOverflowException
import kyo.SqlSchema
import kyo.SqlUnsupportedException
import kyo.SqlUnsupportedTypeOnBackendException
import kyo.Test
import kyo.db.Idiom
import kyo.internal.postgres.types.PostgresEncoder

/** Verifies that [[PostgresParamWriter]] produces [[BoundParam]] instances whose wire bytes are byte-for-byte identical to the output of
  * the corresponding [[PostgresEncoder]] singletons.
  *
  * Each parity test computes the expected bytes at runtime by calling the encoder through a [[PostgresBufferWriter]], then asserts that the
  * writer produces identical bytes. This ensures the parity invariant is checked without hardcoded byte-array snapshots.
  *
  * Two leaves (LocalDate, LocalDateTime) build their [[BoundParam]] from the encoder singleton directly rather than through a writer call,
  * so the encoder's own declared OID and byte width stay pinned independently of which vocabulary method routes to it.
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
            assert(param.encoder.oid == PostgresEncoder.OID_INT4, s"OID mismatch for $v")
            assert(param.encoder.format == Format.Binary, s"format mismatch for $v")
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.int4Binary), s"int $v")
        end for
        succeed
    }

    "encodes Long as int8Binary" in {
        val values = Seq(0L, -1L, Long.MaxValue, Long.MinValue, 42L, 1_000_000_000_000L)
        for v <- values do
            val param = singleParam(_.long(v))
            assert(param.encoder.oid == PostgresEncoder.OID_INT8, s"OID mismatch for $v")
            assert(param.encoder.format == Format.Binary, s"format mismatch for $v")
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.int8Binary), s"long $v")
        end for
        succeed
    }

    "encodes Short as int2Binary" in {
        val values = Seq(0.toShort, (-1).toShort, Short.MaxValue, Short.MinValue)
        for v <- values do
            val param = singleParam(_.short(v))
            assert(param.encoder.oid == PostgresEncoder.OID_INT2, s"OID mismatch for $v")
            assert(param.encoder.format == Format.Binary, s"format mismatch for $v")
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.int2Binary), s"short $v")
        end for
        succeed
    }

    "encodes String as text" in {
        val values = Seq("", "hello", "Hello, 世界", " ", "a" * 10000)
        for v <- values do
            val param = singleParam(_.string(v))
            assert(param.encoder.oid == PostgresEncoder.OID_TEXT, s"OID mismatch for '$v'")
            assert(param.encoder.format == Format.Text, s"format mismatch for '$v' (must be String, not Binary)")
            assertBytesMatch(param.encoded, encode(v, PostgresEncoder.textText), s"string '$v'")
        end for
        succeed
    }

    "encodes Boolean true as boolBinary" in {
        val param = singleParam(_.boolean(true))
        assert(param.encoder.oid == PostgresEncoder.OID_BOOL)
        assert(param.encoder.format == Format.Binary)
        param.encoded match
            case Maybe.Absent => fail("expected encoded bytes for true")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 1, s"expected 1 byte, got ${bytes.size}")
                assert(bytes(0) == 1.toByte, "true should encode to byte 1")
        end match
    }

    "encodes Boolean false as boolBinary" in {
        val param = singleParam(_.boolean(false))
        assert(param.encoder.oid == PostgresEncoder.OID_BOOL)
        assert(param.encoder.format == Format.Binary)
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
            assert(param.encoder.oid == PostgresEncoder.OID_FLOAT4, s"OID mismatch for $v")
            assert(param.encoder.format == Format.Binary, s"format mismatch for $v")
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
            assert(param.encoder.oid == PostgresEncoder.OID_FLOAT8, s"OID mismatch for $v")
            assert(param.encoder.format == Format.Binary, s"format mismatch for $v")
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
            assert(param.encoder.oid == PostgresEncoder.OID_NUMERIC, s"OID mismatch for $v")
            // bigDecimal binds numericText (Format.Text), not numericBinary, so a parameterised query stays on the
            // same planner plan as the literal one the static renderer emits.
            assert(param.encoder.format == Format.Text, s"format must be String (not Binary) for $v")
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
            assert(param.encoder.oid == PostgresEncoder.OID_BYTEA, s"OID mismatch")
            assert(param.encoder.format == Format.Binary, s"format mismatch")
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
            assert(param.encoder.oid == PostgresEncoder.OID_TIMESTAMPTZ, s"OID mismatch for $v")
            assert(param.encoder.format == Format.Binary, s"format mismatch for $v")
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
            assert(param.encoder.oid == PostgresEncoder.OID_DATE, s"OID mismatch for $v")
            assert(param.encoder.format == Format.Binary, s"format mismatch for $v")
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
            assert(param.encoder.oid == PostgresEncoder.OID_TIMESTAMP, s"OID mismatch for $v")
            assert(param.encoder.format == Format.Binary, s"format mismatch for $v")
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
        assert(!param.encoded.isDefined, "nil() encoded should be Maybe.Absent, so no bytes reach the wire")
        // The OID is NOT ignored. A NULL slot sends no bytes, but its OID still travels in the Parse message and
        // the server type-checks the parameter against it, so a concrete OID declares the parameter to BE that
        // type: `int4` for an absent value bound into a TEXT
        // column is a mismatch the server rejects, naming a type the caller never wrote. Oid 0 is the protocol's
        // own "no type named here", which leaves the target column to drive inference. Asserting the value rather
        // than merely reading the field is what makes this a regression guard for that choice.
        assert(
            param.encoder.oid == PostgresEncoder.OID_UNSPECIFIED,
            s"an absent parameter must declare oid 0 so the column drives inference, got ${param.encoder.oid}"
        )
    }

    "date resolves the builtin 'date' name to OID=1082 without a registry entry" in {
        val w    = new PostgresParamWriter(TypeRegistry.empty) // empty registry
        val date = java.time.LocalDate.of(2000, 1, 11)         // 10 days after the PG epoch
        w.date(date)
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        assert(ps(0).encoder.oid == PostgresEncoder.OID_DATE, s"expected OID=${PostgresEncoder.OID_DATE} got ${ps(0).encoder.oid}")
        assert(ps(0).encoder.format == Format.Binary)
        assertBytesMatch(ps(0).encoded, Array[Byte](0x00, 0x00, 0x00, 0x0a), "date")
    }

    "dateTime resolves the builtin 'timestamp' name to OID=1114 without a registry entry" in {
        val w  = new PostgresParamWriter(TypeRegistry.empty) // empty registry
        val dt = java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 0, 10_000)
        w.dateTime(dt)
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        assert(
            ps(0).encoder.oid == PostgresEncoder.OID_TIMESTAMP,
            s"expected OID=${PostgresEncoder.OID_TIMESTAMP} got ${ps(0).encoder.oid}"
        )
        assert(ps(0).encoder.format == Format.Binary)
        // 10 microseconds after the PG epoch, as a big-endian Int64.
        assertBytesMatch(ps(0).encoded, Array[Byte](0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a), "timestamp")
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
        assert(param.encoder.oid == PostgresEncoder.OID_INTERVAL, s"expected OID=1186 got ${param.encoder.oid}")
        assert(param.encoder.format == Format.Binary, s"expected Binary format got ${param.encoder.format}")
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
        assert(param.encoder.oid == PostgresEncoder.OID_INTERVAL)
        assert(param.encoder.format == Format.Binary)
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
        assert(param.encoder.oid == PostgresEncoder.OID_INTERVAL)
        assert(param.encoder.format == Format.Binary)
        param.encoded match
            case Maybe.Absent => fail("expected encoded bytes for Duration.ofSeconds(-30)")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 16, s"expected 16 bytes, got ${bytes.size}")
                assert(bytes.toArray.sameElements(expected), s"byte mismatch: ${bytes.toArray.toSeq}")
        end match
    }

    "duration encoding raises SqlRequestDurationOverflowException naming PostgreSQL's own bound" in {
        val w = new PostgresParamWriter(TypeRegistry.empty)
        // 9_223_372_036_855L seconds × 1_000_000 overflows Int64
        val overflowDuration = java.time.Duration.ofSeconds(9_223_372_036_855L)
        val ex = intercept[SqlRequestDurationOverflowException] {
            w.duration(overflowDuration)
        }
        val expectedDays = overflowDuration.getSeconds / 86_400L
        assert(ex.totalDays == expectedDays, s"expected totalDays $expectedDays, got: ${ex.totalDays}")
        // The bound must be PostgreSQL's own. The limit is a parameter of the exception rather than a constant in
        // its message, so a Postgres user overflowing an interval is never told about MySQL's TIME range, and the
        // text is asserted here because nothing else reads it.
        assert(ex.limit == "the PostgreSQL interval microsecond range", s"unexpected limit: ${ex.limit}")
        assert(!ex.getMessage.contains("MySQL"), s"a Postgres overflow must not name MySQL: ${ex.getMessage}")
    }

    "extension with an unknown type name throws SqlUnsupportedException" in {
        val w = new PostgresParamWriter(TypeRegistry.empty) // registry is empty
        val ex = intercept[SqlUnsupportedException] {
            w.extension(SqlCodec.Writer.Payload(PostgresEncoder.dialectId, "geometry", Format.Binary, Span.empty))
        }
        assert(ex.message.contains("geometry"), s"error message should mention type name: ${ex.message}")
    }

    "extension with a populated TypeRegistry uses the registered OID in the resulting BoundParam" in {
        val geomOid = 12345
        val reg     = TypeRegistry(Map("geometry" -> geomOid))
        val w       = new PostgresParamWriter(reg)
        val bytes   = Span.from(Array[Byte](0x01, 0x02, 0x03))
        w.extension(SqlCodec.Writer.Payload(PostgresEncoder.dialectId, "geometry", Format.Binary, bytes))
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        assert(ps(0).encoder.oid == geomOid, s"expected OID=$geomOid but got ${ps(0).encoder.oid}")
        assertBytesMatch(ps(0).encoded, Array[Byte](0x01, 0x02, 0x03), "geometry payload")
    }

    "extension with a populated TypeRegistry throws SqlUnsupportedException for unregistered type names" in {
        val reg = TypeRegistry(Map("geometry" -> 12345))
        val w   = new PostgresParamWriter(reg)
        val ex = intercept[SqlUnsupportedException] {
            w.extension(SqlCodec.Writer.Payload(PostgresEncoder.dialectId, "hstore", Format.Binary, Span.empty))
        }
        assert(ex.message.contains("hstore"), s"error message should mention type name: ${ex.message}")
    }

    "extension rejects a payload another dialect owns before consulting the registry" in {
        // A dialect id no backend in this build owns. The property under test is that an extension payload for a
        // dialect other than the active one is rejected; naming a real sibling engine over-specifies that and
        // would make this suite depend on the other backend's module.
        val foreign = Idiom.Id("acme")
        val w       = new PostgresParamWriter(TypeRegistry(Map("geometry" -> 12345)))
        val ex = intercept[SqlUnsupportedTypeOnBackendException] {
            w.extension(SqlCodec.Writer.Payload(foreign, "geometry", Format.Binary, Span.empty))
        }
        assert(ex.dialect == foreign)
        assert(ex.activeDialect == PostgresEncoder.dialectId)
        assert(w.params.isEmpty)
    }

    // ── Bonus: multiple params accumulate in order ────────────────────────────

    "accumulates multiple params in write order" in {
        val w = new PostgresParamWriter(TypeRegistry.empty)
        w.int(1)
        w.string("hello")
        w.nil()
        val ps = w.params
        assert(ps.size == 3, s"expected 3 params, got ${ps.size}")
        assert(ps(0).encoder.oid == PostgresEncoder.OID_INT4)
        assert(ps(1).encoder.oid == PostgresEncoder.OID_TEXT)
        assert(!ps(2).value.isDefined)
        succeed
    }

    // ── The rest of the SQL type vocabulary ───────────────────────────────────
    //
    // Each method must reach the wire under the OID of the `pg_type` name for that SQL type, carrying
    // exactly what the corresponding PostgresEncoder produces.

    "json emits a jsonb param (OID 3802) holding the version byte plus the document" in {
        val p = singleParam(_.json("""{"a":1}"""))
        assert(p.encoder.oid == PostgresEncoder.OID_JSONB)
        assert(p.encoder.format == Format.Binary)
        assertBytesMatch(p.encoded, encode("""{"a":1}""", PostgresEncoder.jsonbBinary), "jsonb")
    }

    "uuid emits a uuid param (OID 2950) holding the 16 binary bytes" in {
        val value = java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val p     = singleParam(_.uuid(value))
        assert(p.encoder.oid == PostgresEncoder.OID_UUID)
        assertBytesMatch(p.encoded, encode(value, PostgresEncoder.uuidBinary), "uuid")
    }

    "time emits a time param (OID 1083)" in {
        val value = java.time.LocalTime.of(13, 45, 30)
        val p     = singleParam(_.time(value))
        assert(p.encoder.oid == PostgresEncoder.OID_TIME)
        assertBytesMatch(p.encoded, encode(value, PostgresEncoder.timeBinary), "time")
    }

    "timeWithOffset emits a timetz param (OID 1266)" in {
        val value = java.time.OffsetTime.of(13, 45, 30, 0, java.time.ZoneOffset.ofHours(5))
        val p     = singleParam(_.timeWithOffset(value))
        assert(p.encoder.oid == PostgresEncoder.OID_TIMETZ)
        assertBytesMatch(p.encoded, encode(value, PostgresEncoder.timetzBinary), "timetz")
    }

    "calendarInterval emits an interval param (OID 1186)" in {
        val value = java.time.Period.of(1, 6, 15)
        val p     = singleParam(_.calendarInterval(value))
        assert(p.encoder.oid == PostgresEncoder.OID_INTERVAL)
        assertBytesMatch(p.encoded, encode(value, PostgresEncoder.intervalPeriodBinary), "interval")
    }

    "arrayOfInt emits an _int4 param (OID 1007)" in {
        val values = kyo.Chunk(1, 2, 3)
        val p      = singleParam(_.arrayOfInt(values))
        assert(p.encoder.oid == 1007)
        assertBytesMatch(p.encoded, encode(values, PostgresEncoder.int4ArrayBinary), "_int4")
    }

    "arrayOfString emits a _text param (OID 1009)" in {
        val values = kyo.Chunk("a", "b")
        val p      = singleParam(_.arrayOfString(values))
        assert(p.encoder.oid == 1009)
        assertBytesMatch(p.encoded, encode(values, PostgresEncoder.textArrayBinary), "_text")
    }

    "arrayOfJson emits a _jsonb param (OID 3807)" in {
        val values = kyo.Chunk("""{"a":1}""", "null")
        val p      = singleParam(_.arrayOfJson(values))
        assert(p.encoder.oid == PostgresEncoder.OID_JSONB_ARRAY)
        assertBytesMatch(p.encoded, encode(values, PostgresEncoder.jsonbArrayBinary), "_jsonb")
    }

    // --- encodeElement, the composite-payload element SPI ---

    "encodeElement returns the element's own column bytes, so a composite can inline them" in {
        val w     = new PostgresParamWriter(TypeRegistry.empty)
        val bytes = w.encodeElement(SqlSchema.int, 7, "Int", Format.Binary)
        // int4 binary is 4 big-endian bytes, and nothing is appended to the writer's own params.
        assert(bytes.toArray.toSeq == Seq[Byte](0, 0, 0, 7))
        assert(w.params.isEmpty, "encodeElement must not emit a bind parameter of its own")
    }

    "encodeElement meets a binary demand for numeric with the binary sibling" in {
        // The standalone `numeric` bind stays text (byte-for-byte identical to the static renderer, which keeps a
        // parameterised query on the same plan as the literal one), but an element inside a binary composite never
        // reaches the planner as a parameter, so the demand selects `numericBinary` instead of refusing.
        val w     = new PostgresParamWriter(TypeRegistry.empty)
        val bytes = w.encodeElement(SqlSchema.bigDecimal, BigDecimal(1), "BigDecimal", Format.Binary)
        val expected =
            val buf = new PostgresBufferWriter()
            PostgresEncoder.numericBinary.write(BigDecimal(1), buf)
            buf.toSpan
        end expected
        assert(bytes.toArray.toSeq == expected.toArray.toSeq)
        assert(w.params.isEmpty, "encodeElement must not emit a bind parameter of its own")
    }

    "encodeElement accepts the demand its encoder for the type does meet" in {
        // The same `numeric` encoder answers a text demand, so the refusal above is about the demand rather than
        // about the type: nothing here is unencodable, only mismatched.
        val w     = new PostgresParamWriter(TypeRegistry.empty)
        val bytes = w.encodeElement(SqlSchema.bigDecimal, BigDecimal(1), "BigDecimal", Format.Text)
        assert(new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8) == "1")
    }

    // Both refusals are universal rather than PostgreSQL's: no flavor's composite wire form gives one element room for a second column or
    // for an absent value, so a message naming this backend sends the reader looking for a MySQL spelling that does not exist either. The
    // intercepted TYPE is what pins that here, because a dialect-named leaf formats every message as
    // "Feature 'X' is not supported on postgres" and cannot be thrown at a leaf expecting a dialect-neutral one.
    //
    // The "no dialect is named" half is asserted in SqlExceptionTest instead, on the whole rendered sentence, and it has to be: in
    // development mode `getMessage` returns the frame render, which carries this suite's own `kyo.internal.postgres` package name, so a
    // `!getMessage.contains("postgres")` here reads the test file rather than the message and fails over a clean message.
    // MysqlParamWriterTest carries the mirror of both leaves.

    "encodeElement rejects an element that occupies more than one column" in {
        // A tuple or case class cannot reach here at all: `encodeElement` takes the single-column tier, so a row
        // is a compile error at the call site. What is still reachable is a hand-installed column whose write
        // emits more than the one column it declares, which is what this one is.
        val twoColumns = kyo.SqlSchema.of[Int](
            (v, w) =>
                w.int(v); w.int(v)
            ,
            r => r.int()
        )
        val w = new PostgresParamWriter(TypeRegistry.empty)
        val ex = intercept[kyo.SqlUnsupportedMultiColumnElementException] {
            val _ = w.encodeElement(twoColumns, 1, "TwoColumnInt", Format.Binary)
        }
        assert(ex.columnCount == 2)
        assert(ex.scalaType.contains("TwoColumnInt"), s"the refusal must name the value's type, got '${ex.scalaType}'")
        assert(ex.getMessage.contains("occupies 2 SQL columns"), ex.getMessage)
    }

    "encodeElement rejects an absent element, which a composite payload cannot express" in {
        val w = new PostgresParamWriter(TypeRegistry.empty)
        val ex = intercept[kyo.SqlUnsupportedAbsentElementException] {
            val _ = w.encodeElement(summon[SqlSchema.Column[Maybe[Int]]], Maybe.Absent, "Maybe[Int]", Format.Binary)
        }
        assert(ex.getMessage.contains("is absent, and an element of a composite value cannot be"), ex.getMessage)
    }

    "Range[Int] writes the exact range binary form: flags, then int32 length plus element bytes per bound" in {
        val range  = kyo.PostgresTypes.Range(kyo.PostgresTypes.Range.Bound.Inclusive(1), kyo.PostgresTypes.Range.Bound.Exclusive(10))
        val column = summon[SqlSchema.Column[kyo.PostgresTypes.Range[Int]]]
        val p      = singleParam(w => column.write(range, w))
        assert(p.encoder.oid == 3904, "int4range resolves its builtin OID")
        // flags 0x02 (lower inclusive, upper exclusive), then each int4 element as int32 length 4 plus 4 bytes.
        val expected = Seq[Byte](0x02, 0, 0, 0, 4, 0, 0, 0, 1, 0, 0, 0, 4, 0, 0, 0, 10)
        p.encoded match
            case Maybe.Present(bytes) => assert(bytes.toArray.toSeq == expected)
            case Maybe.Absent         => fail("expected the range param to carry bytes, got an absent one")
        end match
    }

    // ── PostgresTypes.custom as the escape hatch for a removed dedicated address type ──────────
    //
    // kyo-sql carries no address type of its own; a caller wanting a native `inet` column reaches for
    // PostgresTypes.custom with a plain String and its own wire encoding. `builtinTypeOids` already maps
    // "inet" to OID 869 for the extension channel, independently of any dedicated Scala type, so the OID
    // resolution below is the same one a dedicated type would have gotten.

    "PostgresTypes.custom[String] naming inet resolves the builtin inet OID and writes the inet wire form" in {
        // Wire: family(1) + prefix_bits(1) + is_cidr(1) + addr_len(1) + addr_bytes(N). family 2 = IPv4,
        // prefix 32 = a full host. Encoding a dotted-decimal string into this shape is the caller's own
        // job: the escape hatch supplies no address parser of its own.
        def encodeIPv4(dotted: String): Span[Byte] =
            val octets = dotted.split('.').map(s => s.toInt.toByte)
            Span.from(Array[Byte](2, 32, 0, 4) ++ octets)

        val column = kyo.PostgresTypes.custom[String] { (addr, w) =>
            w.extension(SqlCodec.Writer.Payload(PostgresEncoder.dialectId, "inet", Format.Binary, encodeIPv4(addr)))
        } { r =>
            val bytes = r.nextExtension(PostgresEncoder.dialectId, "inet").bytes
            s"${bytes(4) & 0xff}.${bytes(5) & 0xff}.${bytes(6) & 0xff}.${bytes(7) & 0xff}"
        }

        // The OID comes from the payload's own type name, which is the only place a custom column states one.
        val p = singleParam(w => column.write("192.168.1.1", w))
        assert(p.encoder.oid == PostgresEncoder.OID_INET, s"expected the builtin inet OID 869, got ${p.encoder.oid}")
        assertBytesMatch(p.encoded, Array[Byte](2, 32, 0, 4, 192.toByte, 168.toByte, 1, 1), "inet")
    }

end PostgresParamWriterTest
