package kyo.internal.postgres.types

import kyo.*
import kyo.SqlCodec.Format
import kyo.internal.postgres.PostgresParamWriter
import kyo.internal.postgres.PostgresRowCodec

/** Wire-level tests for `java.time.OffsetDateTime` on PostgreSQL.
  *
  * The column writes the value's instant, which PostgreSQL carries in a `timestamptz` column (OID 1184, binary). Two values at the same
  * instant therefore produce identical bytes whatever offsets they carried, which is the wire face of the offset loss the core suite
  * documents: a decode reconstructs the instant at UTC.
  *
  * The backend-blind half lives in `kyo/SqlSchemaOffsetDateTimeTest.scala`.
  */
class PostgresEncoderOffsetDateTimeTest extends kyo.Test:

    given CanEqual[java.time.LocalDateTime, java.time.LocalDateTime] = CanEqual.derived

    private def timestamptzRow(bytes: Chunk[Span[Byte]], names: Chunk[String]): SqlRow =
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

    "OffsetDateTime reaches the wire as one OID 1184 binary param" in {
        val odt    = java.time.OffsetDateTime.of(2024, 6, 15, 10, 30, 0, 0, java.time.ZoneOffset.ofHours(5))
        val params = PostgresParamWriter.write(summon[SqlSchema.Column[java.time.OffsetDateTime]], odt)
        assert(params.size == 1)
        assert(params(0).encoder.oid == PostgresEncoder.OID_TIMESTAMPTZ)
        assert(params(0).encoder.format == Format.Binary)
        assert(params(0).encoded.map(_.size) == Maybe(8), "timestamptz binary is an 8-byte Int64 of microseconds")
    }

    "two OffsetDateTimes at the same instant encode to identical bytes" in {
        val column = summon[SqlSchema.Column[java.time.OffsetDateTime]]
        val odt1   = java.time.OffsetDateTime.of(2024, 1, 1, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        val odt2   = java.time.OffsetDateTime.of(2024, 1, 1, 17, 0, 0, 0, java.time.ZoneOffset.ofHours(5))
        assert(odt1.toInstant.equals(odt2.toInstant))
        assert(payloads(column, odt1).head.toArray.toSeq == payloads(column, odt2).head.toArray.toSeq)
    }

    "OffsetDateTime round-trips through a timestamptz column, coming back at UTC" in {
        val column   = summon[SqlSchema.Column[java.time.OffsetDateTime]]
        val original = java.time.OffsetDateTime.of(2022, 8, 1, 9, 0, 0, 0, java.time.ZoneOffset.ofHours(3))
        val row      = timestamptzRow(payloads(column, original), Chunk("ts"))
        Abort.run(row.decode[java.time.OffsetDateTime]).eval match
            case Result.Success(decoded) =>
                assert(decoded.toInstant.equals(original.toInstant))
                assert(decoded.getOffset.equals(java.time.ZoneOffset.UTC))
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "the offset is not persisted: a non-UTC OffsetDateTime comes back with a UTC offset and a shifted wall clock" in {
        // The single timestamptz column carries an instant and nothing else, so the offset a caller wrote is
        // application-level metadata the round-trip drops. Pinning both halves (same instant, UTC offset, and the
        // wall-clock fields the UTC rendering puts on it) is what keeps that loss a stated behavior rather than a
        // surprise a reader has to infer from the absence of a second column.
        val column   = summon[SqlSchema.Column[java.time.OffsetDateTime]]
        val original = java.time.OffsetDateTime.parse("2026-05-22T10:30:00+05:30")
        val row      = timestamptzRow(payloads(column, original), Chunk("ts"))
        Abort.run(row.decode[java.time.OffsetDateTime]).eval match
            case Result.Success(decoded) =>
                assert(decoded.toInstant.equals(original.toInstant))
                assert(decoded.getOffset.getTotalSeconds == 0)
                assert(decoded.toLocalDateTime == java.time.LocalDateTime.of(2026, 5, 22, 5, 0, 0))
            case other => fail(s"Expected Success but got $other")
        end match
    }

end PostgresEncoderOffsetDateTimeTest
