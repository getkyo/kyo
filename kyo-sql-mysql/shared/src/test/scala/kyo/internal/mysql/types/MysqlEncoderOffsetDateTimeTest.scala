package kyo.internal.mysql.types

import kyo.*
import kyo.SqlCodec.Format
import kyo.internal.mysql.MysqlParamWriter
import kyo.internal.mysql.MysqlRowCodec

/** Wire-level tests for `java.time.OffsetDateTime` on MySQL.
  *
  * The column writes the value's instant, which MySQL carries as a `TIMESTAMP` struct in UTC: the offset does not reach the wire, and the
  * value comes back at UTC.
  *
  * The backend-blind half lives in `kyo/SqlSchemaOffsetDateTimeTest.scala`.
  */
class MysqlEncoderOffsetDateTimeTest extends kyo.Test:

    given CanEqual[java.time.LocalDateTime, java.time.LocalDateTime] = CanEqual.derived

    /** The struct body MySQL sends back for a TIMESTAMP column, which is the param payload minus its length byte. */
    private def structBody(bytes: Span[Byte]): Span[Byte] = bytes.slice(1, bytes.size)

    private def mysqlRow(columns: Chunk[Span[Byte]], names: Chunk[String]): SqlRow =
        new SqlRow(columns.map(Maybe.Present(_)), names.map(SqlRow.Column(_, 0)), MysqlRowCodec(Format.Binary))

    private def payloads[A](column: SqlSchema.Column[A], value: A)(using kyo.test.AssertScope): Chunk[Span[Byte]] =
        MysqlParamWriter.write(column, value).map { p =>
            p.encoded match
                case Maybe.Present(b) => b
                case Maybe.Absent     => fail("expected an encoded param")
        }

    "OffsetDateTime reaches the wire as one TIMESTAMP param" in {
        val odt    = java.time.OffsetDateTime.of(2023, 3, 10, 8, 0, 0, 0, java.time.ZoneOffset.ofHours(-3))
        val params = MysqlParamWriter.write(summon[SqlSchema.Column[java.time.OffsetDateTime]], odt)
        assert(params.size == 1)
        assert(params(0).encoder.mysqlType == MysqlEncoder.TYPE_TIMESTAMP)
    }

    "two OffsetDateTimes at the same instant encode to identical bytes" in {
        val column = summon[SqlSchema.Column[java.time.OffsetDateTime]]
        val odt1   = java.time.OffsetDateTime.of(2024, 1, 1, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        val odt2   = java.time.OffsetDateTime.of(2024, 1, 1, 17, 0, 0, 0, java.time.ZoneOffset.ofHours(5))
        assert(payloads(column, odt1).head.toArray.toSeq == payloads(column, odt2).head.toArray.toSeq)
    }

    "OffsetDateTime round-trips through a DATETIME column, coming back at UTC" in {
        val column   = summon[SqlSchema.Column[java.time.OffsetDateTime]]
        val original = java.time.OffsetDateTime.of(2022, 8, 1, 9, 0, 0, 0, java.time.ZoneOffset.ofHours(3))
        val row      = mysqlRow(Chunk(structBody(payloads(column, original).head)), Chunk("ts"))
        Abort.run(row.decode[java.time.OffsetDateTime]).eval match
            case Result.Success(decoded) =>
                assert(decoded.toInstant.equals(original.toInstant))
                assert(decoded.getOffset.equals(java.time.ZoneOffset.UTC))
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "the offset is not persisted: a non-UTC OffsetDateTime comes back with a UTC offset and a shifted wall clock" in {
        // The single TIMESTAMP column carries an instant and nothing else, so the offset a caller wrote is
        // application-level metadata the round-trip drops. Pinning the wall-clock fields alongside the instant is
        // what keeps that a stated behavior rather than a surprise a reader has to infer.
        val column   = summon[SqlSchema.Column[java.time.OffsetDateTime]]
        val original = java.time.OffsetDateTime.parse("2026-05-22T10:30:00+05:30")
        val row      = mysqlRow(Chunk(structBody(payloads(column, original).head)), Chunk("ts"))
        Abort.run(row.decode[java.time.OffsetDateTime]).eval match
            case Result.Success(decoded) =>
                assert(decoded.toInstant.equals(original.toInstant))
                assert(decoded.getOffset.getTotalSeconds == 0)
                assert(decoded.toLocalDateTime == java.time.LocalDateTime.of(2026, 5, 22, 5, 0, 0))
            case other => fail(s"Expected Success but got $other")
        end match
    }

end MysqlEncoderOffsetDateTimeTest
