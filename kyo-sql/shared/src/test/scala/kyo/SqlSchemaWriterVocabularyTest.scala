package kyo

/** Exercises every method of the [[SqlCodec.Writer]] and [[SqlCodec.Reader]] SQL type vocabulary.
  *
  * The vocabulary is the whole point of the transport: a schema names the SQL type a value IS, and each backend decides how to spell it. So
  * every method is driven here through a recording writer and a replaying reader, and every one carries its value through unchanged. The
  * per-backend spellings are pinned by the backend writers' and readers' own suites.
  */
class SqlSchemaWriterVocabularyTest extends Test:

    private def writer = SqlSchemaWriterMock.postgresMock

    "json carries the document text" in {
        val w = writer
        w.json("""{"a":1}""")
        assert(w.calls == Chunk(SqlSchemaWriterMock.Call.Json("""{"a":1}""")))
        assert(SqlSchemaReaderMock.postgresMock(w.calls).nextJson() == """{"a":1}""")
    }

    "uuid carries the value" in {
        val value = java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val w     = writer
        w.uuid(value)
        assert(w.calls == Chunk(SqlSchemaWriterMock.Call.Uuid(value)))
        assert(SqlSchemaReaderMock.postgresMock(w.calls).nextUuid().equals(value))
    }

    "date carries the calendar date" in {
        val value = java.time.LocalDate.of(2026, 5, 5)
        val w     = writer
        w.date(value)
        assert(w.calls == Chunk(SqlSchemaWriterMock.Call.Date(value)))
        assert(SqlSchemaReaderMock.postgresMock(w.calls).nextDate().equals(value))
    }

    "time carries the time of day" in {
        val value = java.time.LocalTime.of(13, 45, 30, 123_000_000)
        val w     = writer
        w.time(value)
        assert(w.calls == Chunk(SqlSchemaWriterMock.Call.Time(value)))
        assert(SqlSchemaReaderMock.postgresMock(w.calls).nextTime().equals(value))
    }

    "timeWithOffset carries the time and its offset" in {
        val value = java.time.OffsetTime.of(13, 45, 30, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30))
        val w     = writer
        w.timeWithOffset(value)
        assert(w.calls == Chunk(SqlSchemaWriterMock.Call.TimeWithOffset(value)))
        assert(SqlSchemaReaderMock.postgresMock(w.calls).nextTimeWithOffset().equals(value))
    }

    "dateTime carries the wall-clock date and time" in {
        val value = java.time.LocalDateTime.of(2026, 5, 5, 12, 30, 0)
        val w     = writer
        w.dateTime(value)
        assert(w.calls == Chunk(SqlSchemaWriterMock.Call.DateTime(value)))
        assert(SqlSchemaReaderMock.postgresMock(w.calls).nextDateTime().equals(value))
    }

    "calendarInterval carries the months and days" in {
        val value = java.time.Period.of(1, 6, 15)
        val w     = writer
        w.calendarInterval(value)
        assert(w.calls == Chunk(SqlSchemaWriterMock.Call.CalendarInterval(value)))
        assert(SqlSchemaReaderMock.postgresMock(w.calls).nextCalendarInterval().equals(value))
    }

    "arrayOfInt carries the elements in order" in {
        val values = Chunk(1, -2, 3)
        val w      = writer
        w.arrayOfInt(values)
        assert(w.calls == Chunk(SqlSchemaWriterMock.Call.ArrayOfInt(values)))
        assert(SqlSchemaReaderMock.postgresMock(w.calls).nextArrayOfInt() == values)
    }

    "arrayOfString carries the elements in order" in {
        val values = Chunk("a", "", "ç")
        val w      = writer
        w.arrayOfString(values)
        assert(w.calls == Chunk(SqlSchemaWriterMock.Call.ArrayOfString(values)))
        assert(SqlSchemaReaderMock.postgresMock(w.calls).nextArrayOfString() == values)
    }

    "arrayOfJson carries one document per element" in {
        val values = Chunk("""{"a":1}""", "null")
        val w      = writer
        w.arrayOfJson(values)
        assert(w.calls == Chunk(SqlSchemaWriterMock.Call.ArrayOfJson(values)))
        assert(SqlSchemaReaderMock.postgresMock(w.calls).nextArrayOfJson() == values)
    }

    "extension carries the dialect's own bytes, and their format, under the type name" in {
        val bytes = Span.from(Array[Byte](1, 2, 3))
        val w     = writer
        w.extension(SqlCodec.Writer.Payload(SqlSchemaWriterMock.postgres, "hstore", SqlCodec.Format.Binary, bytes))
        w.onlyCall match
            case Maybe.Present(SqlSchemaWriterMock.Call.Extension(typeName, format, recorded)) =>
                assert(typeName == "hstore")
                assert(format == SqlCodec.Format.Binary)
                assert(recorded.toArray.toSeq == Seq[Byte](1, 2, 3))
            case other => fail(s"expected one extension call, got $other")
        end match
        val ext = SqlSchemaReaderMock.postgresMock(w.calls).nextExtension(SqlSchemaWriterMock.postgres, "hstore")
        assert(ext.format == SqlCodec.Format.Binary)
        assert(ext.bytes.toArray.toSeq == Seq[Byte](1, 2, 3))
    }

    "a reader asked for a different SQL type than the column carries fails rather than coercing" in {
        val w = writer
        w.date(java.time.LocalDate.of(2026, 5, 5))
        val reader = SqlSchemaReaderMock.postgresMock(w.calls)
        val ex = intercept[kyo.SqlDecodeException] {
            val _ = reader.nextDateTime()
        }
        assert(ex.getMessage.contains("date and time"), s"the failure should name the type asked for: ${ex.getMessage}")
    }

end SqlSchemaWriterVocabularyTest
