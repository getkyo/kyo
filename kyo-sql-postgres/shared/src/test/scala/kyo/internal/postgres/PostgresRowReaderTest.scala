package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Frame
import kyo.Instant
import kyo.JsonText
import kyo.Maybe
import kyo.PostgresTypes
import kyo.Span
import kyo.SqlCodec.Format
import kyo.SqlDecodeColumnAbsentException
import kyo.SqlDecodeEmptyStringForCharException
import kyo.SqlDecodeException
import kyo.SqlDecodeMultiCharacterForCharException
import kyo.SqlException
import kyo.SqlRow
import kyo.SqlSchema
import kyo.SqlUnsupportedTypeOnBackendException
import kyo.Test
import kyo.db.Idiom
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

    private def field(name: String): SqlRow.Column =
        SqlRow.Column(name, 0) // no type token: the reader falls back to positional reads

    /** Builds a [[SqlRow]] with one binary-format column whose bytes are `bytes`. */
    private def binaryRow(bytes: Span[Byte]): SqlRow =
        pgRow(Chunk(Maybe.Present(bytes)), Chunk(field("column")), Format.Binary)

    /** Builds a [[SqlRow]] with one text-format column whose bytes are `bytes`. */
    private def textRow(bytes: Span[Byte]): SqlRow =
        pgRow(Chunk(Maybe.Present(bytes)), Chunk(field("column")), Format.Text)

    /** Builds a [[SqlRow]] with one binary-format column that is SQL NULL. */
    private def nullRow(): SqlRow =
        pgRow(Chunk(Maybe.empty[Span[Byte]]), Chunk(field("column")), Format.Binary)

    /** Builds a [[SqlRow]] carrying the PostgreSQL codec for `format`. */
    private def pgRow(values: Chunk[Maybe[Span[Byte]]], columns: Chunk[SqlRow.Column], format: Format): SqlRow =
        new SqlRow(values, columns, PostgresRowCodec(format))

    /** Builds a [[PostgresRowReader]] wrapping the given [[SqlRow]]. */
    private def reader(row: SqlRow): PostgresRowReader =
        new PostgresRowReader(row, PostgresRowCodec.formatOf(row))

    /** Reads `row`'s single column through `column`, which is how a value whose codec is not a scalar read reaches the reader. */
    private def readColumn[A](row: SqlRow, column: SqlSchema.Column[A]): A =
        column.read(reader(row))

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

    "nextExtension returns raw column bytes for a PostgreSQL-owned type, under the row's format" in {
        val payload = Span.from(Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte))
        val row     = binaryRow(payload)
        val r       = reader(row)
        val result  = r.nextExtension(PostgresEncoder.dialectId, "geometry")
        assert(result.format == Format.Binary)
        assert(result.bytes.toArray.sameElements(payload.toArray))
    }

    "nextExtension reports a text-format column as text" in {
        // PostgreSQL negotiates the format per column, so the same extension type arrives binary through the
        // extended protocol and text through a simple query. A carrier that reported one for both would hand the
        // read closure bytes it parses under the wrong grammar.
        val payload = encodeText("192.168.1.1")
        val r       = reader(textRow(payload))
        val result  = r.nextExtension(PostgresEncoder.dialectId, "inet")
        assert(result.format == Format.Text)
        assert(result.bytes.toArray.sameElements(payload.toArray))
    }

    "decodeElement reads the slice in the format the caller states, not the one this row arrived in" in {
        // The bytes are cut out of a composite payload, and the payload's layout fixes its elements' format. The
        // enclosing column's format describes the composite, so using it for the element decodes the wrong grammar
        // whenever the two differ, which is the whole reason the format is a parameter here.
        val column = kyo.SqlSchema.int

        val binaryReader = reader(binaryRow(Span.empty))
        assert(binaryReader.decodeElement(column, encodeText("42"), Format.Text) == 42)

        val textReader = reader(textRow(Span.empty))
        assert(textReader.decodeElement(column, encode(7, PostgresEncoder.int4Binary), Format.Binary) == 7)
    }

    "nextExtension rejects a type another dialect owns without consuming the column" in {
        val payload = Span.from(Array[Byte](0x01))
        val row     = binaryRow(payload)
        val r       = reader(row)
        // A dialect id no backend in this build owns. The property under test is that an extension payload for a
        // dialect other than the active one is rejected; naming a real sibling engine over-specifies that and
        // would make this suite depend on the other backend's module.
        val foreign = Idiom.Id("acme")
        val ex = intercept[SqlUnsupportedTypeOnBackendException] {
            val _ = r.nextExtension(foreign, "geometry")
        }
        assert(ex.dialect == foreign)
        assert(ex.activeDialect == PostgresEncoder.dialectId)
        // The cursor did not advance: the column is still readable.
        assert(r.bytes().toArray.sameElements(payload.toArray))
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
        val row = pgRow(values, fields, Format.Binary)
        val r   = reader(row)

        assert(r.int() == 7)
        assert(r.string() == "alice")
        assert(r.boolean() == true)
    }

    "frame is preserved through to subclass" in {
        def makeReader()(using f: Frame): (Frame, PostgresRowReader) =
            val row = binaryRow(encode(0, PostgresEncoder.int4Binary))
            // Constructed here, not through the `reader` helper, so the captured Frame is this site's.
            (f, new PostgresRowReader(row, Format.Binary))
        end makeReader
        val (frameAtConstruction, r) = makeReader()
        assert(r.frame == frameAtConstruction, "PostgresRowReader must expose the Frame captured at construction")
    }

    // ── isNil: consumes on true, leaves the cursor alone on false ────────────

    // A true answer has to consume the column. The nullable column codec reads a `Maybe` field as
    // `if r.isNil() then Maybe.empty else Maybe(inner.read(r))`, so a true answer that left the cursor in place
    // would leave the NULL column in front of the next field's read and shift every field after it by one.
    "isNil returns true for a NULL column and consumes it" in {
        val row = pgRow(
            Chunk(Maybe.empty[Span[Byte]], Maybe.Present(encode(99, PostgresEncoder.int4Binary))),
            Chunk(field("nullable"), field("present")),
            Format.Binary
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
        val row = pgRow(
            Chunk(Maybe.empty[Span[Byte]], Maybe.empty[Span[Byte]], Maybe.Present(encode(5, PostgresEncoder.int4Binary))),
            Chunk(field("_1"), field("_2"), field("_3")),
            Format.Binary
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
        val row = nullRow()
        val r   = reader(row)
        val ex = intercept[SqlDecodeColumnAbsentException] {
            r.int()
        }
        assert(ex.columnIndex == Maybe(0), s"expected columnIndex 0, got: ${ex.columnIndex}")
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
        assert(ex.columnIndex == Maybe(0), s"expected columnIndex 0, got: ${ex.columnIndex}")
        succeed
    }

    "reading a multi-character column as Char is refused rather than truncated to the first character" in {
        val row = binaryRow(Span.from("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        val ex  = intercept[SqlDecodeMultiCharacterForCharException](reader(row).char())
        assert(ex.characterCount == 3, s"expected 3 characters reported, got: ${ex.characterCount}")
        assert(ex.columnIndex == Maybe(0), s"expected column 0 reported, got: ${ex.columnIndex}")
    }

    "an array read over a scalar column raises a decode failure, not an unsupported-operation one" in {
        // A four-byte int4 payload cannot hold the twenty-byte array header, and the refusal has to be a decode
        // failure: an Unsupported leaf would say the backend cannot read arrays at all, which is the opposite of
        // what happened.
        val fdInt4 = SqlRow.Column("column", 23)
        val row    = pgRow(Chunk(Maybe.Present(encode(1, PostgresEncoder.int4Binary))), Chunk(fdInt4), Format.Binary)
        val r      = reader(row)
        val ex = intercept[kyo.SqlDecodeArrayFormatException] {
            val _ = r.nextArrayOfInt()
        }
        // The intercepted type IS the assertion: SqlDecodeArrayFormatException is a decode leaf, and an
        // Unsupported one could not be thrown at it.
        assert(ex.length == 4, s"the refusal reports the payload length it was handed, got ${ex.length}")
    }

    // ── Container-column leaves ───────────────────────────────────────────────

    private def textBytes(s: String): Span[Byte] =
        Span.from(s.getBytes(StandardCharsets.UTF_8))

    private def fieldWithOid(name: String, oid: Int): SqlRow.Column =
        SqlRow.Column(name, oid)

    private def pgRowCols(columns: (String, Span[Byte], Int)*): SqlRow =
        val values = Chunk.from(columns.map { case (_, b, _) => Maybe.Present(b) })
        val fields = Chunk.from(columns.map { case (n, _, oid) => fieldWithOid(n, oid) })
        pgRow(values, fields, Format.Binary)
    end pgRowCols

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

    /** Builds a text-format row whose columns hold the given renderings under the given OIDs. */
    private def pgTextRow(columns: (String, String, Int)*): SqlRow =
        val values = Chunk.from(columns.map { case (_, v, _) => Maybe.Present(textBytes(v)) })
        val fields = Chunk.from(columns.map { case (n, _, oid) => fieldWithOid(n, oid) })
        pgRow(values, fields, Format.Text)
    end pgTextRow

    /** Builds a one-dimensional binary array payload over already-encoded elements, under `elemOid`. An `Absent` element is written as
      * PostgreSQL's own NULL element, an `elemLen` of -1
      * followed by no data, which is the wire format's marker and not a sentinel this test invents.
      */
    private def pgBinaryArrayBytes(elemOid: Int, elements: Seq[Maybe[Span[Byte]]]): Span[Byte] =
        val buf = new java.io.ByteArrayOutputStream
        def writeInt32BE(v: Int): Unit =
            buf.write((v >> 24) & 0xff)
            buf.write((v >> 16) & 0xff)
            buf.write((v >> 8) & 0xff)
            buf.write(v & 0xff)
        end writeInt32BE
        writeInt32BE(1)                                           // ndim
        writeInt32BE(if elements.exists(_.isEmpty) then 1 else 0) // hasNulls
        writeInt32BE(elemOid)                                     // elemOID
        writeInt32BE(elements.size)                               // dim_size
        writeInt32BE(1)                                           // lbound
        for e <- elements do
            e match
                case Maybe.Present(bytes) =>
                    writeInt32BE(bytes.size)
                    buf.write(bytes.toArray)
                case Maybe.Absent =>
                    writeInt32BE(-1)
        end for
        Span.from(buf.toByteArray)
    end pgBinaryArrayBytes

    /** Builds an hstore binary payload: Int32 entryCount BE, then per entry Int32 keyLen BE + key, Int32 valLen BE + value. */
    private def pgHstoreBytes(entries: (String, String)*): Span[Byte] =
        val buf = new java.io.ByteArrayOutputStream
        def writeInt32BE(v: Int): Unit =
            buf.write((v >> 24) & 0xff)
            buf.write((v >> 16) & 0xff)
            buf.write((v >> 8) & 0xff)
            buf.write(v & 0xff)
        end writeInt32BE
        writeInt32BE(entries.size)
        for (k, v) <- entries do
            val kBytes = k.getBytes(StandardCharsets.UTF_8)
            val vBytes = v.getBytes(StandardCharsets.UTF_8)
            writeInt32BE(kBytes.length)
            buf.write(kBytes)
            writeInt32BE(vBytes.length)
            buf.write(vBytes)
        end for
        Span.from(buf.toByteArray)
    end pgHstoreBytes

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

    "container columns" - {

        // A container column is a whole-column read through the vocabulary: `nextArrayOfInt` hands its bytes to
        // PostgresArrayReader and returns the decoded Chunk, `nextExtension` hands an hstore payload to the read
        // closure that PostgresTypes installs. Both wire formats reach here, because SimpleQueryExchange receives
        // every column in text while the extended protocol negotiates binary, and a parser that assumed one would
        // read `{1,2,3}`'s first four bytes as an ndim of 2054844416.

        "nextArrayOfInt reads a binary int4[] column" in {
            val row = pgRowCols(("arr", pgInt4ArrayBytes(1, 2, 3), PostgresEncoder.OID_INT4_ARRAY))
            assert(reader(row).nextArrayOfInt() == Chunk(1, 2, 3))
        }

        "nextArrayOfString reads a binary text[] column" in {
            val row = pgRowCols(("arr", pgTextArrayBytes("a", "b"), PostgresEncoder.OID_TEXT_ARRAY))
            assert(reader(row).nextArrayOfString() == Chunk("a", "b"))
        }

        "an array column hands the row cursor back to the column after it" in {
            // The array is one column however many elements it holds, so the trailing column has to be next. A
            // read that consumed a column per element would leave the cursor past it.
            val row = pgRowCols(
                ("arr", pgInt4ArrayBytes(1, 2, 3), PostgresEncoder.OID_INT4_ARRAY),
                ("id", encode(7L, PostgresEncoder.int8Binary), PostgresEncoder.OID_INT8)
            )
            val rowReader = reader(row)
            assert(rowReader.nextArrayOfInt() == Chunk(1, 2, 3))
            assert(rowReader.long() == 7L, "the row cursor must still be on the column after the array")
        }

        "nextExtension hands an hstore payload to the hstore codec, which reads its entries" in {
            val row = pgRowCols(("col", pgHstoreBytes("name" -> "alice", "role" -> "admin"), 16384))
            assert(
                readColumn(row, PostgresTypes.hstoreColumn) ==
                    PostgresTypes.HStore(Map("name" -> Maybe("alice"), "role" -> Maybe("admin")))
            )
        }

        // ── A NULL array element names its own position ───────────────────────────
        //
        // The index has to come off the array walk rather than off the row, and the two wire formats track it
        // differently: the binary path decrements a remaining count, the text path parses every element up front
        // and walks an index. Both are covered, because a counter that is right for one can be wrong for the
        // other while the leaf reads identically either way.

        "a binary-format array names the NULL element's own position" in {
            val elements = Seq(Maybe(encode(1, PostgresEncoder.int4Binary)), Maybe.empty[Span[Byte]])
            val row      = pgRowCols(("arr", pgBinaryArrayBytes(23, elements), PostgresEncoder.OID_INT4_ARRAY))
            val ex       = intercept[kyo.SqlDecodeArrayAbsentElementException](reader(row).nextArrayOfInt())
            assert(ex.arrayIndex == 1, s"expected the NULL element's own position 1, got ${ex.arrayIndex}")
            assert(ex.scalaType == "Int", s"expected the Int target named, got ${ex.scalaType}")
        }

        // ── Text-format arrays and hstore (the simple-query path) ─────────────────

        "a text-format int4[] rendering decodes its elements" in {
            val row = pgTextRow(("arr", "{1,2,3}", PostgresEncoder.OID_INT4_ARRAY))
            assert(reader(row).nextArrayOfInt() == Chunk(1, 2, 3))
        }

        "a text-format text[] rendering resolves quotes and escapes" in {
            // What array_out renders for the three elements `a`, `b,c` and `d"e`.
            val row = pgTextRow(("arr", """{a,"b,c","d\"e"}""", PostgresEncoder.OID_TEXT_ARRAY))
            assert(reader(row).nextArrayOfString() == Chunk("a", "b,c", "d\"e"))
        }

        "a text-format array distinguishes an unquoted NULL element from the quoted string" in {
            val row = pgTextRow(("arr", """{"NULL",NULL}""", PostgresEncoder.OID_TEXT_ARRAY))
            val ex  = intercept[kyo.SqlDecodeArrayAbsentElementException](reader(row).nextArrayOfString())
            assert(ex.scalaType == "String", s"expected the String target named, got ${ex.scalaType}")
            // The NULL is the SECOND element, and the leaf names that position: the quoted one before it is the
            // four-character string, not a NULL.
            assert(ex.arrayIndex == 1, s"expected the NULL element's own position 1, got ${ex.arrayIndex}")
            assert(
                ex.getMessage.contains("Array element at index 1 held no value and is not decodable as String"),
                s"got ${ex.getMessage}"
            )
        }

        "a text-format array of one quoted NULL is the four-character string" in {
            val row = pgTextRow(("arr", """{"NULL"}""", PostgresEncoder.OID_TEXT_ARRAY))
            assert(reader(row).nextArrayOfString() == Chunk("NULL"))
        }

        "a text-format empty array has no elements" in {
            val row = pgTextRow(("arr", "{}", PostgresEncoder.OID_INT4_ARRAY))
            assert(reader(row).nextArrayOfInt() == Chunk.empty[Int])
        }

        "a text-format array with an explicit lower bound skips the range prefix" in {
            // PostgreSQL emits `[0:2]={...}` whenever a lower bound is not 1; a Chunk has no lower bound.
            val row = pgTextRow(("arr", "[0:2]={4,5,6}", PostgresEncoder.OID_INT4_ARRAY))
            assert(reader(row).nextArrayOfInt() == Chunk(4, 5, 6))
        }

        "a multi-dimensional text-format array is refused, as the binary ndim check refuses one" in {
            val row = pgTextRow(("arr", "{{1,2},{3,4}}", PostgresEncoder.OID_INT4_ARRAY))
            intercept[kyo.SqlDecodeArrayFormatException](reader(row).nextArrayOfInt())
            succeed
        }

        "a text-format hstore rendering decodes its entries" in {
            val row = pgTextRow(("col", """"name"=>"alice", "role"=>"admin"""", 16384))
            assert(
                readColumn(row, PostgresTypes.hstoreColumn) ==
                    PostgresTypes.HStore(Map("name" -> Maybe("alice"), "role" -> Maybe("admin")))
            )
        }

        "a text-format hstore distinguishes an unquoted NULL value from the quoted string" in {
            // A NULL hstore VALUE is not a NULL column and not the four-character string: HStore models it as an
            // absent value, so both survive the same rendering.
            val row = pgTextRow(("col", """"a"=>NULL, "b"=>"NULL"""", 16384))
            assert(
                readColumn(row, PostgresTypes.hstoreColumn) ==
                    PostgresTypes.HStore(Map("a" -> Maybe.empty[String], "b" -> Maybe("NULL")))
            )
        }

        "a text-format hstore resolves escapes inside its quoted tokens" in {
            val row = pgTextRow(("col", """"a=>b"=>"x\"y"""", 16384))
            assert(
                readColumn(row, PostgresTypes.hstoreColumn) ==
                    PostgresTypes.HStore(Map("a=>b" -> Maybe("x\"y"))),
                "a quoted key may hold the entry separator itself"
            )
        }

        "an empty text-format hstore has no entries" in {
            val row = pgTextRow(("col", "", 16384))
            assert(readColumn(row, PostgresTypes.hstoreColumn) == PostgresTypes.HStore(Map.empty))
        }

        // ── Each container column hands the row cursor back where it found it ─────
        //
        // A scalar on BOTH sides of the container column is what makes that visible: the leading one proves the
        // cursor arrived where the container read started, the trailing one proves it came back. One leaf per
        // container shape, since each reaches the wire through a different vocabulary method.

        "a JSON document field hands the row cursor back to the column after it" in {
            case class JsonSandwich(a: Int, m: JsonText, z: String) derives CanEqual

            val jsonBytes = Span.from(Array[Byte](0x01.toByte) ++ """{"k":2}""".getBytes(StandardCharsets.UTF_8))
            val row = pgRowCols(
                ("a", encode(1, PostgresEncoder.int4Binary), PostgresEncoder.OID_INT4),
                ("m", jsonBytes, 3802),
                ("z", textBytes("tail"), PostgresEncoder.OID_TEXT)
            )
            kyo.Abort.run[SqlDecodeException](row.decode[JsonSandwich]).eval match
                case kyo.Result.Success(v) =>
                    assert(v.a == 1, s"the column before the document must decode, got ${v.a}")
                    assert(v.m.text == """{"k":2}""", s"the document must decode, got ${v.m.text}")
                    assert(v.z == "tail", s"the column after the document must decode, got '${v.z}'")
                case other => fail(s"Expected a decoded JsonSandwich but got $other")
            end match
        }

        "a binary array field hands the row cursor back to the column after it" in {
            case class ArraySandwich(a: Int, xs: Chunk[Int], z: String) derives CanEqual

            val row = pgRowCols(
                ("a", encode(1, PostgresEncoder.int4Binary), PostgresEncoder.OID_INT4),
                ("xs", pgInt4ArrayBytes(2, 3), PostgresEncoder.OID_INT4_ARRAY),
                ("z", textBytes("tail"), PostgresEncoder.OID_TEXT)
            )
            kyo.Abort.run[SqlDecodeException](row.decode[ArraySandwich]).eval match
                case kyo.Result.Success(v) =>
                    assert(v.a == 1, s"the column before the array must decode, got ${v.a}")
                    assert(v.xs == Chunk(2, 3), s"the array must decode, got ${v.xs}")
                    assert(v.z == "tail", s"the column after the array must decode, got '${v.z}'")
                case other => fail(s"Expected a decoded ArraySandwich but got $other")
            end match
        }

        "an hstore field hands the row cursor back to the column after it" in {
            case class HstoreSandwich(a: Int, m: PostgresTypes.HStore, z: String) derives CanEqual

            // The hstore OID is contrib-extension-installed, so any non-json, non-jsonb value routes here.
            val row = pgRowCols(
                ("a", encode(1, PostgresEncoder.int4Binary), PostgresEncoder.OID_INT4),
                ("m", pgHstoreBytes("k" -> "v"), 16384),
                ("z", textBytes("tail"), PostgresEncoder.OID_TEXT)
            )
            kyo.Abort.run[SqlDecodeException](row.decode[HstoreSandwich]).eval match
                case kyo.Result.Success(v) =>
                    assert(v.a == 1, s"the column before the hstore must decode, got ${v.a}")
                    assert(v.m == PostgresTypes.HStore(Map("k" -> Maybe("v"))), s"the hstore must decode, got ${v.m}")
                    assert(v.z == "tail", s"the column after the hstore must decode, got '${v.z}'")
                case other => fail(s"Expected a decoded HstoreSandwich but got $other")
            end match
        }

    }

    // ── Decode failures through the row codec ─────────────────────────────────
    //
    // The wire-level half of the schema decode contract: too few bytes for the type asked for must
    // surface as Abort[SqlDecodeException], never as a throw escaping the codec.

    "a payload too short for the type surfaces as Abort[SqlDecodeException]" in {
        // 3 bytes where int8 needs 8.
        val row = binaryRow(Span.from(Array[Byte](0x01, 0x02, 0x03)))
        kyo.Abort.run[SqlDecodeException](row.decode[Long]).eval match
            case kyo.Result.Failure(_: SqlDecodeException) => succeed
            case other                                     => fail(s"Expected Failure(SqlDecodeException) but got $other")
    }

    "a one-byte payload for int8 surfaces as Abort[SqlDecodeException]" in {
        val row = binaryRow(Span.from(Array[Byte](0x01)))
        kyo.Abort.run[SqlDecodeException](row.decode[Long]).eval match
            case kyo.Result.Failure(_: SqlDecodeException) => succeed
            case other                                     => fail(s"Expected Failure(SqlDecodeException) but got $other")
    }

    "a decode failure is a SqlException, the widening contract every Abort.recover on the decode path relies on" in {
        val row = binaryRow(Span.from(Array[Byte](0x01, 0x02, 0x03)))
        kyo.Abort.run[SqlDecodeException](row.decode[Long]).eval match
            case kyo.Result.Failure(_: SqlDecodeException) => succeed
            case other                                     => fail(s"Expected decode failure but got $other")
        end match
    }

    "a row round-trips a Long written by the PostgreSQL param writer" in {
        val params = PostgresParamWriter.write(SqlSchema.long, 42L)
        assert(params.size == 1)
        val bytes = params(0).encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail("expected encoded int8 bytes")
        kyo.Abort.run(binaryRow(bytes).decode[Long]).eval match
            case kyo.Result.Success(v) => assert(v == 42L)
            case other                 => fail(s"Expected Success(42L) but got $other")
    }

    // == The narrow-Scala-type-over-wide-column mismatch table ==================
    //
    // Every extended-protocol result column is requested in binary format, so a Scala numeric type over a column of a
    // different numeric type is the DEFAULT path, not an edge. A read that took a byte count from the Scala type at
    // offset 0 would read the HIGH word of an `int8` for an `Int`, which is 0 for every value under 2^32, so
    // `SELECT count(*)` decoded into `Int` would return 0 for any non-huge count. One leaf per row of that table, each
    // asserting either the correct value or a typed failure, never a plausible wrong one.

    "Int over an int8 column reads the value, not the high word" in {
        // The canonical case: count(*), sum(int4), and array_length are all int8 in PostgreSQL.
        val row = pgRowCols(("count", encode(7L, PostgresEncoder.int8Binary), PostgresEncoder.OID_INT8))
        assert(reader(row).int() == 7, "an int8 column holding 7 must decode as 7, not as its high word 0")
    }

    "Int over an int8 column carrying a value beyond Int aborts typed instead of wrapping" in {
        val row = pgRowCols(("big", encode(4_294_967_296L, PostgresEncoder.int8Binary), PostgresEncoder.OID_INT8))
        val ex  = intercept[kyo.SqlDecodeValueRangeException](reader(row).int())
        assert(ex.scalaType == "Int", s"expected the Int target named, got ${ex.scalaType}")
        assert(ex.wireValue == "4294967296", s"expected the wire value reported, got ${ex.wireValue}")
    }

    "Short over an int4 column reads the value, not the high half" in {
        val row = pgRowCols(("n", encode(300, PostgresEncoder.int4Binary), PostgresEncoder.OID_INT4))
        assert(reader(row).short() == 300.toShort, "an int4 column holding 300 must decode as 300, not as its high half 0")
    }

    "Short over an int8 column carrying a value beyond Short aborts typed" in {
        val row = pgRowCols(("n", encode(70_000L, PostgresEncoder.int8Binary), PostgresEncoder.OID_INT8))
        val ex  = intercept[kyo.SqlDecodeValueRangeException](reader(row).short())
        assert(ex.scalaType == "Short", s"expected the Short target named, got ${ex.scalaType}")
        assert(ex.wireValue == "70000", s"expected the wire value reported, got ${ex.wireValue}")
    }

    "Long over an int4 column widens rather than reading past the span" in {
        // The auto-key path decodes a RETURNING payload as Long, and a SERIAL primary key returns int4.
        val row = pgRowCols(("id", encode(42, PostgresEncoder.int4Binary), PostgresEncoder.OID_INT4))
        assert(reader(row).long() == 42L, "an int4 RETURNING payload must widen to 42L")
    }

    "Float over a float8 column reads the double and rounds, not the high word of its bit pattern" in {
        val row = pgRowCols(("d", encode(1.5d, PostgresEncoder.float8Binary), PostgresEncoder.OID_FLOAT8))
        assert(reader(row).float() == 1.5f, "1.5d over a float8 column must read 1.5f, not the 1.9375f its high word spells")
    }

    "Double over a numeric column reads the numeric, not its header bytes as double bits" in {
        val row = pgRowCols(("n", encode(BigDecimal("2.5"), PostgresEncoder.numericBinary), PostgresEncoder.OID_NUMERIC))
        assert(reader(row).double() == 2.5d, "a numeric column holding 2.5 must decode as 2.5d")
    }

    "BigDecimal over an int8 column reads the integer, not a numeric header" in {
        // 42L parsed as a numeric header gives ndigits=0, dscale=42, and so the silent zero 0E-42.
        val row = pgRowCols(("v", encode(42L, PostgresEncoder.int8Binary), PostgresEncoder.OID_INT8))
        assert(reader(row).bigDecimal() == BigDecimal(42), "an int8 column holding 42 must decode as 42, not as 0E-42")
    }

    "BigInt over an int8 column reads the integer, not a numeric header" in {
        val row = pgRowCols(("v", encode(42L, PostgresEncoder.int8Binary), PostgresEncoder.OID_INT8))
        assert(reader(row).bigInt() == BigInt(42), "an int8 column holding 42 must decode as 42")
    }

    "BigInt over a fractional numeric column aborts typed instead of truncating" in {
        val row = pgRowCols(("n", encode(BigDecimal("2.5"), PostgresEncoder.numericBinary), PostgresEncoder.OID_NUMERIC))
        val ex  = intercept[kyo.SqlDecodeValueRangeException](reader(row).bigInt())
        assert(ex.scalaType == "BigInt", s"expected the BigInt target named, got ${ex.scalaType}")
        assert(ex.wireValue == "2.5", s"expected the wire value reported, got ${ex.wireValue}")
    }

    "Byte over an int2 column carrying a value beyond Byte aborts typed instead of wrapping" in {
        val row = pgRowCols(("b", encode(300.toShort, PostgresEncoder.int2Binary), PostgresEncoder.OID_INT2))
        val ex  = intercept[kyo.SqlDecodeValueRangeException](reader(row).byte())
        assert(ex.scalaType == "Byte", s"expected the Byte target named, got ${ex.scalaType}")
        assert(ex.wireValue == "300", s"expected the wire value reported, got ${ex.wireValue}")
    }

    "Byte over an int2 column inside Byte's range still decodes" in {
        val row = pgRowCols(("b", encode((-5).toShort, PostgresEncoder.int2Binary), PostgresEncoder.OID_INT2))
        assert(reader(row).byte() == (-5).toByte, "int2 is how a Byte field travels, so an in-range value must decode")
    }

    "Int over a fractional numeric column aborts typed instead of rounding" in {
        val row = pgRowCols(("n", encode(BigDecimal("3.7"), PostgresEncoder.numericBinary), PostgresEncoder.OID_NUMERIC))
        val ex  = intercept[kyo.SqlDecodeValueRangeException](reader(row).int())
        assert(ex.scalaType == "Int", s"expected the Int target named, got ${ex.scalaType}")
        assert(ex.wireValue == "3.7", s"expected the wire value reported, got ${ex.wireValue}")
    }

    "a text-format int8 too large for Int aborts typed rather than as an untyped number-format failure" in {
        val row = pgRow(Chunk(Maybe.Present(textBytes("5000000000"))), Chunk(fieldWithOid("n", PostgresEncoder.OID_INT8)), Format.Text)
        val ex  = intercept[kyo.SqlDecodeValueRangeException](reader(row).int())
        assert(ex.scalaType == "Int", s"expected the Int target named, got ${ex.scalaType}")
        assert(ex.wireValue == "5000000000", s"expected the wire value reported, got ${ex.wireValue}")
    }

    "the mismatch reads reach the caller as Abort, not as a throw escaping the codec" in {
        val row = pgRowCols(("big", encode(4_294_967_296L, PostgresEncoder.int8Binary), PostgresEncoder.OID_INT8))
        kyo.Abort.run[SqlDecodeException](row.decode[Int]).eval match
            case kyo.Result.Failure(e: kyo.SqlDecodeValueRangeException) => assert(e.scalaType == "Int")
            case other => fail(s"Expected Failure(SqlDecodeValueRangeException) but got $other")
    }

    "Int over a text column parses the rendering rather than reading the UTF-8 bytes as an integer" in {
        // A number stored in a text-family column is its own rendering in BOTH wire formats, so under Binary the bytes
        // are still ASCII. Reading four of them big-endian gives 0x34320000-shaped garbage.
        val row = pgRowCols(("n", textBytes("42"), PostgresEncoder.OID_TEXT))
        assert(reader(row).int() == 42, "the digits of a text column are its value")
    }

    "BigDecimal over a varchar column parses the rendering" in {
        val row = pgRowCols(("n", textBytes("12.34"), 1043))
        assert(reader(row).bigDecimal() == BigDecimal("12.34"))
    }

    "Double over an int8 column reads the integer, not its bytes as a double bit pattern" in {
        val row = pgRowCols(("v", encode(42L, PostgresEncoder.int8Binary), PostgresEncoder.OID_INT8))
        assert(reader(row).double() == 42.0d, "an int8 column holding 42 must decode as 42.0, not as 5.9e-323")
    }

    // A boolean read has to hand the decoder its column OID, or the binary arm answers off `bytes(0)`. A big-endian
    // `int4` holding 1 is `00 00 00 01`, whose leading byte is 0, so the same statement would answer false under the
    // extended protocol and true through `simpleQuery`, which reads the text rendering.

    "Boolean over an int4 column reads the value's truthiness, not its leading byte" in {
        val row = pgRowCols(("flag", encode(1, PostgresEncoder.int4Binary), PostgresEncoder.OID_INT4))
        assert(reader(row).boolean(), "an int4 column holding 1 must decode as true, not as its leading zero byte")
    }

    "Boolean over an int4 column holding zero is false" in {
        val row = pgRowCols(("flag", encode(0, PostgresEncoder.int4Binary), PostgresEncoder.OID_INT4))
        assert(!reader(row).boolean(), "an int4 column holding 0 must decode as false")
    }

    "Boolean over a numeric column reads the value, not its header bytes" in {
        // 0.5 as a numeric header has ndigits 1, whose big-endian first byte is 0, so the byte read said false.
        val row = pgRowCols(("n", encode(BigDecimal("0.5"), PostgresEncoder.numericBinary), PostgresEncoder.OID_NUMERIC))
        assert(reader(row).boolean(), "a numeric column holding 0.5 must decode as true")
    }

    "Boolean over a numeric column holding zero is false" in {
        val row = pgRowCols(("n", encode(BigDecimal("0.00"), PostgresEncoder.numericBinary), PostgresEncoder.OID_NUMERIC))
        assert(!reader(row).boolean(), "a numeric column holding 0.00 must decode as false")
    }

    "Boolean over a float8 column holding negative zero is false, not its sign bit" in {
        val row = pgRowCols(("d", encode(-0.0d, PostgresEncoder.float8Binary), PostgresEncoder.OID_FLOAT8))
        assert(!reader(row).boolean(), "-0.0 is zero, but its bit pattern's leading byte is 0x80")
    }

    "Boolean over a bool column still reads the single wire byte" in {
        val trueRow  = pgRowCols(("b", encode(true, PostgresEncoder.boolBinary), PostgresEncoder.OID_BOOL))
        val falseRow = pgRowCols(("b", encode(false, PostgresEncoder.boolBinary), PostgresEncoder.OID_BOOL))
        assert(reader(trueRow).boolean(), "a bool column holding true must decode as true")
        assert(!reader(falseRow).boolean(), "a bool column holding false must decode as false")
    }

    "a text-format int4 rendering answers the same as the binary read" in {
        val row = pgRow(Chunk(Maybe.Present(textBytes("2"))), Chunk(fieldWithOid("n", PostgresEncoder.OID_INT4)), Format.Text)
        assert(reader(row).boolean(), "simpleQuery renders an int4 2 as \"2\", which is nonzero and so true")
    }

    "a text-format bool rendering still reads as the bool it is" in {
        val trueRow  = pgRow(Chunk(Maybe.Present(textBytes("t"))), Chunk(fieldWithOid("b", PostgresEncoder.OID_BOOL)), Format.Text)
        val falseRow = pgRow(Chunk(Maybe.Present(textBytes("f"))), Chunk(fieldWithOid("b", PostgresEncoder.OID_BOOL)), Format.Text)
        assert(reader(trueRow).boolean(), "PostgreSQL renders a true bool as \"t\"")
        assert(!reader(falseRow).boolean(), "PostgreSQL renders a false bool as \"f\"")
    }

end PostgresRowReaderTest
