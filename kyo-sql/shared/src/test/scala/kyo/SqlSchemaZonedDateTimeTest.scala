package kyo

import kyo.SqlDecodeException

/** Unit tests for [[SqlSchema]] `java.time.ZonedDateTime`.
  *
  * The column writes `w.instant(v.toInstant)` and reads the instant back at `ZoneOffset.UTC`, so the IANA zone a value carried is NOT stored.
  * That is the documented design choice of the single-column given, and every leaf here asserts it explicitly: an application that needs the
  * original zone stores it as a field of its own.
  *
  * Which column each backend puts the instant in lives in `kyo/internal/postgres/types/PostgresEncoderZonedDateTimeTest.scala` and
  * `kyo/internal/mysql/types/MysqlEncoderZonedDateTimeTest.scala`.
  */
class SqlSchemaZonedDateTimeTest extends Test:

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

    /** Reads an `A` back from `calls` through the same catch the row codec applies in production. */
    private def decoded[A](calls: Chunk[SqlSchemaWriterMock.Call])(using s: SqlSchema[A], f: Frame): Result[SqlDecodeException, A] =
        Abort.run(SqlRow.Codec.catching(s.read(SqlSchemaReaderMock.postgresMock(calls)))).eval

    // ── summon ─────────────────────────────────────────────────────────────────

    "summon SqlSchema[ZonedDateTime] compiles" in {
        val s: SqlSchema.Column[java.time.ZonedDateTime] = summon[SqlSchema.Column[java.time.ZonedDateTime]]
        assert(s.width == 1)
    }

    // ── write: one instant column, the zone does not reach the wire ────────────

    "ZonedDateTime writes one instant column" in {
        val zdt = java.time.ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, java.time.ZoneId.of("America/New_York"))
        assert(written(zdt) == Chunk(SqlSchemaWriterMock.Call.Instant(zdt.toInstant)))
    }

    "two ZonedDateTimes at the same instant write the same column, whatever their zones" in {
        val zdt1 = java.time.ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        val zdt2 = java.time.ZonedDateTime.of(2024, 1, 1, 21, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo"))
        assert(zdt1.toInstant.equals(zdt2.toInstant))
        assert(written(zdt1) == written(zdt2))
    }

    // ── read: the value comes back at UTC, zone loss is the documented design ──

    "ZonedDateTime decodes at UTC, the original zone is lost" in {
        val original = java.time.ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, java.time.ZoneId.of("America/New_York"))
        val decoded  = roundTrip(original)
        assert(decoded.getZone.equals(java.time.ZoneOffset.UTC), "the zone must always come back as UTC")
        assert(decoded.toInstant.equals(original.toInstant), "the instant must be preserved")
    }

    "a Europe/Paris value also decodes at UTC" in {
        val original = java.time.ZonedDateTime.of(2023, 12, 31, 16, 0, 0, 0, java.time.ZoneId.of("Europe/Paris"))
        val decoded  = roundTrip(original)
        assert(decoded.getZone.equals(java.time.ZoneOffset.UTC))
        assert(decoded.toInstant.equals(original.toInstant))
    }

    "a ZonedDateTime already at UTC round-trips exactly" in {
        val original = java.time.ZonedDateTime.of(2024, 3, 14, 15, 9, 26, 535_000_000, java.time.ZoneOffset.UTC)
        assert(roundTrip(original).equals(original))
    }

    "a non-UTC zone normalizes to UTC at the same instant, shifting the local time" in {
        val original = java.time.ZonedDateTime.of(2022, 8, 1, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo"))
        val decoded  = roundTrip(original)
        assert(decoded.getZone.equals(java.time.ZoneOffset.UTC))
        assert(decoded.toInstant.equals(original.toInstant)) // 2022-08-01T00:00:00Z, Tokyo is UTC+9
        assert(decoded.getHour == 0, "09:00 Asia/Tokyo becomes 00:00 UTC")
    }

    // ── case class carrying a ZonedDateTime field ──────────────────────────────

    "a record with a ZonedDateTime field writes its columns in declaration order" in {
        val zdt    = java.time.ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC)
        val record = SqlSchemaZonedDateTimeRecord(42L, zdt)
        assert(written(record) == Chunk(SqlSchemaWriterMock.Call.Long(42L), SqlSchemaWriterMock.Call.Instant(zdt.toInstant)))
    }

    "a record with a ZonedDateTime field round-trips, the field at UTC" in {
        val zdt     = java.time.ZonedDateTime.of(2025, 6, 21, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        val decoded = roundTrip(SqlSchemaZonedDateTimeRecord(99L, zdt))
        assert(decoded.id == 99L)
        assert(decoded.ts.getZone.equals(java.time.ZoneOffset.UTC))
        assert(decoded.ts.toInstant.equals(zdt.toInstant))
    }

    // ── the zone is application data, not a column ────────────────────────────
    //
    // An application that needs the original IANA zone back declares a field for it, which is a row of two
    // ordinary columns rather than a variant encoding of one type.

    "a record storing the zone id alongside the instant recovers the original zone" in {
        val original = java.time.ZonedDateTime.parse("2026-05-22T10:30:00+02:00[Europe/Paris]")
        val stored   = SqlSchemaZonedDateTimeAtZone(original.toInstant, original.getZone.getId)
        assert(
            written(stored) == Chunk(
                SqlSchemaWriterMock.Call.Instant(original.toInstant),
                SqlSchemaWriterMock.Call.Str("Europe/Paris")
            )
        )
        val decoded = roundTrip(stored)
        assert(decoded.zoneId == "Europe/Paris")
        assert(java.time.ZonedDateTime.ofInstant(decoded.instant, java.time.ZoneId.of(decoded.zoneId)).equals(original))
    }

    "an Asia/Tokyo value stored the same way keeps its zone id" in {
        val original = java.time.ZonedDateTime.parse("2026-05-22T18:30:00+09:00[Asia/Tokyo]")
        val decoded  = roundTrip(SqlSchemaZonedDateTimeAtZone(original.toInstant, original.getZone.getId))
        assert(decoded.instant.equals(original.toInstant))
        assert(decoded.zoneId == "Asia/Tokyo")
    }

    // A stored zone id is ordinary text, so a value the zone database does not know reaches the application
    // rather than the decode: nothing in the column codec parses it.
    "an unknown zone id decodes as the text it is, and fails where it is resolved" in {
        val calls = Chunk(
            SqlSchemaWriterMock.Call.Instant(java.time.Instant.parse("2026-05-22T10:30:00Z")),
            SqlSchemaWriterMock.Call.Str("Not/A_Real/Zone")
        )
        decoded[SqlSchemaZonedDateTimeAtZone](calls) match
            case Result.Success(v) =>
                assert(v.zoneId == "Not/A_Real/Zone")
                assert(Result.catching[Exception](java.time.ZoneId.of(v.zoneId)).isFailure)
            case other => fail(s"expected the row to decode, got $other")
        end match
    }

end SqlSchemaZonedDateTimeTest

/** Top-level case class for the record round-trip leaves: every field is a single column, so the row derives. */
case class SqlSchemaZonedDateTimeRecord(id: Long, ts: java.time.ZonedDateTime) derives SqlSchema

/** The two-column shape an application uses when it needs the original zone back: the instant and the IANA zone it was observed in. */
case class SqlSchemaZonedDateTimeAtZone(instant: java.time.Instant, zoneId: String) derives SqlSchema
