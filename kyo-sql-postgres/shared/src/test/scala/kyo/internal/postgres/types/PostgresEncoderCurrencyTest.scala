package kyo.internal.postgres.types

import kyo.*
import kyo.SqlCodec.Format
import kyo.SqlDecodeException
import kyo.internal.postgres.PostgresParamWriter
import kyo.internal.postgres.PostgresRowCodec

/** Wire-level tests for the [[Currency]] schema on PostgreSQL: the ISO 4217 code as an OID 25 text param, decoded back from a `text`
  * column. The backend-blind half lives in `kyo/SqlSchemaCurrencyTest.scala`; the MySQL counterpart in
  * `kyo/internal/mysql/types/MysqlEncoderCurrencyTest.scala`.
  */
class PostgresEncoderCurrencyTest extends kyo.Test:

    /** A one-column PG text row holding `value`. */
    private def textRow(value: String): SqlRow =
        new SqlRow(
            Chunk(Maybe.Present(Span.from(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))),
            Chunk(SqlRow.Column("col", PostgresEncoder.OID_TEXT)),
            PostgresRowCodec(Format.Text)
        )

    /** Asserts `value` reaches the wire as a single OID 25 text param, and returns the text it carried. */
    private def textParam[A](value: A)(using s: SqlSchema.Column[A], as: kyo.test.AssertScope): String =
        val params = PostgresParamWriter.write(s, value)
        assert(params.size == 1, s"expected 1 param, got ${params.size}")
        assert(params(0).encoder.oid == PostgresEncoder.OID_TEXT)
        assert(params(0).encoder.format == Format.Text)
        params(0).encoded match
            case Maybe.Present(bytes) => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
            case Maybe.Absent         => fail("expected encoded text bytes")
        end match
    end textParam

    /** Decodes `row` as an `A` and projects the result with `f`, keeping `A` abstract for the whole decode (see the same helper in
      * `PostgresEncoderJdkTypesTest.scala`).
      */
    private def decodeAs[A, B](row: SqlRow, f: A => B)(using Frame, SqlSchema[A]): Result[SqlDecodeException, B] =
        Abort.run[SqlDecodeException](row.decode[A].map(f)).eval

    "Currency reaches the wire as an OID 25 text param holding its code and round-trips" in {
        assert(textParam(Currency.parse("JPY").getOrThrow) == "JPY")
        decodeAs[Currency, String](textRow("JPY"), _.code) match
            case Result.Success(code) => assert(code == "JPY")
            case other                => fail(s"Expected Success but got $other")
        end match
    }

    "text that is not an ISO 4217 code surfaces as Abort[SqlDecodeException] through the row codec" in {
        decodeAs[Currency, String](textRow("NOTACURRENCY"), _.code) match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Failure(SqlDecodeException) but got $other")
    }

end PostgresEncoderCurrencyTest
