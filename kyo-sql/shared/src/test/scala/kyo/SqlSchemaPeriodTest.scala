package kyo

/** Unit tests for [[SqlSchema]] `java.time.Period`, the calendar-interval column.
  *
  * A Period occupies one column and reaches the writer as `w.calendarInterval(v)`, which each backend maps to its own form: PostgreSQL to a
  * native `INTERVAL`, MySQL to ISO-8601 text. This file pins the backend-blind half, that the schema writes one calendar-interval column and
  * reads it back, including the years-into-months decomposition the type performs.
  *
  * Wire-level tests live in `kyo/internal/postgres/PostgresEncoderIntervalTest.scala` (the `(µs, days, months)` layout) and
  * `kyo/internal/mysql/types/MysqlEncoderPeriodTest.scala` (the ISO-8601 string codec).
  */
class SqlSchemaPeriodTest extends Test:

    given CanEqual[java.time.Period, java.time.Period] = CanEqual.canEqualAny

    private def written(period: java.time.Period): Chunk[SqlSchemaWriterMock.Call] =
        recording(period).calls

    private def recording(period: java.time.Period): SqlSchemaWriterMock =
        val writer = SqlSchemaWriterMock.postgresMock
        summon[SqlSchema[java.time.Period]].write(period, writer)
        writer
    end recording

    private def roundTrip(period: java.time.Period): java.time.Period =
        summon[SqlSchema[java.time.Period]].read(SqlSchemaReaderMock.replaying(recording(period)))

    "summon SqlSchema[Period] compiles" in {
        val s: SqlSchema.Column[java.time.Period] = summon[SqlSchema.Column[java.time.Period]]
        assert(s.width == 1)
        assert(summon[SqlType[java.time.Period]].columnType == SqlType.Type.CalendarInterval)
    }

    "Period writes exactly one calendar-interval column" in {
        assert(written(java.time.Period.of(1, 6, 15)) == Chunk(SqlSchemaWriterMock.Call.CalendarInterval(java.time.Period.of(1, 6, 15))))
    }

    "Period round-trips through the calendar-interval column" in {
        val cases = Seq(
            java.time.Period.of(1, 6, 15),
            java.time.Period.of(-2, -5, -20),
            java.time.Period.ofMonths(13),
            java.time.Period.ofDays(5),
            java.time.Period.ZERO
        )
        cases.foreach(original => assert(roundTrip(original) == original, s"round-trip mismatch for $original"))
        succeed
    }

    // A Period's years and months collapse into a single months count on every backend, so the
    // decomposition is the schema's semantic, not a wire detail: 1 year 6 months is 18 months.

    "Period carries years as months, 1 year 6 months is 18 total months" in {
        val period = java.time.Period.of(1, 6, 15)
        assert(period.toTotalMonths == 18L)
        assert(roundTrip(period).toTotalMonths == 18L)
    }

    "a negative Period keeps its sign through the column" in {
        val period  = java.time.Period.of(-1, -3, -10)
        val decoded = roundTrip(period)
        assert(decoded.toTotalMonths == -15L)
        assert(decoded.getDays == -10)
    }

end SqlSchemaPeriodTest
