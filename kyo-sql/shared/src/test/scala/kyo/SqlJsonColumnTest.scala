package kyo

/** Unit tests for [[Sql.jsonColumn]], the escape that stores a value of any shape as one JSON document column.
  *
  * The factory takes the two functions that produce and consume the document text, so the JSON library is the caller's choice; the leaves
  * below use kyo-schema as one such library, which is the shape a downstream user writes. What kyo-sql owns is the column: the text reaches
  * the writer through the `json` vocabulary and comes back through `nextJson`, and a `decode` that throws is reported as a typed
  * [[SqlDecodeJsonException]] rather than escaping as whatever the library raised.
  *
  * The same round-trip against the real PostgreSQL writer and reader lives in `kyo/internal/postgres/types/PostgresEncoderJsonTest.scala`,
  * which is where a backend may be named.
  */
class SqlJsonColumnTest extends Test:

    private def written[A](value: A)(using s: SqlSchema[A]): Chunk[SqlSchemaWriterMock.Call] =
        recording(value).calls

    /** Writes `value` into a fresh recording writer and returns it. */
    private def recording[A](value: A)(using s: SqlSchema[A]): SqlSchemaWriterMock =
        val writer = SqlSchemaWriterMock.postgresMock
        s.write(value, writer)
        writer
    end recording

    private def roundTrip[A](value: A)(using s: SqlSchema[A]): A =
        s.read(SqlSchemaReaderMock.replaying(recording(value)))

    /** Reads an `A` from `calls`, through the same catch the row codec applies in production. */
    private def decoded[A](calls: SqlSchemaWriterMock.Call*)(using s: SqlSchema[A], f: Frame): Result[SqlDecodeException, A] =
        Abort.run(SqlRow.Codec.catching(s.read(SqlSchemaReaderMock.postgresMock(Chunk.from(calls))))).eval

    "jsonColumn occupies one column, and is a Column so it can be bound" in {
        val column: SqlSchema.Column[SqlJsonColumnDoc] = SqlJsonColumnDoc.column
        assert(column.width == 1)
        assert(column.fieldNames == Chunk.empty[String])
    }

    "jsonColumn writes one JSON column holding the encoded document" in {
        given SqlSchema.Column[SqlJsonColumnDoc] = SqlJsonColumnDoc.column
        val original                             = SqlJsonColumnDoc("ada", 42)
        assert(written(original) == Chunk(SqlSchemaWriterMock.Call.Json("""{"name":"ada","count":42}""")))
        assert(roundTrip(original) == original)
    }

    "jsonColumn round-trips every variant of a sum type" in {
        given SqlSchema.Column[SqlJsonColumnPayload] = SqlJsonColumnPayload.column
        val cases = Seq[SqlJsonColumnPayload](
            SqlJsonColumnSuccess("ok"),
            SqlJsonColumnFailure(500, "boom")
        )
        cases.foreach { original =>
            val calls = written(original)
            assert(calls.size == 1, s"expected one json column for $original")
            assert(roundTrip(original) == original, s"round-trip mismatch for $original")
        }
        succeed
    }

    // The caller's `decode` signals failure however its library does; the column is what turns that into the
    // typed decode leaf the run path promises, instead of letting the library's own exception escape.
    "a decode that throws surfaces as SqlDecodeJsonException" in {
        given SqlSchema.Column[SqlJsonColumnDoc] = SqlJsonColumnDoc.column
        decoded[SqlJsonColumnDoc](SqlSchemaWriterMock.Call.Json("{not json at all")) match
            case Result.Failure(_: SqlDecodeJsonException) => succeed
            case other                                     => fail(s"Expected Failure(SqlDecodeJsonException) but got $other")
    }

    // A row field wants a Column, and jsonColumn produces one, so a document nests inside a case class as an
    // ordinary field: the row writes its other columns and the document as one more.
    "a jsonColumn field nests inside a derived row" in {
        val row = SqlJsonColumnRow(7L, SqlJsonColumnDoc("ada", 42))
        assert(
            written(row) == Chunk(
                SqlSchemaWriterMock.Call.Long(7L),
                SqlSchemaWriterMock.Call.Json("""{"name":"ada","count":42}""")
            )
        )
        assert(roundTrip(row) == row)
    }

end SqlJsonColumnTest

/** A product stored as one document. The `column` is a plain `def` rather than a given so each leaf installs it deliberately, except in
  * [[SqlJsonColumnRow]] where the field needs it resolved.
  */
case class SqlJsonColumnDoc(name: String, count: Int) derives Schema, CanEqual

object SqlJsonColumnDoc:
    def column: SqlSchema.Column[SqlJsonColumnDoc] =
        Sql.jsonColumn[SqlJsonColumnDoc](v => Json.encode(v))(text => Json.decode[SqlJsonColumnDoc](text).getOrThrow)
end SqlJsonColumnDoc

sealed trait SqlJsonColumnPayload derives CanEqual
case class SqlJsonColumnSuccess(msg: String)               extends SqlJsonColumnPayload
case class SqlJsonColumnFailure(code: Int, reason: String) extends SqlJsonColumnPayload

object SqlJsonColumnPayload:
    given Schema[SqlJsonColumnPayload] = Schema.derived

    def column: SqlSchema.Column[SqlJsonColumnPayload] =
        Sql.jsonColumn[SqlJsonColumnPayload](v => Json.encode(v))(text => Json.decode[SqlJsonColumnPayload](text).getOrThrow)
end SqlJsonColumnPayload

/** A row carrying a document field, for the nesting leaf. */
case class SqlJsonColumnRow(id: Long, doc: SqlJsonColumnDoc) derives CanEqual

object SqlJsonColumnRow:
    given SqlSchema.Column[SqlJsonColumnDoc] = SqlJsonColumnDoc.column
    given SqlSchema[SqlJsonColumnRow]        = SqlSchema.derived
end SqlJsonColumnRow
