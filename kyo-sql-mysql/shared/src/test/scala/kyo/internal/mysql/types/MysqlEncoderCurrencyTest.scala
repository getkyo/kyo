package kyo.internal.mysql.types

import kyo.*
import kyo.SqlCodec.Format
import kyo.SqlDecodeException
import kyo.internal.mysql.MysqlParamWriter
import kyo.internal.mysql.MysqlRowCodec

/** Wire-level tests for the [[Currency]] schema on MySQL: the ISO 4217 code as a VAR_STRING param, decoded back from such a column. The
  * backend-blind half lives in `kyo/SqlSchemaCurrencyTest.scala`; the PostgreSQL counterpart in
  * `kyo/internal/postgres/types/PostgresEncoderCurrencyTest.scala`.
  */
class MysqlEncoderCurrencyTest extends kyo.Test:

    /** A one-column MySQL row holding `value` as the raw UTF-8 payload of a VAR_STRING column. */
    private def textRow(value: String): SqlRow =
        new SqlRow(
            Chunk(Maybe.Present(Span.from(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))),
            Chunk(SqlRow.Column("col", 0)),
            MysqlRowCodec(Format.Binary)
        )

    /** Asserts `value` reaches the wire as a single VAR_STRING param, and returns the text it carried. */
    private def textParam[A](value: A)(using s: SqlSchema.Column[A], as: kyo.test.AssertScope): String =
        val params = MysqlParamWriter.write(s, value)
        assert(params.size == 1, s"expected 1 param, got ${params.size}")
        assert(params(0).encoder.mysqlType == MysqlEncoder.TYPE_VAR_STRING)
        params(0).value match
            case Maybe.Present(text: String) => text
            case other                       => fail(s"expected a string param, got $other")
        end match
    end textParam

    /** Decodes `row` as an `A` and projects the result with `f`, keeping `A` abstract for the whole decode (see the same helper in
      * `kyo/internal/postgres/types/PostgresEncoderJdkTypesTest.scala`).
      */
    private def decodeAs[A, B](row: SqlRow, f: A => B)(using Frame, SqlSchema[A]): Result[SqlDecodeException, B] =
        Abort.run[SqlDecodeException](row.decode[A].map(f)).eval

    "Currency reaches the wire as a VAR_STRING param holding its code and round-trips" in {
        assert(textParam(Currency.parse("BRL").getOrThrow) == "BRL")
        decodeAs[Currency, String](textRow("BRL"), _.code) match
            case Result.Success(code) => assert(code == "BRL")
            case other                => fail(s"Expected Success but got $other")
        end match
    }

    "text that is not an ISO 4217 code surfaces as Abort[SqlDecodeException] through the row codec" in {
        decodeAs[Currency, String](textRow("NOTACURRENCY"), _.code) match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Failure(SqlDecodeException) but got $other")
    }

end MysqlEncoderCurrencyTest
