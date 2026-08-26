package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Frame
import kyo.Instant
import kyo.JsonText
import kyo.Maybe
import kyo.Span
import kyo.SqlCodec.Format
import kyo.SqlDecodeColumnAbsentException
import kyo.SqlDecodeColumnTypeMismatchException
import kyo.SqlDecodeException
import kyo.SqlDecodeIntervalException
import kyo.SqlDecodeMultiCharacterForCharException
import kyo.SqlDecodeTemporalException
import kyo.SqlDecodeValueRangeException
import kyo.SqlException
import kyo.SqlRow
import kyo.SqlUnsupportedTypeOnBackendException
import kyo.Test
import kyo.db.Idiom
import kyo.internal.mysql.types.MysqlEncoder

/** Verifies that [[MysqlRowReader]] decodes raw binary wire bytes from a [[kyo.SqlRow]] into the correct Scala values.
  *
  * Each test constructs a [[SqlRow]] whose column bytes mirror what [[BinaryResultsetRowUnmarshaller]] delivers, fixed-width LE integers
  * for numeric types, raw UTF-8 bytes for strings, and datetime struct bodies (length prefix already stripped) for temporal types. The
  * reader is then asserted to return the original Scala value, confirming byte-for-byte round-trip parity with the [[MysqlEncoder]] layer.
  *
  * All tests are pure unit tests on wire bytes; no MySQL container or network I/O is required.
  */
