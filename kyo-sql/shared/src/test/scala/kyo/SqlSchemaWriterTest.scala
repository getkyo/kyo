package kyo

/** Contract tests for [[SqlCodec.Writer]]: what the abstract class guarantees to every implementation.
  *
  * The vocabulary methods' behaviour is covered by `SqlSchemaWriterVocabularyTest` (codec-level) and by each backend writer's own suite
  * (byte-level). This file pins that the writer is self-contained, the frame the constructor threads, and the one behaviour
  * [[SqlCodec.Writer.extension]] requires of every implementation: reject a payload another dialect owns.
  */
class SqlSchemaWriterTest extends Test:

    // The SQL transport declares its own vocabulary and extends no document-serialization writer, which is what lets
    // a backend implement exactly the SQL methods and nothing else. A supertype relationship here would put the
    // framing and field-iteration protocol back in every backend's contract.
    "a SqlCodec.Writer is not a document Codec.Writer" in {
        typeCheckFailure("val writer: Codec.Writer = SqlSchemaWriterMock.postgresMock")
    }

    "frame is the frame passed at construction" in {
        val capturedFrame = summon[Frame]
        val writer        = new SqlSchemaWriterMock(SqlSchemaWriterMock.postgres)(using capturedFrame)
        assert(writer.frame eq capturedFrame)
    }

    "extension accepts a payload the writer's own dialect owns" in {
        val mock  = SqlSchemaWriterMock.postgresMock
        val bytes = Span.from(Array[Byte](1, 2, 3))
        mock.extension(SqlCodec.Writer.Payload(SqlSchemaWriterMock.postgres, "hstore", SqlCodec.Format.Binary, bytes))
        mock.onlyCall match
            case Maybe.Present(SqlSchemaWriterMock.Call.Extension(typeName, format, recorded)) =>
                assert(typeName == "hstore")
                assert(format == SqlCodec.Format.Binary)
                assert(recorded.toArray.toSeq == Seq[Byte](1, 2, 3))
            case other => fail(s"expected one recorded extension call, got $other")
        end match
    }

    "extension carries the payload's format through to the recorded call" in {
        // The format is the payload author's statement about the bytes, so a text payload must arrive as one:
        // a channel that dropped it would leave every reader guessing, which is what the field exists to stop.
        val mock  = SqlSchemaWriterMock.postgresMock
        val bytes = Span.from("192.168.1.1".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        mock.extension(SqlCodec.Writer.Payload(SqlSchemaWriterMock.postgres, "inet", SqlCodec.Format.Text, bytes))
        mock.onlyCall match
            case Maybe.Present(SqlSchemaWriterMock.Call.Extension(_, format, _)) =>
                assert(format == SqlCodec.Format.Text)
            case other => fail(s"expected one recorded extension call, got $other")
        end match
    }

    "extension rejects a payload another dialect owns with the type and both dialects named" in {
        val mock = SqlSchemaWriterMock.mysqlMock
        val ex = intercept[SqlUnsupportedTypeOnBackendException] {
            mock.extension(SqlCodec.Writer.Payload(SqlSchemaWriterMock.postgres, "hstore", SqlCodec.Format.Binary, Span.empty))
        }
        assert(ex.dialect == SqlSchemaWriterMock.postgres)
        assert(ex.activeDialect == SqlSchemaWriterMock.mysql)
        assert(ex.typeName == "hstore")
        assert(mock.calls.isEmpty)
    }

    "encodeElement records the format the composite demanded" in {
        // A composite's byte layout fixes its elements' format, so the demand is part of the call rather than
        // whatever the writer would have chosen on its own.
        val mock   = SqlSchemaWriterMock.postgresMock
        val column = summon[SqlSchema.Column[Int]]
        val _      = mock.encodeElement(column, 7, "Int", SqlCodec.Format.Binary)
        val _      = mock.encodeElement(column, 8, "Int", SqlCodec.Format.Text)
        assert(mock.elementFormats == Chunk(SqlCodec.Format.Binary, SqlCodec.Format.Text))
        assert(mock.elementCalls == Chunk(Chunk(SqlSchemaWriterMock.Call.Int(7)), Chunk(SqlSchemaWriterMock.Call.Int(8))))
        assert(mock.calls.isEmpty, "an element is not a column of its own")
    }

end SqlSchemaWriterTest
