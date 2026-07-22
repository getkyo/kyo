package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Frame
import kyo.Instant
import kyo.Maybe
import kyo.Span
import kyo.SqlDecodeColumnNullException
import kyo.SqlDecodeEmptyStringForCharException
import kyo.SqlDecodeException
import kyo.SqlException
import kyo.SqlRow
import kyo.SqlUnsupportedException
import kyo.Test
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresDecoder
import kyo.internal.postgres.types.PostgresEncoder

/** Verifies that [[PostgresRowReader]] decodes raw binary wire bytes from a [[SqlRow]] into the correct Scala values.
  *
  * Each test constructs a [[SqlRow]] whose column bytes are produced by the corresponding [[PostgresEncoder]] singleton, then asserts that
  * the reader returns a value equal to the original. This confirms byte-for-byte round-trip parity with the encoder/decoder layer.
  */
class PostgresRowReaderTest extends Test:

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Encodes `value` using `enc` and returns the raw wire bytes. */
    private def encode[A](value: A, enc: PostgresEncoder[A]): Span[Byte] =
        val buf = new PostgresBufferWriter
        enc.write(value, buf)
        buf.toSpan
    end encode

    /** Encodes `value` using `enc` as a text UTF-8 string. */
    private def encodeText(value: String): Span[Byte] =
        Span.from(value.getBytes(StandardCharsets.UTF_8))

    private def field(name: String): FieldDescription =
        FieldDescription(name, 0, 0, 0, 0, 0, 1) // formatCode=1 for Binary

    /** Builds a [[SqlRow]] with one binary-format column whose bytes are `bytes`. */
    private def binaryRow(bytes: Span[Byte]): SqlRow =
        new SqlRow(Chunk(Maybe.Present(bytes)), Chunk(field("column")), Format.Binary)

    /** Builds a [[SqlRow]] with one text-format column whose bytes are `bytes`. */
    private def textRow(bytes: Span[Byte]): SqlRow =
        new SqlRow(Chunk(Maybe.Present(bytes)), Chunk(field("column")), Format.Text)

    /** Builds a [[SqlRow]] with one binary-format column that is SQL NULL. */
    private def nullRow(): SqlRow =
        new SqlRow(Chunk(Maybe.empty[Span[Byte]]), Chunk(field("column")), Format.Binary)

    /** Builds a [[PostgresRowReader]] wrapping the given [[SqlRow]]. */
    private def reader(row: SqlRow): PostgresRowReader =
        new PostgresRowReader(row)

    "decodes Long from 8-byte big-endian binary" in {
        val values = Seq(0L, 1L, -1L, Long.MaxValue, Long.MinValue, 42L, 1_000_000_000_000L)
        for v <- values do
            val row = binaryRow(encode(v, PostgresEncoder.int8Binary))
            val r   = reader(row)
            assert(r.long() == v, s"long $v")
        end for
        succeed
    }

    "decodes Int from 4-byte big-endian binary" in {
        val values = Seq(0, 1, -1, Int.MaxValue, Int.MinValue, 42)
        for v <- values do
            val row = binaryRow(encode(v, PostgresEncoder.int4Binary))
            val r   = reader(row)
            assert(r.int() == v, s"int $v")
        end for
        succeed
    }

    "decodes Short from 2-byte big-endian binary" in {
        val values = Seq(0.toShort, 1.toShort, (-1).toShort, Short.MaxValue, Short.MinValue)
        for v <- values do
            val row = binaryRow(encode(v, PostgresEncoder.int2Binary))
            val r   = reader(row)
            assert(r.short() == v, s"short $v")
        end for
        succeed
    }

    "decodes String from UTF-8 bytes" in {
        val values = Seq("hello", "", "Hello, 世界", "a" * 1000)
        for v <- values do
            val row = binaryRow(encodeText(v))
            val r   = reader(row)
            assert(r.string() == v, s"string '$v'")
        end for
        succeed
    }

    "decodes Boolean from single byte (1=true, 0=false)" in {
        val trueRow  = binaryRow(encode(true, PostgresEncoder.boolBinary))
        val falseRow = binaryRow(encode(false, PostgresEncoder.boolBinary))
        assert(reader(trueRow).boolean() == true)
        assert(reader(falseRow).boolean() == false)
    }

    "decodes Float from IEEE 754 4-byte binary" in {
        val values = Seq(0.0f, 1.5f, -1.5f, Float.MaxValue, Float.MinPositiveValue)
        for v <- values do
            val row = binaryRow(encode(v, PostgresEncoder.float4Binary))
            val r   = reader(row)
            assert(r.float() == v, s"float $v")
        end for
        succeed
    }

    "decodes Double from IEEE 754 8-byte binary" in {
        val values = Seq(0.0d, 1.5d, -1.5d, Math.PI, Double.MaxValue, Double.MinPositiveValue)
        for v <- values do
            val row = binaryRow(encode(v, PostgresEncoder.float8Binary))
            val r   = reader(row)
            assert(r.double() == v, s"double $v")
        end for
        succeed
    }

    "decodes BigDecimal from PG numeric text format" in {
        val values = Seq(
            BigDecimal("0"),
            BigDecimal("1"),
            BigDecimal("-1"),
            BigDecimal("0.001"),
            BigDecimal("1234567890.12345"),
            BigDecimal("-9999999999.9999")
        )
        for v <- values do
            // bigDecimal on the writer side uses numericText (Format.Text); reader decodes the same.
            val row = textRow(encodeText(v.toString))
            val r   = reader(row)
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
            val row = binaryRow(v)
            val r   = reader(row)
            assert(r.bytes().toArray.sameElements(v.toArray), s"bytea mismatch")
        end for
        succeed
    }

    "decodes kyo.Instant from PG epoch microseconds" in {
        val values = Seq(
            java.time.Instant.EPOCH,
            java.time.Instant.parse("2000-01-01T00:00:00Z"), // PG epoch → pgMicros = 0
            java.time.Instant.ofEpochSecond(1_000_000L),
            java.time.Instant.ofEpochSecond(-1L)
        )
        for v <- values do
            val kyoInstant = Instant.fromJava(v)
            val row        = binaryRow(encode(kyoInstant, PostgresEncoder.timestamptzBinary))
            val r          = reader(row)
            // Reader returns java.time.Instant; compare with truncation to microseconds (PG wire precision).
            val decoded        = r.instant()
            val expectedMicros = v.getEpochSecond * 1_000_000L + v.getNano / 1_000L
            val decodedMicros  = decoded.getEpochSecond * 1_000_000L + decoded.getNano / 1_000L
            assert(decodedMicros == expectedMicros, s"instant $v: got $decoded, expected $v")
        end for
        succeed
    }
    // Codec.Reader has no localDate() method; verify the underlying decoder directly.

    "decodes LocalDate from PG days-since-epoch via PostgresDecoder" in {
        val values = Seq(
            java.time.LocalDate.of(2000, 1, 1), // PG epoch → days = 0
            java.time.LocalDate.of(1970, 1, 1), // Unix epoch
            java.time.LocalDate.of(2024, 2, 29) // leap day
        )
        for v <- values do
            val bytes   = encode(v, PostgresEncoder.dateBinary)
            val decoded = PostgresDecoder.date.read(Format.Binary, bytes)
            assert(decoded.equals(v), s"localDate $v: got $decoded")
        end for
        succeed
    }
    // Codec.Reader has no localDateTime() method; verify the underlying decoder directly.

    "decodes LocalDateTime from PG epoch microseconds via PostgresDecoder" in {
        val values = Seq(
            java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 0), // PG epoch → 0 micros
            java.time.LocalDateTime.of(2024, 6, 15, 12, 30, 0)
        )
        for v <- values do
            val bytes   = encode(v, PostgresEncoder.timestampBinary)
            val decoded = PostgresDecoder.timestamp.read(Format.Binary, bytes)
            assert(decoded.equals(v), s"localDateTime $v: got $decoded")
        end for
        succeed
    }

    "custom returns raw column bytes" in {
        val payload = Span.from(Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte))
        val row     = binaryRow(payload)
        val r       = reader(row)
        val result  = r.custom("geometry")
        assert(result.toArray.sameElements(payload.toArray))
    }

    "multiple columns read positionally" in {
        val intBytes  = encode(7, PostgresEncoder.int4Binary)
        val textBytes = encodeText("alice")
        val boolBytes = encode(true, PostgresEncoder.boolBinary)

        val fields = Chunk(field("id"), field("name"), field("active"))
        val values = Chunk[Maybe[Span[Byte]]](
            Maybe.Present(intBytes),
            Maybe.Present(textBytes),
            Maybe.Present(boolBytes)
        )
        val row = new SqlRow(values, fields, Format.Binary)
        val r   = reader(row)

        assert(r.int() == 7)
        assert(r.string() == "alice")
        assert(r.boolean() == true)
    }

    "frame is preserved through to subclass" in {
        def makeReader()(using f: Frame): PostgresRowReader =
            val row = binaryRow(encode(0, PostgresEncoder.int4Binary))
            new PostgresRowReader(row)
        end makeReader
        val r = makeReader()
        // frame is not null, that's the minimal contract; Frame equality is not specified.
        assert(r.frame != null)
        succeed
    }

    // ── isNil: peek without advancing ────────────────────────────────────────

    "isNil returns true for NULL column and does not advance the cursor" in {
        val row = nullRow()
        val r   = reader(row)
        assert(r.isNil() == true)
        // isNil must not advance: calling it twice returns true both times.
        assert(r.isNil() == true)
        // And reading after isNil on a null column should throw SqlDecodeColumnNullException.
        val ex = intercept[SqlDecodeColumnNullException] {
            r.int()
        }
        assert(ex.columnIndex == 0, s"expected columnIndex 0, got: ${ex.columnIndex}")
        succeed
    }

    "isNil returns false for non-NULL column and does not advance the cursor" in {
        val row = binaryRow(encode(99, PostgresEncoder.int4Binary))
        val r   = reader(row)
        assert(r.isNil() == false)
        // Cursor was not advanced, value is still readable.
        assert(r.int() == 99)
    }

    "reading an empty-string column as Char throws SqlDecodeException with original message" in {
        val emptyTextBytes = Span.from("".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val row            = binaryRow(emptyTextBytes)
        val r              = reader(row)
        val ex = intercept[SqlDecodeEmptyStringForCharException] {
            r.char()
        }
        assert(ex.columnIndex == 0, s"expected columnIndex 0, got: ${ex.columnIndex}")
        succeed
    }

    "arrayStart on a flat SQL row throws SqlDecodeException with unrecognised OID message" in {
        // Use int4 OID (23) so the OID-mismatch check fires before any binary parsing.
        val int4Oid = 23
        val fdInt4  = FieldDescription("column", 0, 0, int4Oid, 0, 0, 1)
        val row     = new SqlRow(Chunk(Maybe.Present(encode(1, PostgresEncoder.int4Binary))), Chunk(fdInt4), Format.Binary)
        val r       = new PostgresRowReader(row)
        val ex = intercept[SqlDecodeException] {
            r.arrayStart()
        }
        assert(ex.message.contains("not a recognised array OID"))
        succeed
    }

    // ── Structural reader operation leaves (appended from Phase 24) ──────────

    private def textBytes(s: String): Span[Byte] =
        Span.from(s.getBytes(StandardCharsets.UTF_8))

    private def fieldWithOid(name: String, oid: Int): FieldDescription =
        FieldDescription(name, 0, 0, oid, 0, 0, 1) // formatCode=1 Binary

    private def pgRowCols(columns: (String, Span[Byte], Int)*): SqlRow =
        val values = Chunk.from(columns.map { case (_, b, _) => Maybe.Present(b) })
        val fields = Chunk.from(columns.map { case (n, _, oid) => fieldWithOid(n, oid) })
        new SqlRow(values, fields, Format.Binary)
    end pgRowCols

    private def pgRowNamed(name: String, bytes: Span[Byte]): SqlRow =
        new SqlRow(Chunk(Maybe.Present(bytes)), Chunk(fieldWithOid(name, 0)), Format.Binary)

    /** Build PG binary int4[] bytes for `{1,2,3}`: ndim=1 | hasNulls=0 | elemOID=23 | dimSize=3 | lbound=1 | (len=4|val)*3
      */
    private def pgInt4ArrayBytes(values: Int*): Span[Byte] =
        val buf = new java.io.ByteArrayOutputStream
        def writeInt32BE(v: Int): Unit =
            buf.write((v >> 24) & 0xff)
            buf.write((v >> 16) & 0xff)
            buf.write((v >> 8) & 0xff)
            buf.write(v & 0xff)
        end writeInt32BE
        writeInt32BE(1)           // ndim
        writeInt32BE(0)           // hasNulls
        writeInt32BE(23)          // elemOID (int4)
        writeInt32BE(values.size) // dim_size
        writeInt32BE(1)           // lbound
        for v <- values do
            writeInt32BE(4) // element length
            writeInt32BE(v) // element value
        Span.from(buf.toByteArray)
    end pgInt4ArrayBytes

    /** Build PG binary text[] bytes for `{"a","b"}`. */
    private def pgTextArrayBytes(values: String*): Span[Byte] =
        val buf = new java.io.ByteArrayOutputStream
        def writeInt32BE(v: Int): Unit =
            buf.write((v >> 24) & 0xff)
            buf.write((v >> 16) & 0xff)
            buf.write((v >> 8) & 0xff)
            buf.write(v & 0xff)
        end writeInt32BE
        writeInt32BE(1)           // ndim
        writeInt32BE(0)           // hasNulls
        writeInt32BE(25)          // elemOID (text)
        writeInt32BE(values.size) // dim_size
        writeInt32BE(1)           // lbound
        for v <- values do
            val vBytes = v.getBytes(StandardCharsets.UTF_8)
            writeInt32BE(vBytes.length)
            buf.write(vBytes)
        end for
        Span.from(buf.toByteArray)
    end pgTextArrayBytes

    "structural reader operations" - {

        "captureValue buffers a column for sum-codec decode (Postgres)" in {
            val bytes  = textBytes("fixture")
            val row    = pgRowNamed("col", bytes)
            val reader = new PostgresRowReader(row)
            val cap    = reader.captureValue()
            assert(cap.string() == "fixture")
            succeed
        }

        "arrayStart reads PG int[] header and decodes elements" in {
            val arrayBytes = pgInt4ArrayBytes(1, 2, 3)
            // OID 1007 = _int4
            val row    = pgRowCols(("arr", arrayBytes, 1007))
            val reader = new PostgresRowReader(row)
            val count  = reader.arrayStart()
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

        "arrayStart reads PG text[] header and decodes elements" in {
            val arrayBytes = pgTextArrayBytes("a", "b")
            // OID 1009 = _text
            val row    = pgRowCols(("arr", arrayBytes, 1009))
            val reader = new PostgresRowReader(row)
            val count  = reader.arrayStart()
            assert(count == 2, s"expected 2 elements, got $count")
            assert(reader.hasNextElement(), "should have element 1")
            val s1 = reader.string()
            assert(reader.hasNextElement(), "should have element 2")
            val s2 = reader.string()
            assert(!reader.hasNextElement(), "should have no more elements")
            reader.arrayEnd()
            assert(s1 == "a", s"element 1 should be 'a', got '$s1'")
            assert(s2 == "b", s"element 2 should be 'b', got '$s2'")
            succeed
        }

        "mapStart reads PG jsonb object and decodes entry" in {
            // JSONB binary: 1-byte version prefix (0x01) + JSON text. OID 3802 (jsonb) is
            // required so PostgresRowReader.mapStart dispatches to the JsonReader path.
            val jsonBody  = """{"k":"v"}"""
            val jsonBytes = Span.from(Array[Byte](0x01.toByte) ++ jsonBody.getBytes(StandardCharsets.UTF_8))
            val row       = pgRowCols(("col", jsonBytes, 3802))
            val reader    = new PostgresRowReader(row)
            val count     = reader.mapStart()
            // Codec.Reader.mapStart() contract: return the real entry count, never -1.
            assert(count == 1, s"expected 1 entry, got $count")
            assert(reader.hasNextEntry(), "should have an entry")
            val k = reader.field()
            val v = reader.string()
            assert(!reader.hasNextEntry(), "should have no more entries")
            reader.mapEnd()
            assert(k == "k", s"key should be 'k', got '$k'")
            assert(v == "v", s"value should be 'v', got '$v'")
            succeed
        }

        "mapStart reads PG jsonb with multiple entries and returns real count" in {
            val jsonBody  = """{"a":"1","b":"2","c":"3"}"""
            val jsonBytes = Span.from(Array[Byte](0x01.toByte) ++ jsonBody.getBytes(StandardCharsets.UTF_8))
            val row       = pgRowCols(("col", jsonBytes, 3802))
            val reader    = new PostgresRowReader(row)
            val count     = reader.mapStart()
            assert(count == 3, s"expected 3 entries from real count, got $count")
            // Consume all 3 entries to advance the underlying JsonReader past the closing '}'.
            val entries = (0 until count).map { _ =>
                assert(reader.hasNextEntry())
                val k = reader.field()
                val v = reader.string()
                (k, v)
            }
            assert(!reader.hasNextEntry())
            reader.mapEnd()
            assert(entries == Seq("a" -> "1", "b" -> "2", "c" -> "3"), s"got $entries")
            succeed
        }

        "mapStart reads PG hstore binary and returns real entry count" in {
            // Hstore binary wire format: Int32 entryCount BE, then per entry: Int32 keyLen BE + key bytes,
            // Int32 valLen BE (-1 = NULL) + value bytes. OID is contrib-extension-installed; use any
            // non-(114/3802) value to dispatch to the HstoreReader path.
            val buf = new java.io.ByteArrayOutputStream
            def writeInt32BE(v: Int): Unit =
                buf.write((v >> 24) & 0xff)
                buf.write((v >> 16) & 0xff)
                buf.write((v >> 8) & 0xff)
                buf.write(v & 0xff)
            end writeInt32BE
            writeInt32BE(2) // 2 entries
            val k1 = "name".getBytes(StandardCharsets.UTF_8)
            val v1 = "alice".getBytes(StandardCharsets.UTF_8)
            writeInt32BE(k1.length)
            buf.write(k1)
            writeInt32BE(v1.length)
            buf.write(v1)
            val k2 = "role".getBytes(StandardCharsets.UTF_8)
            val v2 = "admin".getBytes(StandardCharsets.UTF_8)
            writeInt32BE(k2.length)
            buf.write(k2)
            writeInt32BE(v2.length)
            buf.write(v2)
            val row    = pgRowCols(("col", Span.from(buf.toByteArray), 16384)) // arbitrary non-JSON OID
            val reader = new PostgresRowReader(row)
            val count  = reader.mapStart()
            assert(count == 2, s"expected 2 entries from hstore header, got $count")
            assert(reader.hasNextEntry())
            val firstKey = reader.field()
            val firstVal = reader.string()
            assert(reader.hasNextEntry())
            val secondKey = reader.field()
            val secondVal = reader.string()
            assert(!reader.hasNextEntry())
            reader.mapEnd()
            assert(firstKey == "name" && firstVal == "alice", s"first entry: $firstKey=$firstVal")
            assert(secondKey == "role" && secondVal == "admin", s"second entry: $secondKey=$secondVal")
            succeed
        }

        "arrayStart on a non-array OID raises SqlDecodeException (not Unsupported)" in {
            // OID 23 = int4 (not an array OID)
            val intBytes = pgInt4ArrayBytes() // use any bytes; the OID check happens first
            val row      = pgRowCols(("col", Span.from(Array[Byte](0, 0, 0, 42)), 23))
            val reader   = new PostgresRowReader(row)
            try
                val _ = reader.arrayStart()
                fail("Expected SqlDecodeException but no exception was thrown")
            catch
                case _: SqlDecodeException      => succeed
                case _: SqlUnsupportedException => fail("Got Unsupported, expected Decode")
            end try
        }

    }

end PostgresRowReaderTest