class MysqlRowReaderTest extends Test:

    // `java.time.Period` is a JDK type with no `CanEqual` instance in scope; its `equals` compares years, months
    // and days field by field, which is exactly the comparison the Period assertions below rely on.
    given CanEqual[java.time.Period, java.time.Period] = CanEqual.canEqualAny

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Encodes `value` using `enc` and returns the raw wire bytes (without lenenc prefix, as delivered by the binary protocol). */
    private def encodeRaw[A](value: A, enc: MysqlEncoder[A]): Span[Byte] =
        val buf = new MysqlBufferWriter
        enc.write(value, buf)
        buf.toSpan
    end encodeRaw

    private def field(name: String): SqlRow.Column =
        SqlRow.Column(name, 0) // MySQL reports no PostgreSQL-style OIDs, so every token is zero
    end field

    /** Builds a [[SqlRow]] carrying the MySQL codec for the binary protocol. */
    private def mysqlBinaryRow(values: Chunk[Maybe[Span[Byte]]], columns: Chunk[SqlRow.Column]): SqlRow =
        new SqlRow(values, columns, MysqlRowCodec(Format.Binary))

    /** Builds a [[SqlRow]] with a single column whose stored bytes are `bytes`.
      *
      * Mimics the output of [[BinaryResultsetRowUnmarshaller]]: column bytes are the struct body without any length prefix.
      */
    private def singleColumnRow(bytes: Span[Byte]): SqlRow =
        mysqlBinaryRow(Chunk(Maybe.Present(bytes)), Chunk(field("column")))
    end singleColumnRow

    /** Builds a [[SqlRow]] with a single NULL column (Maybe.Absent). */
    private def nullColumnRow(): SqlRow =
        mysqlBinaryRow(Chunk(Maybe.empty[Span[Byte]]), Chunk(field("column")))
    end nullColumnRow

    /** Returns a [[MysqlRowReader]] wrapping the given [[SqlRow]]. */
    private def reader(row: SqlRow): MysqlRowReader =
        new MysqlRowReader(row, Format.Binary)
    end reader

    "decodes Long from 8-byte LE" in {
        val values = Seq(0L, 1L, -1L, Long.MaxValue, Long.MinValue, 42L, 1_000_000_000_000L)
        for v <- values do
            val row = singleColumnRow(encodeRaw(v, MysqlEncoder.longEncoder))
            val r   = reader(row)
            assert(r.long() == v, s"long $v")
        end for
        succeed
    }

    "decodes Int from 4-byte LE" in {
        val values = Seq(0, 1, -1, Int.MaxValue, Int.MinValue, 42)
        for v <- values do
            val row = singleColumnRow(encodeRaw(v, MysqlEncoder.intEncoder))
            val r   = reader(row)
            assert(r.int() == v, s"int $v")
        end for
        succeed
    }
    // The reader inlines readInt2LE rather than reaching for a decoder object: there is no decoder table on this backend.

    "decodes Short from 2-byte LE" in {
        val values = Seq(0.toShort, 1.toShort, (-1).toShort, Short.MaxValue, Short.MinValue)
        for v <- values do
            // Build 2-byte LE from a buffer writer (mirrors the inline shortEncoder in MysqlParamWriter).
            val buf = new MysqlBufferWriter
            buf.writeUInt16LE(v.toInt)
            val row = singleColumnRow(buf.toSpan)
            val r   = reader(row)
            assert(r.short() == v, s"short $v")
        end for
        succeed
    }

    "decodes Boolean from 1-byte TINY (nonzero = true, 0 = false)" in {
        val trueRow  = singleColumnRow(encodeRaw(true, MysqlEncoder.boolEncoder))
        val falseRow = singleColumnRow(encodeRaw(false, MysqlEncoder.boolEncoder))
        assert(reader(trueRow).boolean() == true, "byte=1 should be true")
        assert(reader(falseRow).boolean() == false, "byte=0 should be false")
        // Any nonzero byte is true.
        val nonzeroRow = singleColumnRow(Span.from(Array[Byte](2.toByte)))
        assert(reader(nonzeroRow).boolean() == true, "byte=2 should also be true")
    }
    // BinaryResultsetRowUnmarshaller strips the lenenc prefix; stored bytes are raw UTF-8.

    "decodes String from UTF-8 bytes" in {
        val values = Seq("hello", "", "Hello, 世界", "a" * 1000)
        for v <- values do
            // Store raw UTF-8 bytes (no lenenc prefix) as delivered by the unmarshaller.
            val rawBytes = Span.from(v.getBytes(StandardCharsets.UTF_8))
            val row      = singleColumnRow(rawBytes)
            val r        = reader(row)
            assert(r.string() == v, s"string '$v'")
        end for
        succeed
    }

    "decodes Float from 4-byte IEEE 754 LE" in {
        val values = Seq(0.0f, 1.5f, -1.5f, Float.MaxValue, Float.MinPositiveValue)
        for v <- values do
            val row = singleColumnRow(encodeRaw(v, MysqlEncoder.floatEncoder))
            val r   = reader(row)
            assert(r.float() == v, s"float $v")
        end for
        succeed
    }

    "decodes Double from 8-byte IEEE 754 LE" in {
        val values = Seq(0.0d, 1.5d, -1.5d, Math.PI, Double.MaxValue, Double.MinPositiveValue)
        for v <- values do
            val row = singleColumnRow(encodeRaw(v, MysqlEncoder.doubleEncoder))
            val r   = reader(row)
            assert(r.double() == v, s"double $v")
        end for
        succeed
    }
    // NEWDECIMAL wire: bytes stored are raw UTF-8 text (lenenc prefix stripped by unmarshaller).

    "decodes BigDecimal from UTF-8 text bytes" in {
        val values = Seq(
            BigDecimal("0"),
            BigDecimal("1"),
            BigDecimal("-1"),
            BigDecimal("12345.678"),
            BigDecimal("-9999999999.9999"),
            BigDecimal("0.00001")
        )
        for v <- values do
            // Use v.toString (Scala BigDecimal), NOT v.underlying().toPlainString, see
            // MysqlEncoder.bigDecimalEncoder for the same workaround.
            val rawBytes = Span.from(v.toString.getBytes(StandardCharsets.UTF_8))
            val row      = singleColumnRow(rawBytes)
            val r        = reader(row)
            assert(r.bigDecimal() == v, s"bigDecimal $v")
        end for
        succeed
    }

    "decodes Span[Byte] passthrough" in {
        val values = Seq(
            Span.empty[Byte],
            Span.from(Array[Byte](0x00.toByte, 0xff.toByte, 0x7f.toByte)),
            Span.from(Array.fill[Byte](256)(0xab.toByte))
        )
        for v <- values do
            val row = singleColumnRow(v)
            val r   = reader(row)
            assert(r.bytes().toArray.sameElements(v.toArray), s"bytes passthrough mismatch")
        end for
        succeed
    }
    //
    // MysqlEncoder.instantEncoder writes: length_byte(1) + struct_body(7 or 11 bytes).
    // BinaryResultsetRowUnmarshaller reads via lenenc path: reads the length byte, then reads
    // that many body bytes. MysqlRow.values stores the struct body (7 or 11 bytes) WITHOUT
    // the leading length byte.
    //
    // MysqlRowReader.instant() decodes the struct body inline (same logic as
    // MysqlTemporalDecoder.decodeDatetimeBytes) and converts to java.time.Instant via UTC zone.

    "decodes java.time.Instant from MySQL TIMESTAMP binary struct" in {
        val values = Seq(
            java.time.Instant.EPOCH,
            java.time.Instant.ofEpochSecond(1_000_000L),
            java.time.Instant.parse("2024-06-15T12:30:45Z"),
            java.time.Instant.parse("1999-12-31T23:59:59Z")
        )
        for v <- values do
            val kyoInstant = Instant.fromJava(v)
            // encodeRaw produces: length_byte(1) + struct_body(7 bytes for whole-second instants).
            // Strip the first byte (the length prefix) to get just the struct body.
            val fullEncoded = encodeRaw(kyoInstant, MysqlEncoder.instantEncoder)
            val structBody  = fullEncoded.slice(1, fullEncoded.size) // drop the length prefix byte
            val row         = singleColumnRow(structBody)
            val r           = reader(row)
            val decoded     = r.instant()
            // Compare at second precision (MySQL TIMESTAMP wire is whole-second in the 7-byte form).
            assert(
                decoded.getEpochSecond == v.getEpochSecond,
                s"instant $v: got epoch-second ${decoded.getEpochSecond}, expected ${v.getEpochSecond}"
            )
        end for
        succeed
    }

    // A true answer has to consume the column, matching PostgresRowReader. The nullable column codec reads a
    // `Maybe` field as `if r.isNil() then Maybe.empty else Maybe(inner.read(r))`, so a true answer that left the
    // cursor in place would leave the NULL column in front of the next field's read and shift every field after
    // it by one.
    "isNil returns true for a NULL column and consumes it" in {
        val row = mysqlBinaryRow(
            Chunk(Maybe.empty[Span[Byte]], Maybe.Present(encodeRaw(99, MysqlEncoder.intEncoder))),
            Chunk(field("nullable"), field("present"))
        )
        val r = reader(row)
        assert(r.isNil() == true)
        // The cursor moved past the NULL, so the next read sees column 1 rather than re-reading column 0.
        assert(r.int() == 99)
    }

    "a NULL in a leading column is consumed exactly once, so the fields after it keep their columns" in {
        // Two nullable columns and a trailing value: a true `isNil` that consumed nothing would read the first
        // NULL again for the second field and leave the value column unread, and a true one that consumed two
        // would skip past it. Only consuming exactly one lands the trailing value in the trailing field.
        val row = mysqlBinaryRow(
            Chunk(Maybe.empty[Span[Byte]], Maybe.empty[Span[Byte]], Maybe.Present(encodeRaw(5, MysqlEncoder.intEncoder))),
            Chunk(field("_1"), field("_2"), field("_3"))
        )
        kyo.Abort.run[SqlDecodeException](row.decode[(Maybe[Int], Maybe[Int], Int)]).eval match
            case kyo.Result.Success((first, second, third)) =>
                assert(first == Maybe.empty[Int], s"the first NULL must decode as absent, got $first")
                assert(second == Maybe.empty[Int], s"the second NULL must decode as absent, got $second")
                assert(third == 5, s"the trailing value must reach the trailing field, got $third")
            case other => fail(s"Expected the trailing column to reach the trailing field, got $other")
        end match
    }

    "a value read on a NULL column that isNil never saw still raises" in {
        val row = nullColumnRow()
        val r   = reader(row)
        intercept[SqlDecodeException] {
            r.int()
        }
        succeed
    }

    "isNil returns false for non-NULL column and does not advance the cursor" in {
        val row = singleColumnRow(encodeRaw(99, MysqlEncoder.intEncoder))
        val r   = reader(row)
        assert(r.isNil() == false)
        // Cursor was not advanced, value is still readable.
        assert(r.int() == 99)
    }

    "decodeElement reads the slice in the format the caller states, not the one this row arrived in" in {
        // The bytes are cut out of a composite payload, whose layout fixes its elements' format. The enclosing
        // column's format describes the composite, so using it for the element decodes the wrong grammar whenever
        // the two differ, which is why the format is a parameter here.
        val column = kyo.SqlSchema.int

        val binaryReader = readerFor(singleColumnRow(Span.empty), Format.Binary)
        assert(binaryReader.decodeElement(column, asciiBytes("42"), Format.Text) == 42)

        val textReader = readerFor(singleColumnRow(Span.empty), Format.Text)
        assert(textReader.decodeElement(column, encodeRaw(7, MysqlEncoder.intEncoder), Format.Binary) == 7)
    }

    "nextExtension rejects every type, this backend implements no extension types" in {
        val payload = Span.from(Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte))
        val row     = singleColumnRow(payload)
        val r       = reader(row)
        // A dialect id no backend in this build owns. The property under test is that an extension payload for a
        // dialect other than the active one is rejected; naming a real sibling engine over-specifies that and
        // would make this suite depend on the other backend's module.
        val foreign = Idiom.Id("acme")
        val ex = intercept[SqlUnsupportedTypeOnBackendException] {
            val _ = r.nextExtension(foreign, "geometry")
        }
        assert(ex.dialect == foreign)
        assert(ex.activeDialect == MysqlEncoder.dialectId)
        assert(ex.typeName == "geometry")
        // The cursor did not advance: the column is still readable.
        assert(r.bytes().toArray.sameElements(payload.toArray))
    }

    "multiple columns read positionally" in {
        val intBytes  = encodeRaw(7, MysqlEncoder.intEncoder)
        val strBytes  = Span.from("alice".getBytes(StandardCharsets.UTF_8))
        val boolBytes = encodeRaw(true, MysqlEncoder.boolEncoder)

        val multiRow = mysqlBinaryRow(
            Chunk[Maybe[Span[Byte]]](
                Maybe.Present(intBytes),
                Maybe.Present(strBytes),
                Maybe.Present(boolBytes)
            ),
            Chunk(field("id"), field("name"), field("active"))
        )
        val r = reader(multiRow)

        assert(r.int() == 7, "first column: int")
        assert(r.string() == "alice", "second column: string")
        assert(r.boolean() == true, "third column: boolean")
    }

    "frame is preserved through to subclass" in {
        // The reader captures the Frame at its construction site (inside makeReader).
        // The assertion is that the captured Frame's call-site parent points back at the
        // makeReader definition, not at the test's outer scope.
        def makeReader()(using f: Frame): (Frame, MysqlRowReader) =
            val row = singleColumnRow(encodeRaw(0, MysqlEncoder.intEncoder))
            // Constructed here, not through the `reader` helper, so the captured Frame is this site's.
            (f, new MysqlRowReader(row, Format.Binary))
        end makeReader
        val (frameAtConstruction, r) = makeReader()
        assert(r.frame == frameAtConstruction, "MysqlRowReader must expose the Frame captured at construction")
    }

    // ── An array read over a scalar column raises a decode failure ────────────
    // SQL rows are flat, so a column holding four little-endian bytes is not a JSON array. The refusal has to be
    // a decode failure: an Unsupported leaf would say the backend cannot read arrays at all, which is the
    // opposite of what happened.

    "an array read over a scalar column raises a decode failure, not an unsupported-operation one" in {
        val row = singleColumnRow(encodeRaw(0, MysqlEncoder.intEncoder))
        val r   = reader(row)
        intercept[SqlDecodeException] { val _ = r.nextArrayOfInt() }
        succeed
    }

    // ── A row decodes into a case class by column name ────────────────────────
    // A MySQL result set is positional on the wire, and the row codec matches each column to its field through
    // the shared field matcher: by NAME when every field the row type declares is present among the columns, by
    // POSITION otherwise. Both modes are covered, because a matcher that answered positionally for everything
    // would still pass the first leaf while corrupting the second.

    "a row whose columns carry the row type's field names decodes into it" in {
        case class Member(id: Int, name: String) derives CanEqual
        val row = mysqlBinaryRow(
            Chunk(
                Maybe.Present(encodeRaw(7, MysqlEncoder.intEncoder)),
                Maybe.Present(Span.from("alice".getBytes(StandardCharsets.UTF_8)))
            ),
            Chunk(field("id"), field("name"))
        )
        kyo.Abort.run[SqlDecodeException](row.decode[Member]).eval match
            case kyo.Result.Success(m) => assert(m == Member(7, "alice"))
            case other                 => fail(s"Expected a decoded Member but got $other")
        end match
    }

    "a row whose columns arrive in another order still lands each value in its own field" in {
        case class Member(id: Int, name: String) derives CanEqual
        val row = mysqlBinaryRow(
            Chunk(
                Maybe.Present(Span.from("alice".getBytes(StandardCharsets.UTF_8))),
                Maybe.Present(encodeRaw(7, MysqlEncoder.intEncoder))
            ),
            Chunk(field("name"), field("id"))
        )
        kyo.Abort.run[SqlDecodeException](row.decode[Member]).eval match
            case kyo.Result.Success(m) => assert(m == Member(7, "alice"))
            case other                 => fail(s"Expected the by-name decode to survive the column order, got $other")
        end match
    }

    "a projection whose columns name none of the row type's fields decodes positionally" in {
        // `select(c => (c.p.name, c.p.age)).to[Summary]` yields columns named after the expressions, not after
        // the target's fields, and the target's own Mirror already proves the types line up in order.
        case class Summary(fullName: String, years: Int) derives CanEqual
        val row = mysqlBinaryRow(
            Chunk(
                Maybe.Present(Span.from("alice".getBytes(StandardCharsets.UTF_8))),
                Maybe.Present(encodeRaw(30, MysqlEncoder.intEncoder))
            ),
            Chunk(field("name"), field("age"))
        )
        kyo.Abort.run[SqlDecodeException](row.decode[Summary]).eval match
            case kyo.Result.Success(v) => assert(v == Summary("alice", 30))
            case other                 => fail(s"Expected the positional decode, got $other")
        end match
    }

    "reading a column that holds no value raises SqlDecodeColumnAbsentException naming the column index" in {
        val row = nullColumnRow()
        val r   = reader(row)
        val ex  = intercept[SqlDecodeColumnAbsentException] { r.int() }
        assert(ex.columnIndex == Maybe(0), s"expected columnIndex 0, got: ${ex.columnIndex}")
    }

    "duration() decodes ZERO from empty body (zero-length TIME struct)" in {
        // MySQL TIME length=0 → BinaryResultsetRowUnmarshaller strips the 0x00 length byte,
        // delivering an empty Span as the struct body.
        val bytes = Span.empty[Byte]
        val row   = singleColumnRow(bytes)
        val r     = reader(row)
        assert(r.duration().equals(java.time.Duration.ZERO), "zero-length TIME struct should decode to Duration.ZERO")
    }

    "duration() decodes Duration.ofHours(1) from 8-byte struct" in {
        // Build 8-byte TIME body: is_neg=0, days=0(LE4), hours=1, min=0, sec=0
        val body = Array[Byte](
            0x00,                   // is_negative = 0
            0x00, 0x00, 0x00, 0x00, // days = 0 (LE)
            0x01,                   // hours = 1
            0x00,                   // minutes = 0
            0x00                    // seconds = 0
        )
        val row = singleColumnRow(Span.from(body))
        val r   = reader(row)
        assert(r.duration().equals(java.time.Duration.ofHours(1)), "8-byte TIME struct should decode to 1 hour")
    }

    "decodeDate on malformed struct length throws SqlDecodeException" in {
        val malformed = Span.from(Array[Byte](0x01.toByte, 0x02.toByte, 0x03.toByte)) // 3 bytes, not 0 or 4
        val ex        = intercept[SqlDecodeTemporalException] { MysqlRowReader.decodeDate(malformed, Format.Binary) }
        assert(ex.structLength == 3, s"expected structLength 3, got: ${ex.structLength}")
    }

    "decodeDatetime on malformed struct length throws SqlDecodeException" in {
        val malformed = Span.from(Array[Byte](0x01.toByte, 0x02.toByte)) // 2 bytes, not 0, 4, 7, or 11
        val ex        = intercept[SqlDecodeTemporalException] { MysqlRowReader.decodeDatetime(malformed, Format.Binary) }
        assert(ex.structLength == 2, s"expected structLength 2, got: ${ex.structLength}")
    }

    // The zero date, and its two wire formats.
    //
    // MySQL permits `0000-00-00` and, unless NO_ZERO_IN_DATE is set, partial forms like `2024-00-15`. java.time
    // can hold neither. Both wire formats refuse every zero component through the single
    // MysqlTemporalDecoder.isZeroDate predicate, so the binary and text paths cannot diverge on it by construction
    // rather than by matching constants.

    "a zero-length DATETIME struct is refused rather than decoded as year 0 or year 1" in {
        val ex = intercept[SqlDecodeTemporalException] {
            MysqlRowReader.decodeDatetime(Span.empty[Byte], Format.Binary)
        }
        assert(ex.year == 0 && ex.month == 0 && ex.day == 0, s"the refusal must report the zero components, got $ex")
        assert(ex.structLength == 0, s"expected structLength 0, got ${ex.structLength}")
    }

    "a zero-length DATE struct is refused" in {
        val ex = intercept[SqlDecodeTemporalException] {
            MysqlRowReader.decodeDate(Span.empty[Byte], Format.Binary)
        }
        assert(ex.structLength == 0, s"expected structLength 0, got ${ex.structLength}")
    }

    "a partial zero DATE struct is refused rather than rewritten to a real date" in {
        // 2024-00-15: year present, month zero. Substituting 1 for the month would produce a real-looking 2024-01-15.
        val body = Array[Byte]((2024 & 0xff).toByte, ((2024 >> 8) & 0xff).toByte, 0x00.toByte, 15.toByte)
        val ex = intercept[SqlDecodeTemporalException] {
            MysqlRowReader.decodeDate(Span.from(body), Format.Binary)
        }
        assert(ex.year == 2024, s"expected the year reported, got ${ex.year}")
        assert(ex.month == 0, s"expected month 0 reported, got ${ex.month}")
        assert(ex.day == 15, s"expected day 15 reported, got ${ex.day}")
    }

    "a partial zero DATETIME struct is refused" in {
        val body = Array[Byte]((2024 & 0xff).toByte, ((2024 >> 8) & 0xff).toByte, 6.toByte, 0x00.toByte)
        val ex = intercept[SqlDecodeTemporalException] {
            MysqlRowReader.decodeDatetime(Span.from(body), Format.Binary)
        }
        assert(ex.day == 0, s"expected day 0 reported, got ${ex.day}")
    }

    "the text format refuses the same zero dates the binary format does" in {
        val zero = typedRow(asciiBytes("0000-00-00"), MysqlEncoder.TYPE_DATE, 0, Format.Text)
        intercept[SqlDecodeTemporalException](readerFor(zero, Format.Text).nextDate())
        val partial = typedRow(asciiBytes("2024-00-15"), MysqlEncoder.TYPE_DATE, 0, Format.Text)
        val ex      = intercept[SqlDecodeTemporalException](readerFor(partial, Format.Text).nextDate())
        assert(ex.month == 0, s"expected month 0 reported, got ${ex.month}")
    }

    "a real date still decodes in both formats" in {
        val body   = Array[Byte]((2024 & 0xff).toByte, ((2024 >> 8) & 0xff).toByte, 6.toByte, 15.toByte)
        val binary = MysqlRowReader.decodeDate(Span.from(body), Format.Binary)
        val text   = MysqlRowReader.decodeDate(asciiBytes("2024-06-15"), Format.Text)
        assert(binary.equals(java.time.LocalDate.of(2024, 6, 15)), s"binary DATE decoded as $binary")
        assert(text.equals(java.time.LocalDate.of(2024, 6, 15)), s"text DATE decoded as $text")
    }

    // A zero-length TIME struct is NOT the same shape of value: 00:00:00 is a real time of day and a real
    // zero span, so it decodes rather than being refused. The zero-length DATE arm above is a stand-in for
    // absence and this one is not, which is why the two lengths are treated differently.
    "a zero-length TIME struct still decodes as midnight and as a zero span" in {
        assert(MysqlRowReader.decodeLocalTime(Span.empty[Byte], Format.Binary).equals(java.time.LocalTime.MIDNIGHT))
        assert(MysqlRowReader.decodeDuration(Span.empty[Byte], Format.Binary).equals(java.time.Duration.ZERO))
    }

    // ── MySQL TIME is a signed span wider than a day, and both facts reach the decode ──
    //
    // The binary struct carries is_negative and days, and a LocalTime decoder that read past them would turn
    // -10:30:00 into 10:30:00 and 100:00:00 into 04:00:00, which the text sibling refuses.

    /** Builds an 8-byte binary TIME struct body: is_negative(1) | days(4 LE) | hours(1) | minutes(1) | seconds(1). */
    private def timeStruct(negative: Boolean, days: Int, hours: Int, minutes: Int, seconds: Int): Span[Byte] =
        Span.from(Array[Byte](
            if negative then 1.toByte else 0.toByte,
            (days & 0xff).toByte,
            ((days >> 8) & 0xff).toByte,
            ((days >> 16) & 0xff).toByte,
            ((days >> 24) & 0xff).toByte,
            hours.toByte,
            minutes.toByte,
            seconds.toByte
        ))

    "a negative binary TIME is refused as a time of day rather than losing its sign" in {
        val row = typedRow(timeStruct(negative = true, 0, 10, 30, 0), MysqlEncoder.TYPE_TIME, 0, Format.Binary)
        val ex  = intercept[SqlDecodeTemporalException](readerFor(row, Format.Binary).nextTime())
        assert(ex.minute == 30, s"the refusal must report the value's fields, got $ex")
    }

    "a binary TIME beyond a day is refused as a time of day rather than losing its days" in {
        // 100:00:00 travels as days=4, hours=4. Discarding the days would make it 04:00:00.
        val row = typedRow(timeStruct(negative = false, 4, 4, 0, 0), MysqlEncoder.TYPE_TIME, 0, Format.Binary)
        val ex  = intercept[SqlDecodeTemporalException](readerFor(row, Format.Binary).nextTime())
        assert(ex.hour == 100, s"the refusal must report the 100 hours the value names, got ${ex.hour}")
    }

    "a binary TIME inside a day still decodes as a time of day" in {
        val row = typedRow(timeStruct(negative = false, 0, 10, 30, 15), MysqlEncoder.TYPE_TIME, 0, Format.Binary)
        assert(readerFor(row, Format.Binary).nextTime().equals(java.time.LocalTime.of(10, 30, 15)))
    }

    "a negative binary TIME read as a Duration keeps its sign, which is the mapping that carries it" in {
        val row = typedRow(timeStruct(negative = true, 0, 10, 30, 0), MysqlEncoder.TYPE_TIME, 0, Format.Binary)
        assert(readerFor(row, Format.Binary).duration().equals(java.time.Duration.ofHours(-10).minusMinutes(30)))
    }

    "a binary TIME beyond a day read as a Duration keeps its days" in {
        val row = typedRow(timeStruct(negative = false, 4, 4, 0, 0), MysqlEncoder.TYPE_TIME, 0, Format.Binary)
        assert(readerFor(row, Format.Binary).duration().equals(java.time.Duration.ofHours(100)))
    }

    // ── A Char column holds exactly one character ─────────────────────────────────────

    "a multi-character column read as Char is refused rather than truncated" in {
        val row = singleColumnRow(textBytes("abc"))
        val ex  = intercept[SqlDecodeMultiCharacterForCharException](reader(row).char())
        assert(ex.characterCount == 3, s"expected 3 characters reported, got ${ex.characterCount}")
        assert(ex.columnIndex == Maybe(0), s"expected column 0 reported, got ${ex.columnIndex}")
    }

    "a single-character column still decodes as Char" in {
        assert(reader(singleColumnRow(textBytes("x"))).char() == 'x')
    }

    // ── Container-column leaves ───────────────────────────────────────────────

    private def textBytes(s: String): Span[Byte] =
        Span.from(s.getBytes(StandardCharsets.UTF_8))

    private def mysqlRow(name: String, bytes: Span[Byte]): SqlRow =
        mysqlBinaryRow(Chunk(Maybe.Present(bytes)), Chunk(field(name)))

    "container columns" - {

        // A container column is a whole-column read through the vocabulary: MySQL has no array column type, so a
        // Chunk travels as a JSON array in a text column, which `MysqlJsonArray` formats and parses. Every leaf
        // here also pins that the column is ONE column however many elements it holds, by reading a trailing
        // scalar after it.

        "nextArrayOfInt reads a JSON array column" in {
            val row = mysqlRow("arr", textBytes("[1,2,3]"))
            assert(reader(row).nextArrayOfInt() == Chunk(1, 2, 3))
        }

        "nextArrayOfString reads a JSON array column, resolving its escapes" in {
            val row = mysqlRow("arr", textBytes("""["a","b,c","d\"e"]"""))
            assert(reader(row).nextArrayOfString() == Chunk("a", "b,c", "d\"e"))
        }

        "nextArrayOfJson hands back each element's own document text, nested objects included" in {
            val row = mysqlRow("docs", textBytes("""[{"k":1},{"k":2}]"""))
            assert(reader(row).nextArrayOfJson() == Chunk("""{"k":1}""", """{"k":2}"""))
        }

        "nextJson reads a JSON object column as its document text" in {
            val row = mysqlRow("col", textBytes("""{"k":1}"""))
            assert(reader(row).nextJson() == """{"k":1}""")
        }

        "an array column hands the row cursor back to the column after it" in {
            val row = mysqlBinaryRow(
                Chunk(Maybe.Present(textBytes("[1,2,3]")), Maybe.Present(textBytes("tail"))),
                Chunk(field("arr"), field("trailing"))
            )
            val rowReader = reader(row)
            assert(rowReader.nextArrayOfInt() == Chunk(1, 2, 3))
            assert(rowReader.string() == "tail", "the row cursor must still be on the column after the array")
        }

        "a document column hands the row cursor back to the column after it" in {
            val row = mysqlBinaryRow(
                Chunk(Maybe.Present(textBytes("""{"xs":[1,2,3]}""")), Maybe.Present(textBytes("tail"))),
                Chunk(field("doc"), field("trailing"))
            )
            val rowReader = reader(row)
            assert(rowReader.nextJson() == """{"xs":[1,2,3]}""")
            assert(rowReader.string() == "tail", "the row cursor must still be on the column after the document")
        }

        // ── Each container column hands the row cursor back where it found it ─────
        //
        // A scalar on BOTH sides of the container column is what makes that visible: the leading one proves the
        // cursor arrived where the container read started, the trailing one proves it came back. One leaf per
        // container shape, since each reaches the wire through a different vocabulary method.

        "a JSON document field hands the row cursor back to the column after it" in {
            case class JsonSandwich(a: Int, m: JsonText, z: String) derives CanEqual

            val row = mysqlBinaryRow(
                Chunk[Maybe[Span[Byte]]](
                    Maybe.Present(encodeRaw(1, MysqlEncoder.intEncoder)),
                    Maybe.Present(textBytes("""{"k":2}""")),
                    Maybe.Present(textBytes("tail"))
                ),
                Chunk(field("a"), field("m"), field("z"))
            )
            kyo.Abort.run[SqlDecodeException](row.decode[JsonSandwich]).eval match
                case kyo.Result.Success(v) =>
                    assert(v.a == 1, s"the column before the document must decode, got ${v.a}")
                    assert(v.m.text == """{"k":2}""", s"the document must decode, got ${v.m.text}")
                    assert(v.z == "tail", s"the column after the document must decode, got '${v.z}'")
                case other => fail(s"Expected a decoded JsonSandwich but got $other")
            end match
        }

        "a JSON array field hands the row cursor back to the column after it" in {
            case class ArraySandwich(a: Int, xs: Chunk[Int], z: String) derives CanEqual

            val row = mysqlBinaryRow(
                Chunk[Maybe[Span[Byte]]](
                    Maybe.Present(encodeRaw(1, MysqlEncoder.intEncoder)),
                    Maybe.Present(textBytes("[2,3]")),
                    Maybe.Present(textBytes("tail"))
                ),
                Chunk(field("a"), field("xs"), field("z"))
            )
            kyo.Abort.run[SqlDecodeException](row.decode[ArraySandwich]).eval match
                case kyo.Result.Success(v) =>
                    assert(v.a == 1, s"the column before the array must decode, got ${v.a}")
                    assert(v.xs == Chunk(2, 3), s"the array must decode, got ${v.xs}")
                    assert(v.z == "tail", s"the column after the array must decode, got '${v.z}'")
                case other => fail(s"Expected a decoded ArraySandwich but got $other")
            end match
        }

    }

    // Decode failures through the row codec.

    "a payload too short for the type surfaces as Abort[SqlDecodeException]" in {
        // 3 bytes where LONGLONG needs 8.
        val row = singleColumnRow(Span.from(Array[Byte](0x01, 0x02, 0x03)))
        kyo.Abort.run[SqlDecodeException](row.decode[Long]).eval match
            case kyo.Result.Failure(_: SqlDecodeException) => succeed
            case other                                     => fail(s"Expected Failure(SqlDecodeException) but got $other")
        end match
    }

    "a row round-trips a Long written by the MySQL param writer" in {
        val params = MysqlParamWriter.write(kyo.SqlSchema.long, 42L)
        assert(params.size == 1)
        val bytes = params(0).encoded match
            case kyo.Maybe.Present(b) => b
            case kyo.Maybe.Absent     => fail("expected encoded LONGLONG bytes")
        kyo.Abort.run(singleColumnRow(bytes).decode[Long]).eval match
            case kyo.Result.Success(v) => assert(v == 42L)
            case other                 => fail(s"Expected Success(42L) but got $other")
    }

    // == Width, signedness, and format all come from the column, not from the Scala type ==========
    //
    // MySQL's binary protocol writes a fixed width per COLUMN type, so the decode reads width, signedness, and format
    // from the column, never from the Scala target type: an `Int` field over a `BIGINT` still reads all eight bytes,
    // and the UNSIGNED column flag is honored so an `INT UNSIGNED` near its top range does not read as negative. The
    // text protocol shares no representation with any of it.

    /** Builds a single-column row carrying the server's type byte, flags, and collation, the way `MysqlRowCodec.row` does.
      *
      * `charset` defaults to a non-binary collation, since every leaf here reads a value at its declared type rather than probing the
      * BLOB-against-TEXT split; the leaves that do probe it pass 63 explicitly.
      */
    private def typedRow(bytes: Span[Byte], columnType: Int, flags: Int, format: Format, charset: Int = Utf8Collation): SqlRow =
        new SqlRow(
            Chunk(Maybe.Present(bytes)),
            Chunk(SqlRow.Column("column", MysqlColumnToken(columnType, flags, charset))),
            MysqlRowCodec(format)
        )

    /** `utf8mb4_general_ci`, standing in for any collation that is not `binary`. */
    private val Utf8Collation = 45

    /** MySQL's `binary` collation id, which is what separates a BLOB from a TEXT. */
    private val BinaryCollation = 63

    private def readerFor(row: SqlRow, format: Format): MysqlRowReader =
        new MysqlRowReader(row, format)

    private def asciiBytes(s: String): Span[Byte] =
        Span.from(s.getBytes(StandardCharsets.UTF_8))

    private val UnsignedFlag = 0x20

    "Int over a BIGINT column reads the value when it fits" in {
        val row = typedRow(encodeRaw(7L, MysqlEncoder.longEncoder), MysqlEncoder.TYPE_LONGLONG, 0, Format.Binary)
        assert(readerFor(row, Format.Binary).int() == 7, "a LONGLONG column holding 7 must decode as 7")
    }

    "Int over a BIGINT column beyond Int's range aborts typed instead of wrapping to the low word" in {
        val row = typedRow(encodeRaw(2147483648L, MysqlEncoder.longEncoder), MysqlEncoder.TYPE_LONGLONG, 0, Format.Binary)
        val ex  = intercept[SqlDecodeValueRangeException](readerFor(row, Format.Binary).int())
        assert(ex.scalaType == "Int", s"expected the Int target named, got ${ex.scalaType}")
        assert(ex.wireValue == "2147483648", s"expected the wire value reported, got ${ex.wireValue}")
    }

    "Long over an INT column widens rather than failing the eight-byte size check" in {
        val row = typedRow(encodeRaw(42, MysqlEncoder.intEncoder), MysqlEncoder.TYPE_LONG, 0, Format.Binary)
        assert(readerFor(row, Format.Binary).long() == 42L, "a four-byte LONG column must widen into Long")
    }

    "Long over an INT UNSIGNED column near its top reads the magnitude, not a negative number" in {
        // 3000000000 does not fit a signed Int; the four wire bytes are the same either way and only the flag says which.
        val bytes = encodeRaw(-1294967296, MysqlEncoder.intEncoder)
        val row   = typedRow(bytes, MysqlEncoder.TYPE_LONG, UnsignedFlag, Format.Binary)
        assert(readerFor(row, Format.Binary).long() == 3000000000L, "the UNSIGNED flag must make the top bit magnitude")
    }

    "Int over an INT UNSIGNED column above Int's range aborts typed rather than reading negative" in {
        val bytes = encodeRaw(-1294967296, MysqlEncoder.intEncoder)
        val row   = typedRow(bytes, MysqlEncoder.TYPE_LONG, UnsignedFlag, Format.Binary)
        val ex    = intercept[SqlDecodeValueRangeException](readerFor(row, Format.Binary).int())
        assert(ex.wireValue == "3000000000", s"expected the unsigned magnitude reported, got ${ex.wireValue}")
    }

    "BigDecimal over a BIGINT UNSIGNED column above 2^63 carries the magnitude" in {
        // 18446744073709551615 = 2^64 - 1, whose signed reading is -1.
        val row = typedRow(encodeRaw(-1L, MysqlEncoder.longEncoder), MysqlEncoder.TYPE_LONGLONG, UnsignedFlag, Format.Binary)
        assert(
            readerFor(row, Format.Binary).bigDecimal() == BigDecimal("18446744073709551615"),
            "BigDecimal has room for the full unsigned range, so it must carry it rather than reading -1"
        )
    }

    "Long over a BIGINT UNSIGNED column above 2^63 aborts typed rather than wrapping negative" in {
        val row = typedRow(encodeRaw(-1L, MysqlEncoder.longEncoder), MysqlEncoder.TYPE_LONGLONG, UnsignedFlag, Format.Binary)
        val ex  = intercept[SqlDecodeValueRangeException](readerFor(row, Format.Binary).long())
        assert(ex.wireValue == "18446744073709551615", s"expected the unsigned magnitude reported, got ${ex.wireValue}")
    }

    "Int over a FLOAT column reads the float, not its bit pattern as an integer" in {
        val row = typedRow(encodeRaw(3.0f, MysqlEncoder.floatEncoder), MysqlEncoder.TYPE_FLOAT, 0, Format.Binary)
        assert(readerFor(row, Format.Binary).int() == 3, "a FLOAT column holding 3.0 must decode as 3")
    }

    "Int over a FLOAT column carrying a fraction aborts typed rather than truncating" in {
        val row = typedRow(encodeRaw(3.5f, MysqlEncoder.floatEncoder), MysqlEncoder.TYPE_FLOAT, 0, Format.Binary)
        val ex  = intercept[SqlDecodeValueRangeException](readerFor(row, Format.Binary).int())
        assert(ex.scalaType == "Int", s"expected the Int target named, got ${ex.scalaType}")
    }

    "Double over a DOUBLE column still reads the double when the column type is known" in {
        val row = typedRow(encodeRaw(1.5d, MysqlEncoder.doubleEncoder), MysqlEncoder.TYPE_DOUBLE, 0, Format.Binary)
        assert(readerFor(row, Format.Binary).double() == 1.5d)
    }

    "Long over a DOUBLE column reads the double, not its bit pattern" in {
        val row = typedRow(encodeRaw(4.0d, MysqlEncoder.doubleEncoder), MysqlEncoder.TYPE_DOUBLE, 0, Format.Binary)
        assert(readerFor(row, Format.Binary).long() == 4L, "a DOUBLE column holding 4.0 must decode as 4L")
    }

    // == The text protocol: every value is its ASCII rendering ===================================

    "a text-format integer of four or more digits decodes as its value, not as little-endian bytes" in {
        // 0x31 0x32 0x33 0x34 read as a little-endian LONG is 875770417.
        val row = typedRow(asciiBytes("1234"), MysqlEncoder.TYPE_LONG, 0, Format.Text)
        assert(readerFor(row, Format.Text).int() == 1234, "the ASCII digits of 1234 must decode as 1234")
    }

    "a text-format integer of one to three digits decodes rather than failing a binary size check" in {
        val row = typedRow(asciiBytes("42"), MysqlEncoder.TYPE_LONG, 0, Format.Text)
        assert(readerFor(row, Format.Text).int() == 42, "two ASCII digits are a value, not a truncated LONG")
    }

    "a text-format false boolean decodes as false" in {
        // The ASCII byte of "0" is 0x30, which is nonzero, so reading the byte rather than the rendering inverts every false.
        val row = typedRow(asciiBytes("0"), MysqlEncoder.TYPE_TINY, 0, Format.Text)
        assert(!readerFor(row, Format.Text).boolean(), "text 0 must decode as false")
    }

    "a text-format true boolean decodes as true" in {
        val row = typedRow(asciiBytes("1"), MysqlEncoder.TYPE_TINY, 0, Format.Text)
        assert(readerFor(row, Format.Text).boolean(), "text 1 must decode as true")
    }

    "a text-format Long decodes its full range" in {
        val row = typedRow(asciiBytes("9007199254740993"), MysqlEncoder.TYPE_LONGLONG, 0, Format.Text)
        assert(readerFor(row, Format.Text).long() == 9007199254740993L)
    }

    "a text-format value too large for the requested Int aborts typed" in {
        val row = typedRow(asciiBytes("5000000000"), MysqlEncoder.TYPE_LONGLONG, 0, Format.Text)
        val ex  = intercept[SqlDecodeValueRangeException](readerFor(row, Format.Text).int())
        assert(ex.wireValue == "5000000000", s"expected the wire value reported, got ${ex.wireValue}")
    }

    "a text-format DOUBLE decodes as its rendering" in {
        val row = typedRow(asciiBytes("1.5"), MysqlEncoder.TYPE_DOUBLE, 0, Format.Text)
        assert(readerFor(row, Format.Text).double() == 1.5d)
    }

    "a text-format DECIMAL decodes as its rendering" in {
        val row = typedRow(asciiBytes("12.34"), MysqlEncoder.TYPE_NEWDECIMAL, 0, Format.Text)
        assert(readerFor(row, Format.Text).bigDecimal() == BigDecimal("12.34"))
    }

    "a text-format DATETIME decodes rather than reaching the binary struct decoder's reject arm" in {
        val row      = typedRow(asciiBytes("2024-01-15 10:30:00"), MysqlEncoder.TYPE_DATETIME, 0, Format.Text)
        val expected = java.time.LocalDateTime.of(2024, 1, 15, 10, 30, 0).toInstant(java.time.ZoneOffset.UTC)
        assert(readerFor(row, Format.Text).instant().equals(expected))
    }

    "a text-format DATETIME carrying microseconds keeps them" in {
        val row      = typedRow(asciiBytes("2024-01-15 10:30:00.123456"), MysqlEncoder.TYPE_DATETIME, 0, Format.Text)
        val expected = java.time.LocalDateTime.of(2024, 1, 15, 10, 30, 0, 123456000).toInstant(java.time.ZoneOffset.UTC)
        assert(readerFor(row, Format.Text).instant().equals(expected))
    }

    "a text-format DATE decodes with no time part" in {
        val row = typedRow(asciiBytes("2024-01-15"), MysqlEncoder.TYPE_DATE, 0, Format.Text)
        assert(readerFor(row, Format.Text).nextDate().equals(java.time.LocalDate.of(2024, 1, 15)))
    }

    "a text-format TIME decodes as a Duration carrying its sign and its out-of-day hours" in {
        val row = typedRow(asciiBytes("-25:30:00"), MysqlEncoder.TYPE_TIME, 0, Format.Text)
        assert(
            readerFor(row, Format.Text).duration().equals(java.time.Duration.ofHours(-25).minusMinutes(30)),
            "MySQL TIME spans -838:59:59 to 838:59:59, and Duration is the mapping that carries it"
        )
    }

    "a text-format TIME beyond a day aborts typed when read as a time of day" in {
        val row = typedRow(asciiBytes("25:00:00"), MysqlEncoder.TYPE_TIME, 0, Format.Text)
        intercept[SqlDecodeTemporalException](readerFor(row, Format.Text).nextTime())
        succeed
    }

    "a text-format TIME inside a day decodes as a time of day" in {
        val row = typedRow(asciiBytes("10:30:15"), MysqlEncoder.TYPE_TIME, 0, Format.Text)
        assert(readerFor(row, Format.Text).nextTime().equals(java.time.LocalTime.of(10, 30, 15)))
    }

    "the mismatch reads reach the caller as Abort, not as a throw escaping the codec" in {
        val row = typedRow(encodeRaw(2147483648L, MysqlEncoder.longEncoder), MysqlEncoder.TYPE_LONGLONG, 0, Format.Binary)
        kyo.Abort.run[SqlDecodeException](row.decode[Int]).eval match
            case kyo.Result.Failure(e: SqlDecodeValueRangeException) => assert(e.scalaType == "Int")
            case other => fail(s"Expected Failure(SqlDecodeValueRangeException) but got $other")
    }

    // == BIT columns escape the format branch: raw big-endian bytes under both protocols =========

    "a text-format BIT column reads its byte, not an ASCII rendering of it" in {
        // A text-protocol BIT value is the raw byte, so parsing it as digits fails on the zero byte, and reading a
        // TINYINT's ASCII 0 as a byte reports true. The two need opposite treatment and only the type byte separates them.
        val falsy  = typedRow(Span[Byte](0x00.toByte), MysqlEncoder.TYPE_BIT, 0, Format.Text)
        val truthy = typedRow(Span[Byte](0x01.toByte), MysqlEncoder.TYPE_BIT, 0, Format.Text)
        assert(!readerFor(falsy, Format.Text).boolean(), "a BIT column's zero byte is false")
        assert(readerFor(truthy, Format.Text).boolean(), "a BIT column's one byte is true")
    }

    "a multi-byte BIT column reads big-endian, not little-endian" in {
        val row = typedRow(Span[Byte](0x01.toByte, 0x00.toByte), MysqlEncoder.TYPE_BIT, 0, Format.Binary)
        assert(readerFor(row, Format.Binary).int() == 256, "BIT(9) holding 256 is 0x01 0x00 big-endian, which is 1 read the other way")
    }

    // == Boolean is MySQL's own truthiness over the column's VALUE, so it needs the wire-kind dispatch ============
    //
    // Every numeric target resolves the column's wire kind (integer / FLOAT / DOUBLE / DECIMAL) before reading, because widths collide
    // and a DECIMAL arrives as ASCII even under the binary protocol. A `boolean` that went straight to the little-endian integer reader
    // would reinterpret a column of any other kind rather than read it.

    "a binary DECIMAL column decodes as a boolean by its value, not by its ASCII bytes read as an integer" in {
        // A binary-protocol DECIMAL is its ASCII rendering. The four bytes of "0.00" read as a little-endian LONG are 0x30302E30,
        // which is nonzero, so an integer read of those bytes reports a zero as TRUE.
        val zero    = typedRow(asciiBytes("0.00"), MysqlEncoder.TYPE_NEWDECIMAL, 0, Format.Binary)
        val nonZero = typedRow(asciiBytes("1.50"), MysqlEncoder.TYPE_NEWDECIMAL, 0, Format.Binary)
        assert(!readerFor(zero, Format.Binary).boolean(), "DECIMAL 0.00 is false")
        assert(readerFor(nonZero, Format.Binary).boolean(), "DECIMAL 1.50 is true, and it is not a whole number")
    }

    "a binary DOUBLE column decodes as a boolean by its value, not by its bit pattern" in {
        val zero    = typedRow(encodeRaw(0.0d, MysqlEncoder.doubleEncoder), MysqlEncoder.TYPE_DOUBLE, 0, Format.Binary)
        val negZero = typedRow(encodeRaw(-0.0d, MysqlEncoder.doubleEncoder), MysqlEncoder.TYPE_DOUBLE, 0, Format.Binary)
        val small   = typedRow(encodeRaw(0.5d, MysqlEncoder.doubleEncoder), MysqlEncoder.TYPE_DOUBLE, 0, Format.Binary)
        assert(!readerFor(zero, Format.Binary).boolean(), "DOUBLE 0.0 is false")
        assert(!readerFor(negZero, Format.Binary).boolean(), "DOUBLE -0.0 is zero, though its sign bit is not")
        assert(readerFor(small, Format.Binary).boolean(), "DOUBLE 0.5 is nonzero, so true, though it truncates to zero")
    }

    "a binary FLOAT column decodes as a boolean by its value" in {
        // 0.0f is the one float whose bit pattern is also zero, so a leaf built only on it passes against the integer reader too. -0.0f is
        // the case that separates them: its bit pattern is 0x80000000 and its value is zero.
        val zero    = typedRow(encodeRaw(0.0f, MysqlEncoder.floatEncoder), MysqlEncoder.TYPE_FLOAT, 0, Format.Binary)
        val negZero = typedRow(encodeRaw(-0.0f, MysqlEncoder.floatEncoder), MysqlEncoder.TYPE_FLOAT, 0, Format.Binary)
        val small   = typedRow(encodeRaw(0.25f, MysqlEncoder.floatEncoder), MysqlEncoder.TYPE_FLOAT, 0, Format.Binary)
        assert(!readerFor(zero, Format.Binary).boolean(), "FLOAT 0.0 is false")
        assert(!readerFor(negZero, Format.Binary).boolean(), "FLOAT -0.0 is zero, though its sign bit is not")
        assert(readerFor(small, Format.Binary).boolean(), "FLOAT 0.25 is nonzero, so true")
    }

    "a BIGINT UNSIGNED above 2^63 is true rather than a range failure" in {
        // Read as a signed Long the value is negative, which the exact-integer path reports as out of range. Truthiness has room for
        // every value in the column, so a boolean read has nothing to report.
        val row = typedRow(encodeRaw(-1L, MysqlEncoder.longEncoder), MysqlEncoder.TYPE_LONGLONG, UnsignedFlag, Format.Binary)
        assert(readerFor(row, Format.Binary).boolean(), "2^64-1 is nonzero, so true")
    }

    // == Byte, whose column is always wider than the field on both flavors =======================================
    //
    // `.cast[Byte]` targets `SqlType.Type.SmallInt`, which MySQL spells `SIGNED`, so a `Byte` is read back from a column many times its own
    // width. The narrowing is checked rather than truncated, and these are the leaves that say so; the PostgreSQL twin is
    // `PostgresRowReaderTest`'s "Byte over an int2 column carrying a value beyond Byte aborts typed instead of wrapping".

    "Byte over a wider integer column carrying a value beyond Byte aborts typed instead of wrapping" in {
        val row = typedRow(encodeRaw(300L, MysqlEncoder.longEncoder), MysqlEncoder.TYPE_LONGLONG, 0, Format.Binary)
        val ex  = intercept[SqlDecodeValueRangeException](readerFor(row, Format.Binary).byte())
        assert(ex.scalaType == "Byte", s"expected the Byte target named, got ${ex.scalaType}")
        assert(ex.wireValue == "300", s"expected the wire value reported, got ${ex.wireValue}")
    }

    "Byte over a wider integer column inside Byte's range still decodes" in {
        val row = typedRow(encodeRaw(-5L, MysqlEncoder.longEncoder), MysqlEncoder.TYPE_LONGLONG, 0, Format.Binary)
        assert(readerFor(row, Format.Binary).byte() == (-5).toByte, "an in-range value must still decode")
    }

    "a wide SIGNED integer column decodes true above the low byte" in {
        // `.cast[Boolean]` renders `CAST(x AS SIGNED)` on MySQL, which yields a LONGLONG column, so the value arrives eight bytes wide
        // and every byte of it has to be read.
        val row = typedRow(encodeRaw(256L, MysqlEncoder.longEncoder), MysqlEncoder.TYPE_LONGLONG, 0, Format.Binary)
        assert(readerFor(row, Format.Binary).boolean(), "256 is nonzero, though its low byte is not")
    }

    // == java.time.Period, whose decode has to agree with PostgreSQL's ==========================================
    //
    // `Period.equals` compares years, months and days field by field, so a decode that reports a different split
    // of the same total reports a different value. PostgreSQL's wire form carries one month count and re-expands
    // it through `Period.of(0, months, days).normalized()`, so `Period.ofMonths(14)` comes back as `P1Y2M` there.
    // Parsing MySQL's text back verbatim would return `P14M`, making one written value read back as two different
    // values depending on which backend answered. An already-normalised input cannot see this, so these use one
    // that is not.

    "a non-normalised Period reads back in the same normalised form PostgreSQL returns" in {
        val stored = java.time.Period.ofMonths(14).normalized().toString
        assert(stored == "P1Y2M", s"the writer stores the canonical split, got $stored")
        val row = singleColumnRow(asciiBytes(stored))
        assert(
            reader(row).nextCalendarInterval() == java.time.Period.of(1, 2, 0),
            "14 months must read back as P1Y2M, the value PostgreSQL's own decode returns"
        )
    }

    "the two spellings of one Period read back equal" in {
        // Two spellings of one Period normalize to the same text before reaching the column, so both must read back equal.
        val fromMonths = java.time.Period.ofMonths(14).normalized().toString
        val fromYears  = java.time.Period.of(1, 2, 0).normalized().toString
        assert(fromMonths == fromYears, s"both must reach the column as the same text, got $fromMonths and $fromYears")
        val a = reader(singleColumnRow(asciiBytes(fromMonths))).nextCalendarInterval()
        val b = reader(singleColumnRow(asciiBytes(fromYears))).nextCalendarInterval()
        assert(a == b, s"both must read back as one value, got $a and $b")
    }

    "days survive normalisation untouched" in {
        // `normalized` carries months into years and leaves days alone, so a 45-day period is not turned into
        // months. The normalisation is that carry alone, not a collapse of every component.
        val stored = java.time.Period.ofDays(45).normalized().toString
        val row    = singleColumnRow(asciiBytes(stored))
        assert(reader(row).nextCalendarInterval() == java.time.Period.ofDays(45), s"expected P45D from $stored")
    }

    "a Period whose year-carry overflows Int raises SqlDecodeIntervalException, not an unchecked ArithmeticException" in {
        // "P2147483647Y12M" parses to a Period whose years field is already Int.MaxValue; normalized() then
        // carries the 12 months into what would be year 2147483648, one past what Math.toIntExact can narrow
        // into an Int, and the JDK throws an unchecked ArithmeticException there. MysqlRowReader.nextCalendarInterval()
        // must report the typed SqlDecodeIntervalException, the same leaf PostgresDecoder.intervalPeriod raises
        // for a Period it cannot parse, not let the JDK's ArithmeticException escape untyped.
        val stored = java.time.Period.of(Int.MaxValue, 12, 0).toString
        assert(stored == "P2147483647Y12M", s"expected the raw unnormalised rendering, got $stored")
        val row = singleColumnRow(asciiBytes(stored))
        val ex  = intercept[SqlDecodeIntervalException](reader(row).nextCalendarInterval())
        assert(ex.field == "text", s"expected field 'text', got ${ex.field}")
        assert(ex.value == stored, s"expected the raw wire text, got ${ex.value}")
    }

    // ---- The BLOB against TEXT split, which only the collation carries --------------------------------
    //
    // MySQL gives a BLOB and a TEXT the same type byte, a VARBINARY and a VARCHAR the same type byte, and a
    // BINARY and a CHAR the same type byte. Only the column's collation separates each pair, 63 meaning
    // binary. Without it a `String` field over a BLOB read the raw bytes as UTF-8 and answered replacement
    // characters, which is the same defect a text read of an int4 is on PostgreSQL, where `bytea` is refused.

    "a String read of a binary-collated BLOB is refused, naming BLOB rather than TEXT" in {
        val bytes = Span.from(Array[Byte](0x00, 0xff.toByte, 0x00, 0xff.toByte))
        val row   = typedRow(bytes, MysqlEncoder.TYPE_BLOB, 0, Format.Binary, BinaryCollation)
        val ex    = intercept[SqlDecodeColumnTypeMismatchException](reader(row).string())
        assert(ex.columnType == "BLOB", s"expected the failure to name BLOB, got ${ex.columnType}")
        assert(ex.scalaType == "String", s"expected the failure to name String, got ${ex.scalaType}")
    }

    "a String read of a binary-collated VARBINARY and BINARY is refused under each spelling" in {
        val varbinary = typedRow(Span.from(Array[Byte](1, 2)), MysqlEncoder.TYPE_VAR_STRING, 0, Format.Binary, BinaryCollation)
        val binary    = typedRow(Span.from(Array[Byte](1, 2)), MysqlEncoder.TYPE_STRING, 0, Format.Binary, BinaryCollation)
        val varEx     = intercept[SqlDecodeColumnTypeMismatchException](reader(varbinary).string())
        val binEx     = intercept[SqlDecodeColumnTypeMismatchException](reader(binary).string())
        assert(varEx.columnType == "VARBINARY", s"expected VARBINARY, got ${varEx.columnType}")
        assert(binEx.columnType == "BINARY", s"expected BINARY, got ${binEx.columnType}")
    }

    "a String read of the same type bytes under a text collation still decodes" in {
        // The other half of the split: the type byte alone must not refuse, or every TEXT and VARCHAR breaks.
        val text    = typedRow(asciiBytes("hello"), MysqlEncoder.TYPE_BLOB, 0, Format.Binary)
        val varchar = typedRow(asciiBytes("hello"), MysqlEncoder.TYPE_VAR_STRING, 0, Format.Binary)
        val char    = typedRow(asciiBytes("hello"), MysqlEncoder.TYPE_STRING, 0, Format.Binary)
        assert(reader(text).string() == "hello", "a TEXT column must still read as String")
        assert(reader(varchar).string() == "hello", "a VARCHAR column must still read as String")
        assert(reader(char).string() == "hello", "a CHAR column must still read as String")
    }

    "a JSON column reads as String even though the server reports it binary-collated" in {
        // The carve-out that makes the collation rule safe: MySQL reports JSON as collation 63, but a JSON
        // column's bytes ARE the document text. JSON has its own type byte outside the string family, which
        // is what keeps it out of the binary split rather than any special case on the collation.
        val doc = "{\"a\":1}"
        val row = typedRow(asciiBytes(doc), MysqlEncoder.TYPE_JSON, 0, Format.Binary, BinaryCollation)
        assert(reader(row).string() == doc, "a JSON column must read as String despite collation 63")
    }

end MysqlRowReaderTest
