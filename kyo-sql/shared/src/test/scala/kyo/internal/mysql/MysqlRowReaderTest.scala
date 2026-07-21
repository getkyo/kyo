package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Frame
import kyo.Instant
import kyo.Maybe
import kyo.Span
import kyo.SqlException
import kyo.SqlRow
import kyo.Test
import kyo.internal.mysql.types.MysqlEncoder
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.types.Format

/** Verifies that [[MysqlRowReader]] decodes raw binary wire bytes from a [[kyo.SqlRow]] into the correct Scala values.
  *
  * Each test constructs a [[SqlRow]] whose column bytes mirror what [[BinaryResultsetRowUnmarshaller]] delivers — fixed-width LE integers
  * for numeric types, raw UTF-8 bytes for strings, and datetime struct bodies (length prefix already stripped) for temporal types. The
  * reader is then asserted to return the original Scala value, confirming byte-for-byte round-trip parity with the [[MysqlEncoder]] layer.
  *
  * All tests are pure unit tests on wire bytes; no MySQL container or network I/O is required.
  */
class MysqlRowReaderTest extends Test:

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Encodes `value` using `enc` and returns the raw wire bytes (without lenenc prefix, as delivered by the binary protocol). */
    private def encodeRaw[A](value: A, enc: MysqlEncoder[A]): Span[Byte] =
        val buf = new MysqlBufferWriter
        enc.write(value, buf)
        buf.toSpan
    end encodeRaw

    private def field(name: String): FieldDescription =
        FieldDescription(name, 0, 0, 0, 0, 0, 0)
    end field

    /** Builds a [[SqlRow]] with a single column whose stored bytes are `bytes`.
      *
      * Mimics the output of [[BinaryResultsetRowUnmarshaller]]: column bytes are the struct body without any length prefix.
      */
    private def singleColumnRow(bytes: Span[Byte]): SqlRow =
        new SqlRow(
            Chunk(Maybe.Present(bytes)),
            Chunk(field("column")),
            Format.Binary
        )
    end singleColumnRow

    /** Builds a [[SqlRow]] with a single NULL column (Maybe.Absent). */
    private def nullColumnRow(): SqlRow =
        new SqlRow(
            Chunk(Maybe.empty[Span[Byte]]),
            Chunk(field("column")),
            Format.Binary
        )
    end nullColumnRow

    /** Returns a [[MysqlRowReader]] wrapping the given [[SqlRow]]. */
    private def reader(row: SqlRow): MysqlRowReader =
        new MysqlRowReader(row)
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
    // MysqlDecoder has no shortDecoder; MysqlRowReader inlines readUInt16LE().toShort.

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
            // Use v.toString (Scala BigDecimal), NOT v.underlying().toPlainString — see
            // MysqlEncoder.bigDecimalEncoder / PostgresEncoder.numericText for the same workaround.
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
    // MysqlDecoder.decodeDatetimeBytes) and converts to java.time.Instant via UTC zone.

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

    "isNil returns true for NULL column and does not advance the cursor" in {
        val row = nullColumnRow()
        val r   = reader(row)
        assert(r.isNil() == true)
        // isNil must not advance: calling it twice returns true both times.
        assert(r.isNil() == true)
        // Reading a NULL column must throw.
        intercept[SqlException.Decode] {
            r.int()
        }
        succeed
    }

    "isNil returns false for non-NULL column and does not advance the cursor" in {
        val row = singleColumnRow(encodeRaw(99, MysqlEncoder.intEncoder))
        val r   = reader(row)
        assert(r.isNil() == false)
        // Cursor was not advanced — value is still readable.
        assert(r.int() == 99)
    }

    "custom returns raw column bytes" in {
        val payload = Span.from(Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte))
        val row     = singleColumnRow(payload)
        val r       = reader(row)
        val result  = r.custom("geometry")
        assert(result.toArray.sameElements(payload.toArray), "custom should return raw column bytes unchanged")
    }

    "multiple columns read positionally" in {
        val intBytes  = encodeRaw(7, MysqlEncoder.intEncoder)
        val strBytes  = Span.from("alice".getBytes(StandardCharsets.UTF_8))
        val boolBytes = encodeRaw(true, MysqlEncoder.boolEncoder)

        val multiRow = new SqlRow(
            Chunk[Maybe[Span[Byte]]](
                Maybe.Present(intBytes),
                Maybe.Present(strBytes),
                Maybe.Present(boolBytes)
            ),
            Chunk(field("id"), field("name"), field("active")),
            Format.Binary
        )
        val r = reader(multiRow)

        assert(r.int() == 7, "first column: int")
        assert(r.string() == "alice", "second column: string")
        assert(r.boolean() == true, "third column: boolean")
    }

    "frame is preserved through to subclass" in {
        def makeReader()(using f: Frame): MysqlRowReader =
            val row = singleColumnRow(encodeRaw(0, MysqlEncoder.intEncoder))
            new MysqlRowReader(row)
        end makeReader
        val r = makeReader()
        // frame is not null — that's the minimal contract (Frame equality is not specified).
        assert(r.frame != null)
        succeed
    }

    // ── Nested array/map methods throw UnsupportedOperationException ──────────
    // SQL rows are flat: nested arrays/maps/captures are not meaningful. The
    // case-class field-iteration protocol (objectStart/hasNextField/matchField/…)
    // IS supported — see the next test.

    "arrayStart on a non-JSON column throws SqlException.Decode (not Unsupported)" in {
        val row = singleColumnRow(encodeRaw(0, MysqlEncoder.intEncoder))
        val r   = reader(row)
        intercept[SqlException.Decode] { r.arrayStart() }
        succeed
    }

    // ── Case-class field-iteration protocol ───────────────────────────────────
    // objectStart/objectEnd/hasNextField/fieldParse/matchField/lastFieldName drive
    // Schema-derived case-class/tuple decoding. SQL result sets are positional, so
    // matchField accepts the next field whenever the cursor is in bounds (the probed
    // name is ignored); primitive reads advance the cursor. This lets a multi-column
    // SELECT decode into a case class on MySQL.

    "case-class field-iteration protocol walks columns positionally" in {
        val row = new SqlRow(
            Chunk(
                Maybe.Present(encodeRaw(7, MysqlEncoder.intEncoder)),
                Maybe.Present(Span.from("alice".getBytes(StandardCharsets.UTF_8)))
            ),
            Chunk(field("id"), field("name")),
            Format.Binary
        )
        val r = reader(row)
        assert(r.objectStart() == 0, "objectStart returns 0")
        assert(r.hasNextField(), "first field present")
        assert(r.lastFieldName() == "id", "first column name")
        r.fieldParse()
        // matchField compares the probe against the current column's name (or `_<idx+1>` for tuple projections),
        // so a wrong probe must not blanket-accept the position: only "id" or "_1" match column 0.
        assert(!r.matchField("wrong".getBytes(StandardCharsets.US_ASCII)), "matchField rejects a name that does not match column 0")
        assert(r.matchField("id".getBytes(StandardCharsets.US_ASCII)), "matchField accepts the current column's name at position 0")
        assert(r.int() == 7, "first column value decodes after match")
        assert(r.hasNextField(), "second field present")
        assert(r.lastFieldName() == "name", "second column name")
        r.fieldParse()
        assert(r.matchField("_2".getBytes(StandardCharsets.US_ASCII)), "matchField accepts the `_2` tuple-alias at position 1")
        assert(r.string() == "alice", "second column value decodes after match")
        assert(!r.hasNextField(), "no fields remain")
        // matchField is false once the cursor runs past the last column.
        assert(!r.matchField("x".getBytes(StandardCharsets.US_ASCII)), "matchField false past end")
        r.objectEnd()
        succeed
    }

    "reading a NULL column throws SqlException.Decode with message 'column 0 is NULL'" in {
        val row = nullColumnRow()
        val r   = reader(row)
        val ex  = intercept[SqlException.Decode] { r.int() }
        assert(ex.getMessage.contains("column 0 is NULL"), s"message was: ${ex.getMessage}")
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

    "decodeDateBytes on malformed struct length throws SqlException.Decode" in {
        val malformed = Span.from(Array[Byte](0x01.toByte, 0x02.toByte, 0x03.toByte)) // 3 bytes, not 0 or 4
        val ex        = intercept[SqlException.Decode] { MysqlRowReader.decodeDateBytes(malformed) }
        assert(ex.getMessage.contains("DATE: unexpected struct length 3"), s"message was: ${ex.getMessage}")
    }

    "decodeDatetimeBytes on malformed struct length throws SqlException.Decode" in {
        val malformed = Span.from(Array[Byte](0x01.toByte, 0x02.toByte)) // 2 bytes, not 0, 4, 7, or 11
        val ex        = intercept[SqlException.Decode] { MysqlRowReader.decodeDatetimeBytes(malformed) }
        assert(ex.getMessage.contains("DATETIME: unexpected struct length 2 (expected 0, 4, 7, or 11)"), s"message was: ${ex.getMessage}")
    }

    // ── Structural reader operation leaves (appended from Phase 24) ──────────

    private def textBytes(s: String): Span[Byte] =
        Span.from(s.getBytes(StandardCharsets.UTF_8))

    private def mysqlRow(name: String, bytes: Span[Byte]): SqlRow =
        new SqlRow(Chunk(Maybe.Present(bytes)), Chunk(field(name)), Format.Binary)

    "structural reader operations" - {

        "captureValue buffers a TYPE_STRING column (MySQL)" in {
            val bytes  = textBytes("fixture")
            val row    = mysqlRow("col", bytes)
            val reader = new MysqlRowReader(row)
            val cap    = reader.captureValue()
            assert(cap.string() == "fixture")
            succeed
        }

        "MySQL arrayStart reads a JSON array column [1,2,3]" in {
            val jsonBytes = textBytes("[1,2,3]")
            val row       = mysqlRow("arr", jsonBytes)
            val reader    = new MysqlRowReader(row)
            val count     = reader.arrayStart()
            assert(count == 3, s"expected 3 elements, got $count")
            assert(reader.hasNextElement(), "should have element 1")
            val v1 = reader.int()
            assert(reader.hasNextElement(), "should have element 2")
            val v2 = reader.int()
            assert(reader.hasNextElement(), "should have element 3")
            val v3 = reader.int()
            assert(!reader.hasNextElement(), "should have no more elements")
            reader.arrayEnd()
            assert(v1 == 1, s"element 1 should be 1, got $v1")
            assert(v2 == 2, s"element 2 should be 2, got $v2")
            assert(v3 == 3, s"element 3 should be 3, got $v3")
            succeed
        }

        "MySQL mapStart reads a JSON object column {\"k\":1}" in {
            val jsonBytes = textBytes("""{"k":1}""")
            val row       = mysqlRow("col", jsonBytes)
            val reader    = new MysqlRowReader(row)
            val count     = reader.mapStart()
            assert(count == 1, s"expected 1 entry, got $count")
            assert(reader.hasNextEntry(), "should have an entry")
            val k = reader.field()
            val v = reader.int()
            assert(!reader.hasNextEntry(), "should have no more entries")
            reader.mapEnd()
            assert(k == "k", s"key should be 'k', got '$k'")
            assert(v == 1, s"value should be 1, got $v")
            succeed
        }

    }

end MysqlRowReaderTest
