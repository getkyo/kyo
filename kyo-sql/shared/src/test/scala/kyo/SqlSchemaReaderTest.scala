package kyo

/** Contract tests for [[SqlCodec.Reader]]: what the abstract class guarantees to every implementation.
  *
  * Per-type decoding is covered by each backend row reader's own suite. This file pins that the reader is self-contained, the frame the
  * constructor threads, and the behaviours every implementation owes its caller: [[SqlCodec.Reader.nextExtension]] rejects a type another
  * dialect owns, `isNil` consumes the column it reports as NULL, and a read that does not match the column raises a decode failure rather
  * than coercing.
  */
class SqlSchemaReaderTest extends Test:

    private def readerOf(calls: SqlSchemaWriterMock.Call*): SqlSchemaReaderMock =
        SqlSchemaReaderMock.postgresMock(Chunk.from(calls))

    // The read half of the transport declares its own vocabulary and extends no document-serialization reader, for
    // the same reason the writer does not: the framing and field-iteration protocol are not part of a SQL backend's
    // contract.
    "a SqlCodec.Reader is not a document Codec.Reader" in {
        typeCheckFailure("val reader: Codec.Reader = SqlSchemaReaderMock.postgresMock(Chunk.empty)")
    }

    // The null contract both backends implement: a NULL answer consumes the column, a non-null answer leaves the
    // cursor for the value read that follows. A reader that peeked in both directions would shift every value after
    // a null by one column.
    "isNil consumes a NULL column and leaves a non-null one in place" in {
        val reader = readerOf(SqlSchemaWriterMock.Call.Nil, SqlSchemaWriterMock.Call.Int(42))
        assert(reader.isNil(), "the first column is NULL")
        assert(!reader.isNil(), "the second column is not, and stays unread")
        assert(reader.int() == 42)
    }

    "skip consumes one column unread" in {
        val reader = readerOf(SqlSchemaWriterMock.Call.Str("ignored"), SqlSchemaWriterMock.Call.Int(42))
        reader.skip()
        assert(reader.int() == 42)
    }

    "frame is the frame passed at construction" in {
        val capturedFrame = summon[Frame]
        val reader        = new SqlSchemaReaderMock(Chunk.empty, SqlSchemaWriterMock.postgres)(using capturedFrame)
        assert(reader.frame eq capturedFrame)
    }

    "nextExtension returns the column bytes and the format they are in" in {
        val bytes  = Span.from(Array[Byte](7, 8))
        val reader = readerOf(SqlSchemaWriterMock.Call.Extension("hstore", SqlCodec.Format.Binary, bytes))
        val ext    = reader.nextExtension(SqlSchemaWriterMock.postgres, "hstore")
        assert(ext.format == SqlCodec.Format.Binary)
        assert(ext.bytes.toArray.toSeq == Seq[Byte](7, 8))
    }

    "nextExtension reports a text column as text, so the read closure can branch" in {
        // Every column of a simple query arrives in text, so a channel that reported one format for both would
        // hand the closure bytes it parses as the other one.
        val bytes  = Span.from("192.168.1.1".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val reader = readerOf(SqlSchemaWriterMock.Call.Extension("inet", SqlCodec.Format.Text, bytes))
        assert(reader.nextExtension(SqlSchemaWriterMock.postgres, "inet").format == SqlCodec.Format.Text)
    }

    "nextExtension rejects a type another dialect owns" in {
        val reader = readerOf(SqlSchemaWriterMock.Call.Extension("hstore", SqlCodec.Format.Binary, Span.empty))
        val ex = intercept[SqlUnsupportedTypeOnBackendException] {
            val _ = reader.nextExtension(SqlSchemaWriterMock.mysql, "geometry")
        }
        assert(ex.dialect == SqlSchemaWriterMock.mysql)
        assert(ex.activeDialect == SqlSchemaWriterMock.postgres)
        assert(ex.typeName == "geometry")
    }

    "reading past the last column raises a decode failure" in {
        val reader = readerOf()
        val ex = intercept[SqlDecodeException] {
            val _ = reader.int()
        }
        assert(ex.getMessage.contains("0 recorded calls"))
    }

end SqlSchemaReaderTest
