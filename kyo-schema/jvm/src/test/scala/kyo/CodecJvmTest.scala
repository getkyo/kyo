package kyo

import java.io.File
import java.net.InetAddress
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Currency
import java.util.Locale
import kyo.internal.JsonReader
import kyo.internal.JsonWriter
import kyo.internal.PlatformSchemas.given

class CodecJvmTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private def jsonRoundTrip[A](value: A)(using schema: Schema[A]): A =
        val w = JsonWriter()
        schema.writeTo(value, w)
        val r = JsonReader(w.resultString)
        schema.readFrom(r)
    end jsonRoundTrip

    "Phase 10: URI round-trip with query and fragment" in {
        val value = new URI("https://example.com/path?query=v&q2=w#frag")
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 10: URL round-trip" in {
        val value = new URI("https://example.com/path").toURL
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 10: InetAddress round-trip" in {
        val value = InetAddress.getByName("192.168.1.1")
        val got   = jsonRoundTrip(value)
        assert(got.getHostAddress == "192.168.1.1")
    }

    "Phase 10: Path round-trip is idempotent under Paths.get(_).toString" in {
        val value: Path = Paths.get("foo", "bar", "baz.txt")
        val got         = jsonRoundTrip(value)
        assert(Paths.get(got.toString) == value)
    }

    "Phase 10: File round-trip preserves getPath" in {
        val value = new File("/tmp/foo")
        val got   = jsonRoundTrip(value)
        assert(got.getPath == "/tmp/foo")
    }

    "Phase 10: Locale BCP-47 round-trip with script and region" in {
        val value = Locale.forLanguageTag("zh-Hant-TW")
        val got   = jsonRoundTrip(value)
        assert(got.toLanguageTag == "zh-Hant-TW")
    }

    "Phase 10: Currency round-trip" in {
        val value = Currency.getInstance("EUR")
        val got   = jsonRoundTrip(value)
        assert(got.getCurrencyCode == "EUR")
    }

    // --- Phase 11 (JVM-only): IANA zones and DST edge cases ---
    // scala-java-time on JS/Native ships only fixed-offset zones by default;
    // these regional / DST round-trips require the platform JVM's tzdata.

    "Phase 11 (JVM): ZoneId IANA America/Los_Angeles round-trip" in {
        val value = java.time.ZoneId.of("America/Los_Angeles")
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 11 (JVM): ZonedDateTime DST fall-back round-trip" in {
        val value = java.time.ZonedDateTime.parse("2024-11-03T01:30:00-07:00[America/Los_Angeles]")
        val got   = jsonRoundTrip(value)
        assert(got == value)
        assert(got.getOffset == value.getOffset)
    }

    "Phase 11 (JVM): ZonedDateTime DST spring-forward round-trip" in {
        val value = java.time.ZonedDateTime.parse("2024-03-10T03:30:00-07:00[America/Los_Angeles]")
        val got   = jsonRoundTrip(value)
        assert(got == value)
        assert(got.getOffset == value.getOffset)
    }

end CodecJvmTest
