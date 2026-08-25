package kyo.internal.mysql

import kyo.Instant
import kyo.Maybe
import kyo.Span
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlDecodeException
import kyo.SqlException
import kyo.SqlRequestDurationOverflowException
import kyo.SqlRequestPeriodOverflowException
import kyo.SqlSchema
import kyo.SqlUnsupportedCustomTypeException
import kyo.SqlUnsupportedException
import kyo.SqlUnsupportedTypeOnBackendException
import kyo.Test
import kyo.db.Idiom
import kyo.internal.mysql.types.MysqlEncoder

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
        val w = new MysqlParamWriter()
        write(w)
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        ps(0)
    end singleParam

    "encodes Boolean true as tinyint(1)" in {
        val param = singleParam(_.boolean(true))
        assert(param.encoder.mysqlType == MysqlEncoder.TYPE_TINY, s"mysqlType mismatch: got 0x${param.encoder.mysqlType.toHexString}")
        param.encoded match
            case Maybe.Absent => fail("expected encoded bytes for true")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 1, s"expected 1 byte, got ${bytes.size}")
                assert(bytes(0) == 1.toByte, "true should encode to byte 1")
        end match
    }

    "encodes Boolean false as tinyint(1)" in {
        val param = singleParam(_.boolean(false))
        assert(param.encoder.mysqlType == MysqlEncoder.TYPE_TINY, s"mysqlType mismatch: got 0x${param.encoder.mysqlType.toHexString}")
        param.encoded match
            case Maybe.Absent => fail("expected encoded bytes for false")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 1, s"expected 1 byte, got ${bytes.size}")
                assert(bytes(0) == 0.toByte, "false should encode to byte 0")
        end match
    }

    "encodes Short via inline encoder" in {
        // TYPE_SHORT is 2 bytes little-endian signed, so the two's-complement pattern of each bound is pinned here rather
        // than recomputed from the encoder: a sign or byte-order regression in the encoder itself would survive a parity check.
        val cases = Seq(
            0.toShort      -> Array[Byte](0x00, 0x00),
            (-1).toShort   -> Array[Byte](0xff.toByte, 0xff.toByte),
            Short.MaxValue -> Array[Byte](0xff.toByte, 0x7f),
            Short.MinValue -> Array[Byte](0x00, 0x80.toByte)
        )
        for (v, expected) <- cases do
            val param = singleParam(_.short(v))
            assert(
                param.encoder.mysqlType == MysqlEncoder.TYPE_SHORT,
                s"mysqlType mismatch for $v: got 0x${param.encoder.mysqlType.toHexString}"
            )
            assertBytesMatch(param.encoded, expected, s"short $v")
        end for
        succeed
    }

    "encodes Int as int4" in {
        val values = Seq(0, -1, 1, Int.MaxValue, Int.MinValue, 42)
        for v <- values do
            val param = singleParam(_.int(v))
            assert(
                param.encoder.mysqlType == MysqlEncoder.TYPE_LONG,
                s"mysqlType mismatch for $v: got 0x${param.encoder.mysqlType.toHexString}"
            )
            assertBytesMatch(param.encoded, encode(v, MysqlEncoder.intEncoder), s"int $v")
        end for
        succeed
    }

    "encodes Long as int8" in {
        val values = Seq(0L, -1L, Long.MaxValue, Long.MinValue, 42L, 1_000_000_000_000L)
        for v <- values do
            val param = singleParam(_.long(v))
            assert(
                param.encoder.mysqlType == MysqlEncoder.TYPE_LONGLONG,
                s"mysqlType mismatch for $v: got 0x${param.encoder.mysqlType.toHexString}"
            )
            assertBytesMatch(param.encoded, encode(v, MysqlEncoder.longEncoder), s"long $v")
        end for
        succeed
    }

    "encodes Float as float4" in {
        val values = Seq(0.0f, -0.0f, Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity, 1.5f, -1.5f)
        for v <- values do
            val param = singleParam(_.float(v))
            assert(
                param.encoder.mysqlType == MysqlEncoder.TYPE_FLOAT,
                s"mysqlType mismatch for $v: got 0x${param.encoder.mysqlType.toHexString}"
            )
            // Compare Span[Byte] only, NaN != NaN in IEEE 754, but bit patterns are deterministic.
            assertBytesMatch(param.encoded, encode(v, MysqlEncoder.floatEncoder), s"float $v")
        end for
        succeed
    }

    "encodes Double as float8" in {
        val values = Seq(0.0d, -0.0d, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, Math.PI)
        for v <- values do
            val param = singleParam(_.double(v))
            assert(
                param.encoder.mysqlType == MysqlEncoder.TYPE_DOUBLE,
                s"mysqlType mismatch for $v: got 0x${param.encoder.mysqlType.toHexString}"
            )
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
            assert(
                param.encoder.mysqlType == MysqlEncoder.TYPE_NEWDECIMAL,
                s"mysqlType mismatch for $v: got 0x${param.encoder.mysqlType.toHexString}"
            )
            assertBytesMatch(param.encoded, encode(v, MysqlEncoder.bigDecimalEncoder), s"bigDecimal $v")
        end for
        succeed
    }

    "encodes String as varString" in {
        val values = Seq("", "hello", "Hello, 世界", " ", "a" * 1000)
        for v <- values do
            val param = singleParam(_.string(v))
            assert(
                param.encoder.mysqlType == MysqlEncoder.TYPE_VAR_STRING,
                s"mysqlType mismatch for '$v': got 0x${param.encoder.mysqlType.toHexString}"
            )
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
            assert(param.encoder.mysqlType == MysqlEncoder.TYPE_BLOB, s"mysqlType mismatch: got 0x${param.encoder.mysqlType.toHexString}")
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
                param.encoder.mysqlType == MysqlEncoder.TYPE_TIMESTAMP,
                s"mysqlType mismatch for $v: got 0x${param.encoder.mysqlType.toHexString}, expected 0x07"
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
            assert(
                param.encoder.mysqlType == MysqlEncoder.TYPE_TINY,
                s"mysqlType mismatch for $v: got 0x${param.encoder.mysqlType.toHexString}"
            )
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
            assert(
                param.encoder.mysqlType == MysqlEncoder.TYPE_VAR_STRING,
                s"mysqlType mismatch for '$v': got 0x${param.encoder.mysqlType.toHexString}"
            )
            // char delegates via v.toString to stringEncoder
            assertBytesMatch(param.encoded, encode(v.toString, MysqlEncoder.stringEncoder), s"char '$v'")
        end for
        succeed
    }

    "encodes BigInt via bigDecimalEncoder" in {
        val values = Seq(BigInt(0), BigInt(1), BigInt(-1), BigInt("123456789012345678901234567890"))
        for v <- values do
            val param = singleParam(_.bigInt(v))
            assert(
                param.encoder.mysqlType == MysqlEncoder.TYPE_NEWDECIMAL,
                s"mysqlType mismatch for $v: got 0x${param.encoder.mysqlType.toHexString}"
            )
            // bigInt delegates to bigDecimalEncoder via BigDecimal(v)
            assertBytesMatch(param.encoded, encode(BigDecimal(v), MysqlEncoder.bigDecimalEncoder), s"bigInt $v")
        end for
        succeed
    }

    "encodes nil (Maybe.Absent) as null param" in {
        val w = new MysqlParamWriter()
        w.nil()
        val ps = w.params
        assert(ps.size == 1, s"expected 1 param, got ${ps.size}")
        val param = ps(0)
        assert(!param.value.isDefined, "nil() should produce Maybe.Absent value")
        assert(!param.encoded.isDefined, "nil() encoded should be Maybe.Absent (SQL NULL)")
        // mysqlType is from the sentinel encoder; just verify the field is accessible.
        val _ = param.encoder.mysqlType
        succeed
    }

    "duration encodes as TIME binary, length=0 for ZERO" in {
        val param = singleParam(_.duration(java.time.Duration.ZERO))
        assert(param.encoder.mysqlType == MysqlEncoder.TYPE_TIME, s"mysqlType mismatch: got 0x${param.encoder.mysqlType.toHexString}")
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
        assert(param.encoder.mysqlType == MysqlEncoder.TYPE_TIME, s"mysqlType mismatch: got 0x${param.encoder.mysqlType.toHexString}")
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
        assert(param.encoder.mysqlType == MysqlEncoder.TYPE_TIME, s"mysqlType mismatch: got 0x${param.encoder.mysqlType.toHexString}")
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
        assert(param.encoder.mysqlType == MysqlEncoder.TYPE_TIME, s"mysqlType mismatch: got 0x${param.encoder.mysqlType.toHexString}")
        assertBytesMatch(param.encoded, encode(value, MysqlEncoder.durationEncoder), "-8h")
        param.encoded match
            case Maybe.Absent => fail("expected Present bytes")
            case Maybe.Present(bytes) =>
                assert(bytes.size == 9, s"expected 9 bytes (1 length + 8 body), got ${bytes.size}")
                assert(bytes(0) == 0x08.toByte, "length should be 8")
                assert(bytes(1) == 0x01.toByte, "is_negative should be 1 for negative duration")
        end match
    }

    "duration encoding raises SqlRequestDurationOverflowException naming the MySQL day-count bound" in {
        // Duration with more than Int.MaxValue days, MysqlParamWriter.duration() guards
        // day-count overflow eagerly and raises the typed SqlRequestDurationOverflowException leaf.
        val hugeSeconds = (Int.MaxValue.toLong + 1L) * 86400L
        val value       = java.time.Duration.ofSeconds(hugeSeconds)
        val ex = intercept[SqlRequestDurationOverflowException] {
            singleParam(_.duration(value))
        }
        assert(ex.totalDays > Int.MaxValue.toLong, s"expected totalDays > Int.MaxValue, got: ${ex.totalDays}")
        assert(ex.limit == "the MySQL TIME day-count range", s"unexpected limit: ${ex.limit}")
    }

    "calendarInterval encoding raises SqlRequestPeriodOverflowException instead of an unchecked ArithmeticException" in {
        // Period.of(Int.MaxValue, 12, 0).normalized() computes years*12L+months, divides by twelve, and narrows
        // the quotient back into an Int; that quotient lands one past Int.MaxValue here, and the JDK narrows it
        // with Math.toIntExact, which throws an unchecked ArithmeticException. MysqlParamWriter.calendarInterval()
        // guards this eagerly and raises the typed SqlRequestPeriodOverflowException leaf instead.
        val value = java.time.Period.of(Int.MaxValue, 12, 0)
        val ex = intercept[SqlRequestPeriodOverflowException] {
            singleParam(_.calendarInterval(value))
        }
        val expectedTotalMonths = Int.MaxValue.toLong * 12L + 12L
        assert(ex.totalMonths == expectedTotalMonths, s"expected totalMonths $expectedTotalMonths, got: ${ex.totalMonths}")
        assert(
            ex.limit == "the Int year range Period.normalized() narrows into",
            s"unexpected limit: ${ex.limit}"
        )
    }

    "extension rejects a payload another dialect owns, naming both dialects" in {
        // A dialect id no backend in this build owns. The property under test is that an extension payload for a
        // dialect other than the active one is rejected; naming a real sibling engine over-specifies that and
        // would make this suite depend on the other backend's module.
        val foreign = Idiom.Id("acme")
        val w       = new MysqlParamWriter()
        val ex = intercept[SqlUnsupportedTypeOnBackendException] {
            w.extension(SqlCodec.Writer.Payload(foreign, "hstore", Format.Binary, Span.empty))
        }
        assert(ex.dialect == foreign)
        assert(ex.activeDialect == MysqlEncoder.dialectId)
        assert(ex.typeName == "hstore")
        assert(w.params.isEmpty)
    }

    "extension rejects a MySQL-claimed payload, this backend implements no extension types" in {
        val w = new MysqlParamWriter()
        val ex = intercept[SqlUnsupportedCustomTypeException] {
            w.extension(SqlCodec.Writer.Payload(MysqlEncoder.dialectId, "geometry", Format.Binary, Span.empty))
        }
        assert(ex.typeName == "geometry", s"expected typeName 'geometry', got: ${ex.typeName}")
        assert(w.params.isEmpty)
    }

    // --- encodeElement, the composite-payload element SPI ---
    //
    // MySQL has no composite column type of its own, so nothing in this module reaches these two guards today. They are still MySQL's to
    // refuse, because `encodeElement` is the writer SPI core calls for any composite it composes, and the pair mirrors
    // PostgresParamWriterTest leaf for leaf. The intercepted TYPE is the property: a multi-column element is a refusal neither engine owns,
    // so it must not be reported as "Feature 'X' is not supported on mysql" against whichever engine was asked. The assertion pins the
    // engine-neutral exception type.
    // The "no dialect is named" half is asserted in SqlExceptionTest, for the reason recorded there.

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
        val w = new MysqlParamWriter()
        val ex = intercept[kyo.SqlUnsupportedMultiColumnElementException] {
            val _ = w.encodeElement(twoColumns, 1, "TwoColumnInt", Format.Binary)
        }
        assert(ex.columnCount == 2)
        assert(ex.scalaType.contains("TwoColumnInt"), s"the refusal must name the value's type, got '${ex.scalaType}'")
        assert(ex.getMessage.contains("occupies 2 SQL columns"), ex.getMessage)
    }

    "encodeElement rejects an absent element, which a composite payload cannot express" in {
        val w = new MysqlParamWriter()
        val ex = intercept[kyo.SqlUnsupportedAbsentElementException] {
            val _ = w.encodeElement(summon[kyo.SqlSchema.Column[Maybe[Int]]], Maybe.Absent, "Maybe[Int]", Format.Binary)
        }
        assert(ex.getMessage.contains("is absent, and an element of a composite value cannot be"), ex.getMessage)
    }

    "encodeElement refuses a text demand, MySQL bound parameters are binary by construction" in {
        // Unlike the two leaves above, this one is the backend's own limit and names it: BoundMysqlParam.encoded
        // has no format to pick, so there is no text rendering for a composite to inline.
        val w = new MysqlParamWriter()
        val ex = intercept[kyo.SqlUnsupportedElementFormatException] {
            val _ = w.encodeElement(kyo.SqlSchema.int, 1, "Int", Format.Text)
        }
        assert(ex.format == Format.Text)
        assert(ex.dialect == MysqlEncoder.dialectId)
        assert(ex.scalaType.contains("Int"), s"the refusal must name the value's type, got '${ex.scalaType}'")
    }

    "encodeElement answers a binary demand with the element's own column bytes" in {
        val w     = new MysqlParamWriter()
        val bytes = w.encodeElement(kyo.SqlSchema.int, 7, "Int", Format.Binary)
        // MySQL's binary protocol writes a LONG as four little-endian bytes.
        assert(bytes.toArray.toSeq == Seq[Byte](7, 0, 0, 0))
    }

    // ── Bonus: multiple params accumulate in order ────────────────────────────

    "accumulates multiple params in write order" in {
        val w = new MysqlParamWriter()
        w.int(1)
        w.string("hello")
        w.nil()
        val ps = w.params
        assert(ps.size == 3, s"expected 3 params, got ${ps.size}")
        assert(ps(0).encoder.mysqlType == MysqlEncoder.TYPE_LONG)
        assert(ps(1).encoder.mysqlType == MysqlEncoder.TYPE_VAR_STRING)
        assert(!ps(2).value.isDefined)
        succeed
    }

    // ── The rest of the SQL type vocabulary ───────────────────────────────────
    //
    // MySQL has a native column for the temporal types and JSON; everything else goes out as a string,
    // which is what the reader parses back.

    "json emits a TYPE_JSON param holding a lenenc-prefixed document" in {
        val p = singleParam(_.json("""{"a":1}"""))
        assert(p.encoder.mysqlType == MysqlEncoder.TYPE_JSON)
        assertBytesMatch(p.encoded, encode("""{"a":1}""", MysqlEncoder.jsonEncoder), "json")
    }

    "date emits a TYPE_DATE param" in {
        val value = java.time.LocalDate.of(2026, 5, 5)
        val p     = singleParam(_.date(value))
        assert(p.encoder.mysqlType == MysqlEncoder.TYPE_DATE)
        assertBytesMatch(p.encoded, encode(value, MysqlEncoder.localDateEncoder), "date")
    }

    "dateTime emits a TYPE_DATETIME param" in {
        val value = java.time.LocalDateTime.of(2026, 5, 5, 12, 30, 0)
        val p     = singleParam(_.dateTime(value))
        assert(p.encoder.mysqlType == MysqlEncoder.TYPE_DATETIME)
        assertBytesMatch(p.encoded, encode(value, MysqlEncoder.localDateTimeEncoder), "datetime")
    }

    "time emits a TYPE_TIME param" in {
        val value = java.time.LocalTime.of(13, 45, 30)
        val p     = singleParam(_.time(value))
        assert(p.encoder.mysqlType == MysqlEncoder.TYPE_TIME)
        assertBytesMatch(p.encoded, encode(value, MysqlEncoder.localTimeEncoder), "time")
    }

    "uuid, timeWithOffset, and calendarInterval go out as VAR_STRING text" in {
        val uuid       = java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val offsetTime = java.time.OffsetTime.of(13, 45, 30, 0, java.time.ZoneOffset.ofHours(5))
        val period     = java.time.Period.of(1, 2, 3)

        val cases = Seq[(MysqlParamWriter => Unit, String)](
            ((w: MysqlParamWriter) => w.uuid(uuid))                 -> uuid.toString,
            ((w: MysqlParamWriter) => w.timeWithOffset(offsetTime)) -> offsetTime.toString,
            ((w: MysqlParamWriter) => w.calendarInterval(period))   -> "P1Y2M3D"
        )
        cases.foreach { case (write, expected) =>
            val p = singleParam(write)
            assert(p.encoder.mysqlType == MysqlEncoder.TYPE_VAR_STRING, s"expected VAR_STRING for '$expected'")
            p.value match
                case Maybe.Present(text: String) => assert(text == expected)
                case other                       => fail(s"expected the text '$expected', got $other")
            end match
        }
        succeed
    }

    "arrayOfInt and arrayOfString go out as VAR_STRING JSON arrays" in {
        val ints = singleParam(_.arrayOfInt(kyo.Chunk(1, -2, 3)))
        assert(ints.encoder.mysqlType == MysqlEncoder.TYPE_VAR_STRING)
        ints.value match
            case Maybe.Present(text: String) => assert(text == "[1,-2,3]")
            case other                       => fail(s"expected a JSON array, got $other")
        end match

        val strings = singleParam(_.arrayOfString(kyo.Chunk("a", "b,c")))
        strings.value match
            case Maybe.Present(text: String) => assert(text == """["a","b,c"]""", "elements are quoted and escaped")
            case other                       => fail(s"expected a JSON array, got $other")
        end match
    }

    "arrayOfJson concatenates the element documents into a VAR_STRING array" in {
        val p = singleParam(_.arrayOfJson(kyo.Chunk("""{"a":1}""", "null")))
        assert(p.encoder.mysqlType == MysqlEncoder.TYPE_VAR_STRING)
        p.value match
            case Maybe.Present(text: String) => assert(text == """[{"a":1},null]""")
            case other                       => fail(s"expected a JSON array, got $other")
        end match
    }

end MysqlParamWriterTest
