package kyo.internal.postgres.types

import kyo.*
import kyo.SqlCodec.Format
import kyo.SqlDecodeException
import kyo.internal.postgres.PostgresParamWriter
import kyo.internal.postgres.PostgresRowCodec

/** Wire-level tests for the JDK string-round-trip schemas on PostgreSQL: `java.net.URI`, `java.util.Locale`,
  * `java.util.Currency`.
  *
  * Each writes through the writer's `string` primitive, which PostgreSQL maps to `textText`: OID 25, text format. These leaves pin that
  * mapping and the real round-trip through a `text` column. The backend-blind half (which text form each schema chooses, and the typed decode
  * failure on unparsable text) lives in `kyo/SqlSchemaJdkTypesTest.scala`; the MySQL counterpart in
  * `kyo/internal/mysql/types/MysqlEncoderJdkTypesTest.scala`.
  */
class PostgresEncoderJdkTypesTest extends kyo.Test:

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

    /** Decodes `row` as an `A` and projects the result with `f`, keeping `A` abstract for the whole decode.
      *
      * `A` stays a type parameter deliberately. `SqlRow.decode[A]` produces `A < Abort[SqlDecodeException]`, and the pending type is the
      * union `A | Kyo[A, S]`; a union erases to the lub of its members, so naming a concrete `A` at the decode site makes the compiler
      * compute that lub from the named class and insert a cast to it. For `java.util.Locale` the two platforms disagree about what that
      * class is: the Scala compiler types `java.util.Locale` from the JDK, where it implements `java.io.Serializable`, while Scala.js links
      * the `scala-java-locales` class, whose ancestor set is `Locale` alone. The inserted cast to `Serializable` then throws on JS, and
      * `java.util.Locale` is the only JDK type this module has a schema for that the JS javalib declares that way (`Currency`, `UUID`,
      * `URI`, `BigDecimal`, `BigInteger`, and every `java.time` class do implement it). Decoding inside a generic method computes the lub
      * on the abstract `A` instead, so no cast exists to disagree about, which is why the backend-blind leaves in
      * `kyo/SqlSchemaJdkTypesTest.scala` already pass on JS.
      */
    private def decodeAs[A, B](row: SqlRow, f: A => B)(using Frame, SqlSchema[A]): Result[SqlDecodeException, B] =
        Abort.run[SqlDecodeException](row.decode[A].map(f)).eval

    "URI reaches the wire as an OID 25 text param and round-trips" in {
        val original = java.net.URI.create("https://example.com/path?q=1#section")
        assert(textParam(original) == original.toString)
        decodeAs[java.net.URI, String](textRow(original.toString), _.toString) match
            case Result.Success(text) => assert(text == original.toString)
            case other                => fail(s"Expected Success but got $other")
        end match
    }

    "Locale reaches the wire as an OID 25 text param and round-trips" in {
        val original = java.util.Locale.forLanguageTag("pt-BR")
        assert(textParam(original) == "pt-BR")
        decodeAs[java.util.Locale, String](textRow("pt-BR"), _.toLanguageTag) match
            case Result.Success(tag) => assert(tag == "pt-BR")
            case other               => fail(s"Expected Success but got $other")
        end match
    }

    "Currency reaches the wire as an OID 25 text param and round-trips" in {
        assert(textParam(java.util.Currency.getInstance("JPY")) == "JPY")
        decodeAs[java.util.Currency, String](textRow("JPY"), _.getCurrencyCode) match
            case Result.Success(code) => assert(code == "JPY")
            case other                => fail(s"Expected Success but got $other")
        end match
    }

    "text that does not parse surfaces as Abort[SqlDecodeException] through the row codec" in {
        decodeAs[java.util.Currency, String](textRow("NOTACURRENCY"), _.getCurrencyCode) match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Failure(SqlDecodeException) but got $other")
    }

end PostgresEncoderJdkTypesTest
