package kyo

/** Unit tests for [[JsonText]], the single-column carrier for a JSON document, and for `Chunk[JsonText]`, its array counterpart.
  *
  * `JsonText` reaches the writer's `json` (or `arrayOfJson`) vocabulary and comes back through `nextJson`, so the server sees a native JSON
  * column rather than text. kyo-sql imposes no JSON library: the text is produced by whatever encoder the caller has, which is what the
  * kyo-schema leaves below stand in for. Which column type each backend then uses is the backend's decision, so this file asserts the
  * vocabulary calls and the round-trip, not bytes.
  *
  * Wire-level JSON tests live in:
  *   - `kyo/internal/postgres/types/PostgresEncoderJsonTest.scala`, PG JSONB binary + JSON text, including the OIDs this column reaches
  *   - `kyo/internal/mysql/types/MysqlEncoderJsonTest.scala`, the MySQL JSON encoder and decoder, including the type bytes it reaches
  */
class JsonTextTest extends Test:

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

    "summon SqlSchema[JsonText] compiles" in {
        val s: SqlSchema.Column[JsonText] = summon[SqlSchema.Column[JsonText]]
        assert(s.width == 1)
        assert(summon[SqlType[JsonText]].columnType == SqlType.Type.Json)
    }

    "JsonText writes one JSON column holding the document text" in {
        val doc = JsonText("""{"x":1,"y":true,"z":null}""")
        assert(written(doc) == Chunk(SqlSchemaWriterMock.Call.Json("""{"x":1,"y":true,"z":null}""")))
    }

    "JsonText round-trips through the JSON column" in {
        val doc = JsonText("""{"mysql":true,"n":42}""")
        assert(roundTrip(doc) == doc)
        assert(roundTrip(doc).text == """{"mysql":true,"n":42}""")
    }

    // The document text is passed through untouched: nothing here parses, re-serialises, or re-orders it, which is
    // what lets any encoder produce it and the server be the one that validates it.
    "the text an encoder produced reaches the column unchanged" in {
        val value = Structure.Value.Record(Chunk(
            "x" -> Structure.Value.Integer(1),
            "y" -> Structure.Value.Bool(true),
            "z" -> Structure.Value.Null
        ))
        val encoded = Json.encode(value)
        assert(written(JsonText(encoded)) == Chunk(SqlSchemaWriterMock.Call.Json(encoded)))
        assert(Json.decode[Structure.Value](roundTrip(JsonText(encoded)).text).getOrThrow == value)
    }

    "summon SqlSchema[Chunk[JsonText]] compiles" in {
        val s: SqlSchema.Column[Chunk[JsonText]] = summon[SqlSchema.Column[Chunk[JsonText]]]
        assert(s.width == 1)
        assert(summon[SqlType[Chunk[JsonText]]].columnType == SqlType.Type.Array(SqlType.Type.Json))
    }

    "Chunk[JsonText] writes one array-of-JSON column, one document per element" in {
        val input = Chunk(JsonText("""{"a":1}"""), JsonText("""{"b":2}"""))
        assert(written(input) == Chunk(SqlSchemaWriterMock.Call.ArrayOfJson(Chunk("""{"a":1}""", """{"b":2}"""))))
    }

    "Chunk[JsonText] round-trips through the array-of-JSON column" in {
        val input = Chunk(JsonText("""{"a":1}"""), JsonText("null"))
        assert(roundTrip(input) == input)
    }

    "an empty Chunk[JsonText] writes an empty array column" in {
        assert(written(Chunk.empty[JsonText]) == Chunk(SqlSchemaWriterMock.Call.ArrayOfJson(Chunk.empty[String])))
        assert(roundTrip(Chunk.empty[JsonText]) == Chunk.empty[JsonText])
    }

end JsonTextTest
