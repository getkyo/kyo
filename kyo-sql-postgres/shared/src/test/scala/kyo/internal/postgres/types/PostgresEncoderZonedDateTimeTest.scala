package kyo.internal.postgres.types

import kyo.*
import kyo.SqlCodec.Format
import kyo.internal.postgres.PostgresParamWriter
import kyo.internal.postgres.PostgresRowCodec

/** Wire-level tests for `java.time.ZonedDateTime` on PostgreSQL.
  *
  * The column writes the value's instant, which PostgreSQL carries in a `timestamptz` column (OID 1184, binary). Two values at the same
  * instant therefore produce identical bytes whatever zones they carried, which is the wire face of the zone loss the core suite documents:
  * a decode reconstructs the instant at UTC.
  *
  * The backend-blind half lives in `kyo/SqlSchemaZonedDateTimeTest.scala`.
  */
class PostgresEncoderZonedDateTimeTest extends kyo.Test:

    given CanEqual[java.time.LocalDateTime, java.time.LocalDateTime] = CanEqual.derived

    private def pgRow(bytes: Chunk[Span[Byte]], names: Chunk[String]): SqlRow =
        new SqlRow(
            bytes.map(Maybe.Present(_)),
            names.map(SqlRow.Column(_, PostgresEncoder.OID_TIMESTAMPTZ)),
            PostgresRowCodec(Format.Binary)
        )

    private def payloads[A](column: SqlSchema.Column[A], value: A)(using kyo.test.AssertScope): Chunk[Span[Byte]] =
        PostgresParamWriter.write(column, value).map { p =>
            p.encoded match
                case Maybe.Present(b) => b
                case Maybe.Absent     => fail("expected an encoded param")
        }

    "ZonedDateTime reaches the wire as one OID 1184 binary param" in {
        val zdt    = java.time.ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, java.time.ZoneId.of("America/New_York"))
        val params = PostgresParamWriter.write(summon[SqlSchema.Column[java.time.ZonedDateTime]], zdt)
        assert(params.size == 1)
        assert(params(0).encoder.oid == PostgresEncoder.OID_TIMESTAMPTZ)
        assert(params(0).encoder.format == Format.Binary)
    }

    "two ZonedDateTimes at the same instant encode to identical bytes" in {
        val column = summon[SqlSchema.Column[java.time.ZonedDateTime]]
        val zdt1   = java.time.ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        val zdt2   = java.time.ZonedDateTime.of(2024, 1, 1, 21, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo"))
        assert(zdt1.toInstant.equals(zdt2.toInstant))
        assert(payloads(column, zdt1).head.toArray.toSeq == payloads(column, zdt2).head.toArray.toSeq)
    }

    "ZonedDateTime round-trips through a timestamptz column, coming back at UTC" in {
        val column   = summon[SqlSchema.Column[java.time.ZonedDateTime]]
        val original = java.time.ZonedDateTime.of(2022, 8, 1, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo"))
        val row      = pgRow(payloads(column, original), Chunk("ts"))
        Abort.run(row.decode[java.time.ZonedDateTime]).eval match
            case Result.Success(decoded) =>
                assert(decoded.toInstant.equals(original.toInstant))
                assert(decoded.getZone.equals(java.time.ZoneOffset.UTC))
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "the zone is not persisted: a zoned value comes back at UTC with a shifted wall clock" in {
        // The single timestamptz column carries an instant, so the IANA zone id a caller wrote is application-level
        // metadata the round-trip drops. Pinning the wall-clock fields alongside the instant is what keeps that a
        // stated behavior rather than a surprise a reader has to infer from the absence of a second column.
        val column   = summon[SqlSchema.Column[java.time.ZonedDateTime]]
        val original = java.time.ZonedDateTime.parse("2026-05-22T10:30:00+02:00[Europe/Paris]")
        val row      = pgRow(payloads(column, original), Chunk("ts"))
        Abort.run(row.decode[java.time.ZonedDateTime]).eval match
            case Result.Success(decoded) =>
                assert(decoded.toInstant.equals(original.toInstant))
                assert(decoded.getZone.equals(java.time.ZoneOffset.UTC))
                assert(decoded.toLocalDateTime == java.time.LocalDateTime.of(2026, 5, 22, 8, 30, 0))
            case other => fail(s"Expected Success but got $other")
        end match
    }

end PostgresEncoderZonedDateTimeTest
