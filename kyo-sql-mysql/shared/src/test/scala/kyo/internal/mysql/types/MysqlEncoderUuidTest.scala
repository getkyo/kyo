package kyo.internal.mysql.types

import java.util.UUID
import kyo.*
import kyo.SqlCodec.Format
import kyo.SqlRow
import kyo.SqlSchema
import kyo.internal.mysql.BoundMysqlParam
import kyo.internal.mysql.MysqlParamWriter
import kyo.internal.mysql.MysqlRowCodec

/** Unit tests for the MySQL UUID column path.
  *
  * MySQL has no native UUID type, so `given SqlSchema.Column[UUID]`'s `w.uuid(v)` becomes a `VAR_STRING` parameter holding the
  * 36-character canonical form, and `r.nextUuid()` parses that form back. The PostgreSQL counterpart, which uses the native binary codec,
  * lives in `kyo/internal/postgres/types/PostgresEncoderUuidTest.scala`.
  */
class MysqlEncoderUuidTest extends kyo.Test:

    given CanEqual[UUID, UUID] = CanEqual.canEqualAny

    /** A one-column MySQL row holding the canonical text of `value`, which is what the server sends back for such a column. */
    private def uuidRow(value: UUID): SqlRow =
        val raw = value.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        new SqlRow(
            Chunk(Maybe.Present(Span.from(raw))),
            Chunk(SqlRow.Column("id", 0)),
            MysqlRowCodec(Format.Binary)
        )
    end uuidRow

    /** The params a whole row occupies, which `MysqlParamWriter.write` cannot take: it accepts the single-column tier only. */
    private def rowParams[A](schema: SqlSchema[A], value: A): Chunk[BoundMysqlParam[?]] =
        val writer = new MysqlParamWriter()
        schema.write(value, writer)
        writer.params
    end rowParams

    "SqlSchema.Column[UUID] writes the canonical text and decodes it back (MySQL)" in {
        val column = summon[SqlSchema.Column[UUID]]
        val value  = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

        val params = MysqlParamWriter.write(column, value)
        assert(params.size == 1, s"expected 1 param, got ${params.size}")
        assert(params(0).encoder.mysqlType == MysqlEncoder.TYPE_VAR_STRING)
        params(0).value match
            case Maybe.Present(s: String) => assert(s == value.toString)
            case other                    => fail(s"expected Present string, got $other")
        end match

        kyo.Abort.run(uuidRow(value).decode[UUID]).map {
            case kyo.Result.Success(decoded) =>
                assert(decoded.getMostSignificantBits == value.getMostSignificantBits)
                assert(decoded.getLeastSignificantBits == value.getLeastSignificantBits)
            case kyo.Result.Failure(e) => fail(s"the row failed to decode: $e")
            case kyo.Result.Panic(t)   => throw t
        }
    }

    // The row derivation assembles its codec out of each field's own `SqlSchema.Column`, so a UUID field takes
    // the same `w.uuid(...)` path a bare UUID bind does. On MySQL that lands on a VAR_STRING param either way,
    // and this leaf pins that the field path produces the standalone column's bytes rather than merely something
    // that happens to round-trip.

    "a derived row's UUID field reaches the wire through the same column codec" in {
        case class WithUuid(id: UUID) derives CanEqual

        val value  = WithUuid(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
        val params = rowParams(summon[SqlSchema[WithUuid]], value)
        assert(params.size == 1)
        assert(params(0).encoder.mysqlType == MysqlEncoder.TYPE_VAR_STRING)
        params(0).value match
            case Maybe.Present(s: String) => assert(s == value.id.toString)
            case other                    => fail(s"expected Present string, got $other")
        end match

        kyo.Abort.run(uuidRow(value.id).decode[WithUuid]).map {
            case kyo.Result.Success(decoded) => assert(decoded == value)
            case kyo.Result.Failure(e)       => fail(s"the row failed to decode: $e")
            case kyo.Result.Panic(t)         => throw t
        }
    }

end MysqlEncoderUuidTest
