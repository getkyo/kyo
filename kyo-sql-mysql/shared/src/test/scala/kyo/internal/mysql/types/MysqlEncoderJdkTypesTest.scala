package kyo.internal.mysql.types

import kyo.*
import kyo.SqlCodec.Format
import kyo.SqlDecodeException
import kyo.internal.mysql.MysqlParamWriter
import kyo.internal.mysql.MysqlRowCodec

/** Wire-level tests for the JDK string-round-trip schemas on MySQL: `java.net.URI`, `java.util.Locale`,
  * `java.util.Currency`.
  *
  * Each writes through the writer's `string` primitive, which MySQL maps to `stringEncoder`: type byte `VAR_STRING`, a lenenc-prefixed UTF-8
  * payload. These leaves pin that mapping and the real round-trip through such a column, where the server hands the reader the payload with the
  * length prefix already consumed. The backend-blind half lives in `kyo/SqlSchemaJdkTypesTest.scala`; the PostgreSQL counterpart in
  * `kyo/internal/postgres/types/PostgresEncoderJdkTypesTest.scala`.
  */
class MysqlEncoderJdkTypesTest extends kyo.Test:

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

    /** Decodes `row` as an `A` and projects the result with `f`, keeping `A` abstract for the whole decode.
      *
      * `A` stays a type parameter deliberately, for the erasure reason spelled out on the same helper in
      * `kyo/internal/postgres/types/PostgresEncoderJdkTypesTest.scala`: naming a concrete `A` at the decode site makes the compiler erase
      * `A < Abort[SqlDecodeException]` to the lub of that class and `Kyo`, and for `java.util.Locale` the JDK signature the compiler types
      * against and the `scala-java-locales` class Scala.js links disagree about whether it implements `java.io.Serializable`.
      */
    private def decodeAs[A, B](row: SqlRow, f: A => B)(using Frame, SqlSchema[A]): Result[SqlDecodeException, B] =
        Abort.run[SqlDecodeException](row.decode[A].map(f)).eval

    "URI reaches the wire as a VAR_STRING param and round-trips" in {
        val original = java.net.URI.create("urn:isbn:0-486-27557-4")
        assert(textParam(original) == original.toString)
        decodeAs[java.net.URI, String](textRow(original.toString), _.toString) match
            case Result.Success(text) => assert(text == original.toString)
            case other                => fail(s"Expected Success but got $other")
        end match
    }

    "Locale reaches the wire as a VAR_STRING param and round-trips" in {
        val original = java.util.Locale.forLanguageTag("pt-BR")
        assert(textParam(original) == "pt-BR")
        decodeAs[java.util.Locale, String](textRow("pt-BR"), _.toLanguageTag) match
            case Result.Success(tag) => assert(tag == "pt-BR")
            case other               => fail(s"Expected Success but got $other")
        end match
    }

    "Currency reaches the wire as a VAR_STRING param and round-trips" in {
        assert(textParam(java.util.Currency.getInstance("BRL")) == "BRL")
        decodeAs[java.util.Currency, String](textRow("BRL"), _.getCurrencyCode) match
            case Result.Success(code) => assert(code == "BRL")
            case other                => fail(s"Expected Success but got $other")
        end match
    }

    "text that does not parse surfaces as Abort[SqlDecodeException] through the row codec" in {
        decodeAs[java.net.URI, String](textRow("not a valid uri with spaces here"), _.toString) match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Failure(SqlDecodeException) but got $other")
    }

end MysqlEncoderJdkTypesTest
