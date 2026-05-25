package kyo

import java.io.File
import java.net.InetAddress
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.DayOfWeek
import java.util.Currency
import kyo.internal.JsonReader
import kyo.internal.JsonWriter
import kyo.internal.PlatformSchemas.given

class CodecJvmTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    given Schema[DayOfWeek]          = Schema.derived[DayOfWeek]
    given Schema[StandardOpenOption] = Schema.derived[StandardOpenOption]

    private def jsonRoundTrip[A](value: A)(using schema: Schema[A]): A =
        val w = JsonWriter()
        schema.writeTo(value, w)
        val r = JsonReader(w.resultString)
        schema.readFrom(r)
    end jsonRoundTrip

    "URL round-trip" in {
        val value = new java.net.URI("https://example.com/path").toURL
        assert(jsonRoundTrip(value) == value)
    }

    "InetAddress round-trip" in {
        val value = InetAddress.getByName("192.168.1.1")
        val got   = jsonRoundTrip(value)
        assert(got.getHostAddress == "192.168.1.1")
    }

    "Path round-trip is idempotent under Paths.get(_).toString" in {
        val value: Path = Paths.get("foo", "bar", "baz.txt")
        val got         = jsonRoundTrip(value)
        assert(Paths.get(got.toString) == value)
    }

    "File round-trip preserves getPath" in {
        val value = new File("/tmp/foo")
        val got   = jsonRoundTrip(value)
        assert(got.getPath == "/tmp/foo")
    }

    "Currency round-trip" in {
        val value = Currency.getInstance("EUR")
        val got   = jsonRoundTrip(value)
        assert(got.getCurrencyCode == "EUR")
    }

    // --- JVM-only: IANA zones and DST edge cases ---
    // scala-java-time on JS/Native ships only fixed-offset zones by default;
    // these regional / DST round-trips require the platform JVM's tzdata.

    "JVM: ZoneId IANA America/Los_Angeles round-trip" in {
        val value = java.time.ZoneId.of("America/Los_Angeles")
        assert(jsonRoundTrip(value) == value)
    }

    "JVM: ZonedDateTime DST fall-back round-trip" in {
        val value = java.time.ZonedDateTime.parse("2024-11-03T01:30:00-07:00[America/Los_Angeles]")
        val got   = jsonRoundTrip(value)
        assert(got == value)
        assert(got.getOffset == value.getOffset)
    }

    "JVM: ZonedDateTime DST spring-forward round-trip" in {
        val value = java.time.ZonedDateTime.parse("2024-03-10T03:30:00-07:00[America/Los_Angeles]")
        val got   = jsonRoundTrip(value)
        assert(got == value)
        assert(got.getOffset == value.getOffset)
    }

    // --- Java enum derivation (folded from JavaEnumTest) ---

    "Java enum derivation" - {

        "round-trip DayOfWeek" in {
            val value: DayOfWeek = DayOfWeek.WEDNESDAY
            val encoded          = Json.encode[DayOfWeek](value)
            val decoded          = Json.decode[DayOfWeek](encoded)
            assert(decoded == Result.succeed(DayOfWeek.WEDNESDAY))
        }

        "round-trip StandardOpenOption" in {
            val value: StandardOpenOption = StandardOpenOption.READ
            val encoded                   = Json.encode[StandardOpenOption](value)
            val decoded                   = Json.decode[StandardOpenOption](encoded)
            assert(decoded == Result.succeed(StandardOpenOption.READ))
        }

        "decode of unknown constant fails with UnknownVariantException" in {
            val raw    = "\"NOT_A_DAY\""
            val result = Json.decode[DayOfWeek](raw)
            assert(result.isFailure)
            result match
                case Result.Failure(e: UnknownVariantException) =>
                    assert(e.variantName == "NOT_A_DAY")
                    assert(e.getMessage.contains("NOT_A_DAY"))
                case other => fail(s"Expected Failure with UnknownVariantException, got $other")
            end match
        }
    }

end CodecJvmTest
