package kyo.internal.mysql.types

import kyo.*
import kyo.SqlCodec.Format
import kyo.SqlDecodeException
import kyo.internal.mysql.MysqlParamWriter
import kyo.internal.mysql.MysqlRowCodec

/** Unit tests for the MySQL `java.time.Period` column path.
  *
  * MySQL has no calendar-interval type, so `given SqlSchema[Period]`'s `w.calendarInterval(v)` becomes a `VAR_STRING` parameter holding the
  * ISO-8601 form (`P1Y2M3D`), and `r.nextCalendarInterval()` parses that form back. The PostgreSQL counterpart, which uses the native INTERVAL
  * binary codec, lives in `kyo/internal/postgres/PostgresEncoderIntervalTest.scala`.
  */
class MysqlEncoderPeriodTest extends kyo.Test:

    /** A one-column MySQL row holding the ISO-8601 text of `period`, which is the lenenc-stripped payload the server sends. */
    private def periodRow(period: java.time.Period): SqlRow =
        val raw = period.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        new SqlRow(
            Chunk(Maybe.Present(Span.from(raw))),
            Chunk(SqlRow.Column("p", 0)),
            MysqlRowCodec(Format.Binary)
        )
    end periodRow

    "Period writes one VAR_STRING param holding the ISO-8601 form" in {
        val params = MysqlParamWriter.write(summon[SqlSchema.Column[java.time.Period]], java.time.Period.of(1, 2, 3))
        assert(params.size == 1)
        assert(params(0).encoder.mysqlType == MysqlEncoder.TYPE_VAR_STRING)
        params(0).value match
            case Maybe.Present(text: String) => assert(text == "P1Y2M3D")
            case other                       => fail(s"expected the ISO-8601 text, got $other")
        end match
    }

    "Period round-trips through the MySQL string codec" in {
        val original = java.time.Period.of(1, 2, 3)
        Abort.run(periodRow(original).decode[java.time.Period]).eval match
            case Result.Success(decoded) =>
                assert(decoded.getYears == 1)
                assert(decoded.getMonths == 2)
                assert(decoded.getDays == 3)
            case other => fail(s"expected Success, got $other")
        end match
    }

    "Period MySQL decode preserves zero and negative periods" in {
        val cases = Seq(
            java.time.Period.ZERO,
            java.time.Period.of(0, 6, 0),
            java.time.Period.of(-1, 0, 0),
            java.time.Period.ofDays(-15)
        )
        cases.foreach { original =>
            Abort.run(periodRow(original).decode[java.time.Period]).eval match
                case Result.Success(decoded) => assert(decoded.equals(original), s"mismatch for $original: got $decoded")
                case other                   => fail(s"expected Success for $original, got $other")
            end match
        }
        succeed
    }

    "Period MySQL decode raises a typed failure on malformed text" in {
        val raw = "not-a-period".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val row = new SqlRow(
            Chunk(Maybe.Present(Span.from(raw))),
            Chunk(SqlRow.Column("p", 0)),
            MysqlRowCodec(Format.Binary)
        )
        Abort.run[SqlDecodeException](row.decode[java.time.Period]).eval match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"expected Failure(SqlDecodeException), got $other")
        end match
    }

end MysqlEncoderPeriodTest
