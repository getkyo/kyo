package kyo

import kyo.SqlDecodeException
import kyo.internal.postgres.BoundParam
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresEncoder

/** Unit tests for [[SqlSchema]] JDK string-round-trip types: `java.net.URI`, `java.net.URL`, `java.util.Locale`, `java.util.Currency`
  * (Phase 22, G-Codec-JDK-7, G-Codec-JDK-8, G-Codec-JDK-11, G-Codec-JDK-13).
  *
  * All tests are pure (no database container required). They exercise the schemas via in-memory byte buffers and mock [[SqlRow]] instances.
  */
class SqlSchemaJdkTypesTest extends Test:

    // ── helpers ────────────────────────────────────────────────────────────────

    private def field(name: String): FieldDescription =
        FieldDescription(name, 0, 0, 0, 0, 0, 1)

    /** Build a Postgres `SqlRow` whose single column carries a raw UTF-8 string value. */
    private def pgStringRow(value: String): SqlRow =
        val bytes = Span.from(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val vals  = Chunk(Maybe.Present(bytes))
        val flds  = Chunk(field("col"))
        new SqlRow(vals, flds, Format.Binary)
    end pgStringRow

    /** Build a MySQL `SqlRow` whose single column carries raw UTF-8 bytes for a string value.
      *
      * The MySQL binary protocol strips the length-encoded prefix before handing column bytes to the row reader; `MysqlRowReader.string()`
      * calls `readUtf8String(nextBytes())` which treats the column span as raw UTF-8.
      */
    private def mysqlStringRow(value: String): SqlRow =
        val bytes = Span.from(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val vals  = Chunk(Maybe.Present(bytes))
        val flds  = Chunk(field("col"))
        new SqlRow(vals, flds, Format.Binary)
    end mysqlStringRow

    // ── java.net.URI ───────────────────────────────────────────────────────────

    "summon SqlSchema[java.net.URI] compiles" in {
        val s: SqlSchema[java.net.URI] = summon[SqlSchema[java.net.URI]]
        assert(s.fieldCount == 1)
        succeed
    }

    "URI writePostgres encodes as OID_TEXT" in {
        val uri    = java.net.URI.create("https://example.com/path?q=1")
        val params = summon[SqlSchema[java.net.URI]].writePostgres(uri)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_TEXT)
        succeed
    }

    "URI writeMysql produces one param" in {
        val uri    = java.net.URI.create("ftp://files.example.org/pub")
        val params = summon[SqlSchema[java.net.URI]].writeMysql(uri)
        assert(params.size == 1)
        succeed
    }

    "URI round-trips through Postgres" in {
        val original = java.net.URI.create("https://example.com/path?q=1#section")
        val row      = pgStringRow(original.toString)
        val result   = Abort.run(summon[SqlSchema[java.net.URI]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toString == original.toString)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "URI round-trips through MySQL" in {
        val original = java.net.URI.create("urn:isbn:0-486-27557-4")
        val row      = mysqlStringRow(original.toString)
        val result   = Abort.run(summon[SqlSchema[java.net.URI]].readMysql(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toString == original.toString)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "URI with empty path round-trips" in {
        val original = java.net.URI.create("https://example.com")
        val row      = pgStringRow(original.toString)
        val result   = Abort.run(summon[SqlSchema[java.net.URI]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) => assert(decoded.toString == original.toString)
            case other                   => fail(s"Expected Success but got $other")
        end match
    }

    "URI decode from invalid string raises Abort[SqlDecodeException]" in {
        // Space characters are illegal in a URI, URI.create will throw IllegalArgumentException.
        val row    = pgStringRow("not a valid uri with spaces here")
        val result = Abort.run[SqlDecodeException](summon[SqlSchema[java.net.URI]].readPostgres(row)).eval
        result match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Failure(SqlDecodeException) but got $other")
        end match
    }

    // ── java.net.URL ───────────────────────────────────────────────────────────

    "summon SqlSchema[java.net.URL] compiles" in {
        val s: SqlSchema[java.net.URL] = summon[SqlSchema[java.net.URL]]
        assert(s.fieldCount == 1)
        succeed
    }

    "URL writePostgres encodes as OID_TEXT" in {
        val url    = new java.net.URI("https://example.com/page").toURL()
        val params = summon[SqlSchema[java.net.URL]].writePostgres(url)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_TEXT)
        succeed
    }

    "URL writeMysql produces one param" in {
        val url    = new java.net.URI("http://api.example.org/v1/items").toURL()
        val params = summon[SqlSchema[java.net.URL]].writeMysql(url)
        assert(params.size == 1)
        succeed
    }

    "URL round-trips through Postgres (string-form equality)" in {
        val original = new java.net.URI("https://example.com/path?q=hello").toURL()
        val row      = pgStringRow(original.toString)
        val result   = Abort.run(summon[SqlSchema[java.net.URL]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                // Compare string representations to avoid DNS-resolving URL.equals.
                assert(decoded.toString == original.toString)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "URL round-trips through MySQL (string-form equality)" in {
        val original = new java.net.URI("ftp://files.example.org/pub/data.csv").toURL()
        val row      = mysqlStringRow(original.toString)
        val result   = Abort.run(summon[SqlSchema[java.net.URL]].readMysql(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toString == original.toString)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "URL decode from invalid string raises Abort[SqlDecodeException]" in {
        // "not-a-url" has no scheme, URI.create succeeds but toURL throws MalformedURLException.
        val row    = pgStringRow("not-a-url")
        val result = Abort.run[SqlDecodeException](summon[SqlSchema[java.net.URL]].readPostgres(row)).eval
        result match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Failure(SqlDecodeException) but got $other")
        end match
    }

    // ── java.util.Locale ───────────────────────────────────────────────────────

    "summon SqlSchema[java.util.Locale] compiles" in {
        val s: SqlSchema[java.util.Locale] = summon[SqlSchema[java.util.Locale]]
        assert(s.fieldCount == 1)
        succeed
    }

    "Locale writePostgres encodes as OID_TEXT" in {
        val locale = java.util.Locale.forLanguageTag("en-US")
        val params = summon[SqlSchema[java.util.Locale]].writePostgres(locale)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_TEXT)
        succeed
    }

    "Locale writeMysql produces one param" in {
        val locale = java.util.Locale.forLanguageTag("pt-BR")
        val params = summon[SqlSchema[java.util.Locale]].writeMysql(locale)
        assert(params.size == 1)
        succeed
    }

    "Locale en-US round-trips through Postgres" in {
        val original = java.util.Locale.forLanguageTag("en-US")
        val row      = pgStringRow(original.toLanguageTag)
        val result   = Abort.run(summon[SqlSchema[java.util.Locale]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toLanguageTag == original.toLanguageTag)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Locale pt-BR round-trips through MySQL" in {
        val original = java.util.Locale.forLanguageTag("pt-BR")
        val row      = mysqlStringRow(original.toLanguageTag)
        val result   = Abort.run(summon[SqlSchema[java.util.Locale]].readMysql(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toLanguageTag == original.toLanguageTag)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Locale with complex BCP 47 tag round-trips" in {
        // zh-Hant-TW is a well-formed complex BCP 47 tag (Chinese Traditional, Taiwan)
        val original = java.util.Locale.forLanguageTag("zh-Hant-TW")
        val row      = pgStringRow(original.toLanguageTag)
        val result   = Abort.run(summon[SqlSchema[java.util.Locale]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toLanguageTag == original.toLanguageTag)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    // ── java.util.Currency ─────────────────────────────────────────────────────

    "summon SqlSchema[java.util.Currency] compiles" in {
        val s: SqlSchema[java.util.Currency] = summon[SqlSchema[java.util.Currency]]
        assert(s.fieldCount == 1)
        succeed
    }

    "Currency writePostgres encodes as OID_TEXT" in {
        val currency = java.util.Currency.getInstance("USD")
        val params   = summon[SqlSchema[java.util.Currency]].writePostgres(currency)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_TEXT)
        succeed
    }

    "Currency writeMysql produces one param" in {
        val currency = java.util.Currency.getInstance("BRL")
        val params   = summon[SqlSchema[java.util.Currency]].writeMysql(currency)
        assert(params.size == 1)
        succeed
    }

    "Currency USD round-trips through Postgres" in {
        val original = java.util.Currency.getInstance("USD")
        val row      = pgStringRow(original.getCurrencyCode)
        val result   = Abort.run(summon[SqlSchema[java.util.Currency]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.getCurrencyCode == "USD")
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Currency BRL round-trips through MySQL" in {
        val original = java.util.Currency.getInstance("BRL")
        val row      = mysqlStringRow(original.getCurrencyCode)
        val result   = Abort.run(summon[SqlSchema[java.util.Currency]].readMysql(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.getCurrencyCode == "BRL")
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Currency JPY round-trips through Postgres" in {
        val original = java.util.Currency.getInstance("JPY")
        val row      = pgStringRow(original.getCurrencyCode)
        val result   = Abort.run(summon[SqlSchema[java.util.Currency]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.getCurrencyCode == "JPY")
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Currency decode from invalid code raises Abort[SqlDecodeException]" in {
        // "NOTACURRENCY" is not a valid ISO 4217 code, Currency.getInstance throws IllegalArgumentException.
        val row    = pgStringRow("NOTACURRENCY")
        val result = Abort.run[SqlDecodeException](summon[SqlSchema[java.util.Currency]].readPostgres(row)).eval
        result match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Failure(SqlDecodeException) but got $other")
        end match
    }

end SqlSchemaJdkTypesTest
