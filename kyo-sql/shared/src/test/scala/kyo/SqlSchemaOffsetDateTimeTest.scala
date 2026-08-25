package kyo

/** Unit tests for [[SqlSchema]] `java.time.OffsetDateTime`.
  *
  * The column writes `w.instant(v.toInstant)` and reads the instant back at `ZoneOffset.UTC`, so the offset a value carried is NOT stored.
  * That is the documented design choice of the single-column given, and every leaf here asserts it explicitly: `timestamptz` and `DATETIME`
  * persist an instant, and an application that needs the original offset stores it as a field of its own.
  *
  * Which column each backend puts the instant in lives in `kyo/internal/postgres/types/PostgresEncoderOffsetDateTimeTest.scala` and
  * `kyo/internal/mysql/types/MysqlEncoderOffsetDateTimeTest.scala`.
  */
class SqlSchemaOffsetDateTimeTest extends Test:

    // ── helpers ────────────────────────────────────────────────────────────────

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

    // ── summon ─────────────────────────────────────────────────────────────────

    "summon SqlSchema[OffsetDateTime] compiles" in {
        val s: SqlSchema.Column[java.time.OffsetDateTime] = summon[SqlSchema.Column[java.time.OffsetDateTime]]
        assert(s.width == 1)
    }

    // ── write: one instant column, the offset does not reach the wire ──────────

    "OffsetDateTime writes one instant column" in {
        val odt = java.time.OffsetDateTime.of(2024, 6, 15, 10, 30, 0, 0, java.time.ZoneOffset.ofHours(5))
        assert(written(odt) == Chunk(SqlSchemaWriterMock.Call.Instant(odt.toInstant)))
    }

    "two OffsetDateTimes at the same instant write the same column, whatever their offsets" in {
        val odt1 = java.time.OffsetDateTime.of(2024, 1, 1, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        val odt2 = java.time.OffsetDateTime.of(2024, 1, 1, 17, 0, 0, 0, java.time.ZoneOffset.ofHours(5))
        assert(odt1.toInstant.equals(odt2.toInstant))
        assert(written(odt1) == written(odt2))
    }

    // ── read: the value comes back at UTC, offset loss is the documented design ─

    "OffsetDateTime decodes at UTC, the original offset is lost" in {
        val original = java.time.OffsetDateTime.of(2024, 6, 15, 10, 30, 0, 0, java.time.ZoneOffset.ofHours(5))
        val decoded  = roundTrip(original)
        assert(decoded.getOffset.equals(java.time.ZoneOffset.UTC), "the offset must always come back as UTC")
        assert(decoded.toInstant.equals(original.toInstant), "the instant must be preserved")
    }

    "a negative offset also decodes at UTC" in {
        val original = java.time.OffsetDateTime.of(2023, 12, 31, 16, 0, 0, 0, java.time.ZoneOffset.ofHours(-8))
        val decoded  = roundTrip(original)
        assert(decoded.getOffset.equals(java.time.ZoneOffset.UTC))
        assert(decoded.toInstant.equals(original.toInstant))
    }

    "an OffsetDateTime already at UTC round-trips exactly" in {
        val original = java.time.OffsetDateTime.of(2024, 3, 14, 15, 9, 26, 535_000_000, java.time.ZoneOffset.UTC)
        assert(roundTrip(original).equals(original))
    }

    "a non-UTC offset normalizes to UTC at the same instant, shifting the local time" in {
        val original = java.time.OffsetDateTime.of(2022, 8, 1, 9, 0, 0, 0, java.time.ZoneOffset.ofHours(3))
        val decoded  = roundTrip(original)
        assert(decoded.getOffset.equals(java.time.ZoneOffset.UTC))
        assert(decoded.toInstant.equals(original.toInstant)) // 2022-08-01T06:00:00Z
        assert(decoded.getHour == 6, "09:00+03:00 becomes 06:00+00:00")
    }

    // ── case class carrying an OffsetDateTime field ────────────────────────────

    "a record with an OffsetDateTime field writes its columns in declaration order" in {
        val odt    = java.time.OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC)
        val record = SqlSchemaOffsetDateTimeRecord(42L, odt)
        assert(written(record) == Chunk(SqlSchemaWriterMock.Call.Long(42L), SqlSchemaWriterMock.Call.Instant(odt.toInstant)))
    }

    "a record with an OffsetDateTime field round-trips, the field at UTC" in {
        val odt     = java.time.OffsetDateTime.of(2025, 6, 21, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        val record  = SqlSchemaOffsetDateTimeRecord(99L, odt)
        val decoded = roundTrip(record)
        assert(decoded.id == 99L)
        assert(decoded.ts.getOffset.equals(java.time.ZoneOffset.UTC))
        assert(decoded.ts.toInstant.equals(odt.toInstant))
    }

    // ── the offset is application data, not a column ──────────────────────────
    //
    // An application that needs the original offset back declares a field for it, which is a row of two
    // ordinary columns rather than a variant encoding of one type.

    "a record storing the offset alongside the instant recovers the original offset" in {
        val original = java.time.OffsetDateTime.parse("2026-05-22T10:30:00+05:30")
        val stored   = SqlSchemaOffsetDateTimeAtOffset(original.toInstant, original.getOffset.getTotalSeconds)
        assert(
            written(stored) == Chunk(
                SqlSchemaWriterMock.Call.Instant(original.toInstant),
                SqlSchemaWriterMock.Call.Int(5 * 3600 + 30 * 60)
            )
        )
        val decoded = roundTrip(stored)
        assert(decoded.offsetSeconds == 5 * 3600 + 30 * 60)
        assert(
            java.time.OffsetDateTime.ofInstant(decoded.instant, java.time.ZoneOffset.ofTotalSeconds(decoded.offsetSeconds))
                .equals(original)
        )
    }

    "a negative offset stored the same way comes back negative" in {
        val original = java.time.OffsetDateTime.parse("2026-05-22T03:15:00-08:00")
        val decoded  = roundTrip(SqlSchemaOffsetDateTimeAtOffset(original.toInstant, original.getOffset.getTotalSeconds))
        assert(decoded.instant.equals(original.toInstant))
        assert(decoded.offsetSeconds == -8 * 3600)
    }

end SqlSchemaOffsetDateTimeTest

/** Top-level case class for the record round-trip leaves: every field is a single column, so the row derives. */
case class SqlSchemaOffsetDateTimeRecord(id: Long, ts: java.time.OffsetDateTime) derives SqlSchema

/** The two-column shape an application uses when it needs the original offset back: the instant and the offset it was observed at. */
case class SqlSchemaOffsetDateTimeAtOffset(instant: java.time.Instant, offsetSeconds: Int) derives SqlSchema
