package kyo

import kyo.SqlDecodeException

/** Unit tests for [[SqlSchema]] JDK string-round-trip types: `java.net.URI`, `java.util.Locale`, `java.util.Currency`.
  *
  * Each of these occupies one text column, and each writes through the writer's `string` primitive: the schema's whole job is choosing the
  * text form (`URI.toString`, `Locale.toLanguageTag`, `Currency.getCurrencyCode`) and parsing it back, which is what this file asserts. Text
  * that does not parse must surface as a typed decode failure, so those leaves drive the same catch the row codec uses in production.
  *
  * The wire legs, which column type each backend puts the text in, live in `kyo/internal/postgres/types/PostgresEncoderJdkTypesTest.scala`
  * and `kyo/internal/mysql/types/MysqlEncoderJdkTypesTest.scala`.
  */
class SqlSchemaJdkTypesTest extends Test:

    // ── helpers ────────────────────────────────────────────────────────────────

    /** The calls `value` made on the writer. */
    private def written[A](value: A)(using s: SqlSchema[A]): Chunk[SqlSchemaWriterMock.Call] =
        recording(value).calls

    /** Writes `value` into a fresh recording writer and returns it. */
    private def recording[A](value: A)(using s: SqlSchema[A]): SqlSchemaWriterMock =
        val writer = SqlSchemaWriterMock.postgresMock
        s.write(value, writer)
        writer
    end recording

    /** Reads an `A` back from a single text column, through the same catch the row codec applies in production. */
    private def readText[A](text: String)(using s: SqlSchema[A], f: Frame): Result[SqlDecodeException, A] =
        val reader = SqlSchemaReaderMock.postgresMock(Chunk(SqlSchemaWriterMock.Call.Str(text)))
        Abort.run(SqlRow.Codec.catching(s.read(reader))).eval
    end readText

    /** Writes `value`, then reads it back from what it wrote. */
    private def roundTrip[A](value: A)(using s: SqlSchema[A]): A =
        s.read(SqlSchemaReaderMock.replaying(recording(value)))

    // ── java.net.URI ───────────────────────────────────────────────────────────

    "summon SqlSchema[java.net.URI] compiles" in {
        val s: SqlSchema.Column[java.net.URI] = summon[SqlSchema.Column[java.net.URI]]
        assert(s.width == 1)
        assert(summon[SqlType[java.net.URI]].columnType == SqlType.Type.Text)
    }

    "URI writes one text column holding its string form" in {
        val uri = java.net.URI.create("https://example.com/path?q=1")
        assert(written(uri) == Chunk(SqlSchemaWriterMock.Call.Str("https://example.com/path?q=1")))
    }

    "URI round-trips, including an empty path and a fragment" in {
        val cases = Seq(
            java.net.URI.create("https://example.com/path?q=1#section"),
            java.net.URI.create("urn:isbn:0-486-27557-4"),
            java.net.URI.create("https://example.com")
        )
        cases.foreach(original => assert(roundTrip(original).toString == original.toString))
        succeed
    }

    "URI decode from invalid text raises Abort[SqlDecodeException]" in {
        // Space characters are illegal in a URI; URI.create throws IllegalArgumentException.
        readText[java.net.URI]("not a valid uri with spaces here") match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Failure(SqlDecodeException) but got $other")
    }

    // No `java.net.URL` leaves: `java.net.URL` is absent from the Scala.js javalib, so a schema over it would make
    // the module unlinkable on JS. The URI leaves above cover the same column shape.

    // ── java.util.Locale ───────────────────────────────────────────────────────

    "summon SqlSchema[java.util.Locale] compiles" in {
        val s: SqlSchema.Column[java.util.Locale] = summon[SqlSchema.Column[java.util.Locale]]
        assert(s.width == 1)
    }

    "Locale writes one text column holding its BCP 47 tag" in {
        assert(written(java.util.Locale.forLanguageTag("en-US")) == Chunk(SqlSchemaWriterMock.Call.Str("en-US")))
    }

    "Locale round-trips, including a complex BCP 47 tag" in {
        // zh-Hant-TW is a well-formed complex tag (Chinese Traditional, Taiwan).
        val cases = Seq("en-US", "pt-BR", "zh-Hant-TW").map(java.util.Locale.forLanguageTag)
        cases.foreach(original => assert(roundTrip(original).toLanguageTag == original.toLanguageTag))
        succeed
    }

    // ── java.util.Currency ─────────────────────────────────────────────────────

    "summon SqlSchema[java.util.Currency] compiles" in {
        val s: SqlSchema.Column[java.util.Currency] = summon[SqlSchema.Column[java.util.Currency]]
        assert(s.width == 1)
    }

    "Currency writes one text column holding its ISO 4217 code" in {
        assert(written(java.util.Currency.getInstance("USD")) == Chunk(SqlSchemaWriterMock.Call.Str("USD")))
    }

    "Currency round-trips for USD, BRL, and JPY" in {
        Seq("USD", "BRL", "JPY").foreach { code =>
            val original = java.util.Currency.getInstance(code)
            assert(roundTrip(original).getCurrencyCode == code)
        }
        succeed
    }

    "Currency decode from an invalid code raises Abort[SqlDecodeException]" in {
        // "NOTACURRENCY" is not a valid ISO 4217 code; Currency.getInstance throws IllegalArgumentException.
        readText[java.util.Currency]("NOTACURRENCY") match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Failure(SqlDecodeException) but got $other")
    }

end SqlSchemaJdkTypesTest
