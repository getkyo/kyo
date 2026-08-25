package kyo.internal.postgres

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Span
import kyo.SqlCodec.Format
import kyo.SqlDecodeException
import kyo.SqlDecodeIntervalException
import kyo.SqlException
import kyo.Test
import kyo.internal.postgres.types.PostgresDecoder
import kyo.internal.postgres.types.PostgresEncoder

/** Tests for the PostgreSQL INTERVAL codec, in both wire formats, for the two Scala types it carries: `java.time.Duration` and
  * `java.time.Period`.
  *
  * Tests use explicit byte arrays (constructed from the known wire format) and the text renderings the server writes, to verify both the
  * encoder and decoder in isolation, no live database required.
  */
class PostgresEncoderIntervalTest extends Test:

    // `java.time.Period` is a JDK type with no `CanEqual` instance in scope; its `equals` compares years, months
    // and days field by field, which is exactly the comparison the Period assertion below relies on.
    given CanEqual[java.time.Period, java.time.Period] = CanEqual.canEqualAny

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a 16-byte INTERVAL binary payload (Int64 µs, Int32 days, Int32 months), all big-endian. */
    private def intervalBytes(micros: Long, days: Int, months: Int): Span[Byte] =
        val arr = new Array[Byte](16)
        arr(0) = ((micros >> 56) & 0xff).toByte
        arr(1) = ((micros >> 48) & 0xff).toByte
        arr(2) = ((micros >> 40) & 0xff).toByte
        arr(3) = ((micros >> 32) & 0xff).toByte
        arr(4) = ((micros >> 24) & 0xff).toByte
        arr(5) = ((micros >> 16) & 0xff).toByte
        arr(6) = ((micros >> 8) & 0xff).toByte
        arr(7) = (micros & 0xff).toByte
        arr(8) = ((days >> 24) & 0xff).toByte
        arr(9) = ((days >> 16) & 0xff).toByte
        arr(10) = ((days >> 8) & 0xff).toByte
        arr(11) = (days & 0xff).toByte
        arr(12) = ((months >> 24) & 0xff).toByte
        arr(13) = ((months >> 16) & 0xff).toByte
        arr(14) = ((months >> 8) & 0xff).toByte
        arr(15) = (months & 0xff).toByte
        Span.from(arr)
    end intervalBytes

    /** Encodes `value` using `enc` and returns the raw wire bytes. */
    private def encode[A](value: A, enc: PostgresEncoder[A]): Span[Byte] =
        val buf = new PostgresBufferWriter
        enc.write(value, buf)
        buf.toSpan
    end encode

    // ── Encode tests ─────────────────────────────────────────────────────────

    "OID_INTERVAL constant equals 1186" in {
        assert(PostgresEncoder.OID_INTERVAL == 1186)
    }

    "intervalBinary declares OID=1186 and Format.Binary" in {
        assert(PostgresEncoder.intervalBinary.oid == 1186)
        assert(PostgresEncoder.intervalBinary.format == Format.Binary)
    }

    "intervalBinary encodes Duration.ofHours(1) as 16-byte big-endian hex 00000000D693A4000000000000000000" in {
        val expected = Array[Byte](
            0x00,
            0x00,
            0x00,
            0x00,
            0xd6.toByte,
            0x93.toByte,
            0xa4.toByte,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00
        )
        val bytes = encode(java.time.Duration.ofHours(1), PostgresEncoder.intervalBinary)
        assert(bytes.size == 16, s"expected 16 bytes, got ${bytes.size}")
        assert(bytes.toArray.sameElements(expected), s"byte mismatch: got ${bytes.toArray.toSeq}")
    }

    "intervalBinary encodes Duration.ZERO as 16 zero bytes" in {
        val bytes = encode(java.time.Duration.ZERO, PostgresEncoder.intervalBinary)
        assert(bytes.size == 16, s"expected 16 bytes, got ${bytes.size}")
        assert(bytes.toArray.forall(_ == 0.toByte), s"expected all zeros, got ${bytes.toArray.toSeq}")
    }

    "intervalBinary encodes Duration.ofSeconds(-30) with negative µs" in {
        // µs = -30_000_000 = 0xFFFFFFFFFE363C80
        val expected = Array[Byte](
            0xff.toByte,
            0xff.toByte,
            0xff.toByte,
            0xff.toByte,
            0xfe.toByte,
            0x36.toByte,
            0x3c.toByte,
            0x80.toByte,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00
        )
        val bytes = encode(java.time.Duration.ofSeconds(-30), PostgresEncoder.intervalBinary)
        assert(bytes.size == 16, s"expected 16 bytes, got ${bytes.size}")
        assert(bytes.toArray.sameElements(expected), s"byte mismatch: ${bytes.toArray.toSeq}")
    }

    // ── Decode tests (binary format) ──────────────────────────────────────────

    "INTERVAL decodes Duration.ofHours(1) from binary" in {
        // µs = 3_600_000_000, days=0, months=0
        val bytes  = intervalBytes(3_600_000_000L, 0, 0)
        val result = PostgresDecoder.interval.read(Format.Binary, bytes)
        assert(result.equals(java.time.Duration.ofHours(1)), s"got $result")
    }

    "INTERVAL decodes Duration.ZERO from binary all-zero bytes" in {
        val bytes  = intervalBytes(0L, 0, 0)
        val result = PostgresDecoder.interval.read(Format.Binary, bytes)
        assert(result.equals(java.time.Duration.ZERO), s"got $result")
    }

    "INTERVAL handles negative microseconds (Duration.ofSeconds(-30))" in {
        // µs = -30_000_000
        val bytes  = intervalBytes(-30_000_000L, 0, 0)
        val result = PostgresDecoder.interval.read(Format.Binary, bytes)
        assert(result.equals(java.time.Duration.ofSeconds(-30)), s"got $result")
    }

    "INTERVAL decode raises SqlDecodeException when months != 0" in {
        // months = -1 (e.g. PG INTERVAL '1 month ago')
        val bytes = intervalBytes(0L, 0, -1)
        val ex = intercept[SqlDecodeIntervalException] {
            PostgresDecoder.interval.read(Format.Binary, bytes)
        }
        assert(ex.field == "months", s"expected months field, got: ${ex.field}")
        assert(ex.value == "-1", s"expected field value '-1', got: ${ex.value}")
    }

    "INTERVAL decode raises SqlDecodeException when days != 0 and months == 0" in {
        // days = 3, months = 0
        val bytes = intervalBytes(0L, 3, 0)
        val ex = intercept[SqlDecodeIntervalException] {
            PostgresDecoder.interval.read(Format.Binary, bytes)
        }
        assert(ex.field == "days", s"expected days field, got: ${ex.field}")
        assert(ex.value == "3", s"expected field value '3', got: ${ex.value}")
    }

    "INTERVAL decode raises months error before days error when both are non-zero" in {
        // months=1, days=5: months check fires first
        val bytes = intervalBytes(0L, 5, 1)
        val ex = intercept[SqlDecodeIntervalException] {
            PostgresDecoder.interval.read(Format.Binary, bytes)
        }
        assert(ex.field == "months", s"expected months field first, got: ${ex.field}")
    }

    // ── Encode/decode round-trip ──────────────────────────────────────────────

    "INTERVAL round-trip: encode then decode restores original Duration" in {
        val values = Seq(
            java.time.Duration.ZERO,
            java.time.Duration.ofHours(1),
            java.time.Duration.ofSeconds(-30),
            java.time.Duration.ofMinutes(90),
            java.time.Duration.ofSeconds(3661, 500_000_000L), // 1h 1m 1s + 500ms
            // A multi-day Duration round-trips cleanly via µs, because `java.time.Duration` encodes
            // everything as microseconds: the wire-format `days != 0` raise fires only when the SERVER
            // emits a days component, not when the Duration spans multiple calendar days client-side.
            java.time.Duration.ofDays(1).plusHours(2),
            java.time.Duration.ofDays(7), // one week
            java.time.Duration.ofDays(-3).minusHours(4)
        )
        for v <- values do
            val encoded = encode(v, PostgresEncoder.intervalBinary)
            val decoded = PostgresDecoder.interval.read(Format.Binary, encoded)
            assert(decoded.equals(v), s"round-trip failed for $v: got $decoded")
        end for
        succeed
    }

    "INTERVAL round-trip: multi-day Duration.ofDays(1).plusHours(2) decodes to 26 hours" in {
        // The multi-day case stated explicitly: `1.day + 2.hours` reads back as `toHours == 26`.
        val v       = java.time.Duration.ofDays(1).plusHours(2)
        val encoded = encode(v, PostgresEncoder.intervalBinary)
        val decoded = PostgresDecoder.interval.read(Format.Binary, encoded)
        assert(decoded.toHours == 26L, s"expected 26 hours, got ${decoded.toHours}")
        assert(decoded.equals(v))
    }

    // ── String-format decode ────────────────────────────────────────────────────

    "INTERVAL text decode parses ISO-8601 format PT1H" in {
        val bytes  = Span.from("PT1H".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val result = PostgresDecoder.interval.read(Format.Text, bytes)
        assert(result.equals(java.time.Duration.ofHours(1)), s"got $result")
    }

    "INTERVAL text decode parses ISO-8601 negative PT-30S" in {
        val bytes  = Span.from("PT-30S".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val result = PostgresDecoder.interval.read(Format.Text, bytes)
        assert(result.equals(java.time.Duration.ofSeconds(-30)), s"got $result")
    }

    "INTERVAL text decode parses PG hh:mm:ss format 01:30:00" in {
        val bytes  = Span.from("01:30:00".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val result = PostgresDecoder.interval.read(Format.Text, bytes)
        assert(result.equals(java.time.Duration.ofMinutes(90)), s"got $result")
    }

    "INTERVAL text decode raises SqlDecodeException for PG verbose format with months" in {
        val s     = "1 year 2 mons 00:01:02"
        val bytes = Span.from(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val ex = intercept[SqlDecodeIntervalException] {
            PostgresDecoder.interval.read(Format.Text, bytes)
        }
        assert(ex.field == "text", s"expected 'text' field, got: ${ex.field}")
        assert(ex.value == s, s"expected raw text as value, got: ${ex.value}")
    }

    // ── Period text-format decode ─────────────────────────────────────────────
    //
    // `IntervalStyle` decides which of four renderings the server writes a text-format INTERVAL in, and every
    // column of a `simpleQuery` result is text, so these are what a `java.time.Period` field reads on that path.
    // The default style is `postgres`, whose `1 year 2 mons 3 days` is not ISO-8601, so an ISO-only parse refuses it outright.

    /** Decodes `rendering` as a text-format INTERVAL column, the way a `simpleQuery` result reaches the decoder. */
    private def decodePeriodText(rendering: String): java.time.Period =
        PostgresDecoder.intervalPeriod.read(Format.Text, Span.from(rendering.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

    "Period text decode parses the default IntervalStyle rendering" in {
        assert(decodePeriodText("1 year 2 mons 3 days") == java.time.Period.of(1, 2, 3))
    }

    "Period text decode parses the singular unit forms the server writes for a count of one" in {
        assert(decodePeriodText("1 year 1 mon 1 day") == java.time.Period.of(1, 1, 1))
    }

    "Period text decode parses the partial renderings, one component at a time" in {
        assert(decodePeriodText("2 mons") == java.time.Period.ofMonths(2))
        assert(decodePeriodText("5 days") == java.time.Period.ofDays(5))
        assert(decodePeriodText("3 years") == java.time.Period.ofYears(3))
        assert(decodePeriodText("1 mon 15 days") == java.time.Period.of(0, 1, 15))
    }

    "Period text decode parses a negative rendering, sign by component" in {
        // The default style gives every component its own sign, so a mixed-sign value carries both.
        assert(decodePeriodText("-1 year -2 mons +3 days") == java.time.Period.of(-1, -2, 3))
        assert(decodePeriodText("-2 mons") == java.time.Period.ofMonths(-2))
        assert(decodePeriodText("-5 days") == java.time.Period.ofDays(-5))
    }

    "Period text decode accepts a zero HH:MM:SS time part" in {
        // The zero interval is written `00:00:00`, and the time part is appended to any value that has one.
        assert(decodePeriodText("00:00:00") == java.time.Period.ZERO)
        assert(decodePeriodText("1 year 2 mons 3 days 00:00:00") == java.time.Period.of(1, 2, 3))
    }

    "Period text decode raises on a non-zero time part, the component Period cannot hold" in {
        // The same refusal the binary arm makes on a non-zero microseconds field, reporting the same field and
        // the microseconds the time part carries: 4h 5m 6s.
        val ex = intercept[SqlDecodeIntervalException](decodePeriodText("1 year 2 mons 3 days 04:05:06"))
        assert(ex.field == "microseconds", s"expected the microseconds field, got: ${ex.field}")
        assert(ex.value == "14706000000", s"expected the µs the time part carries, got: ${ex.value}")

        val negative = intercept[SqlDecodeIntervalException](decodePeriodText("-1 year -2 mons +3 days -04:05:06"))
        assert(negative.value == "-14706000000", s"a negative time part keeps its sign, got: ${negative.value}")

        val worded = intercept[SqlDecodeIntervalException](decodePeriodText("@ 4 hours 5 mins 6 secs"))
        assert(worded.value == "14706000000", s"the verbose style's own time words, got: ${worded.value}")

        val fractional = intercept[SqlDecodeIntervalException](decodePeriodText("00:00:00.5"))
        assert(fractional.value == "500000", s"expected half a second in µs, got: ${fractional.value}")
    }

    "Period text decode parses the postgres_verbose rendering, including its @ and ago markers" in {
        assert(decodePeriodText("@ 1 year 2 mons 3 days") == java.time.Period.of(1, 2, 3))
        // `ago` closes a verbose rendering and negates every component before it.
        assert(decodePeriodText("@ 1 year 2 mons 3 days ago") == java.time.Period.of(-1, -2, -3))
        assert(decodePeriodText("@ 1 mon") == java.time.Period.ofMonths(1))
    }

    "Period text decode parses the sql_standard rendering" in {
        // `1-2` is its year-month field, with one sign for both numbers; a bare count is its day field; and its
        // time part comes without the leading zero the default style writes.
        assert(decodePeriodText("1-2") == java.time.Period.of(1, 2, 0))
        assert(decodePeriodText("-1-2") == java.time.Period.of(-1, -2, 0))
        assert(decodePeriodText("1-2 3 0:00:00") == java.time.Period.of(1, 2, 3))
        assert(decodePeriodText("3 0:00:00") == java.time.Period.ofDays(3))
    }

    "Period text decode still parses the ISO-8601 renderings it read before" in {
        assert(decodePeriodText("P1Y6M15D") == java.time.Period.of(1, 6, 15))
        assert(decodePeriodText("P5D") == java.time.Period.ofDays(5))
        assert(decodePeriodText("P2W") == java.time.Period.ofDays(14))
        assert(decodePeriodText("-P1Y2M3D") == java.time.Period.of(-1, -2, -3))
        assert(decodePeriodText("P-1Y-2M3D") == java.time.Period.of(-1, -2, 3))
        assert(decodePeriodText("p1y2m3d") == java.time.Period.of(1, 2, 3))
    }

    "Period text decode reads the iso_8601 style's zero and its time part" in {
        // `PT0S` is how that style writes the zero interval, and `java.time.Period.parse` rejects every string
        // carrying a `T` at all, so a bare `Period.parse` cannot decode the zero value of an INTERVAL column.
        assert(decodePeriodText("PT0S") == java.time.Period.ZERO)
        assert(decodePeriodText("P1Y2M3DT0S") == java.time.Period.of(1, 2, 3))
        val ex = intercept[SqlDecodeIntervalException](decodePeriodText("P1Y2M3DT4H5M6S"))
        assert(ex.field == "microseconds", s"expected the microseconds field, got: ${ex.field}")
        assert(ex.value == "14706000000", s"expected the µs the time part carries, got: ${ex.value}")
    }

    "Period text decode returns the normalised year/month split the binary arm returns" in {
        // The claim the binary leaf below makes, held on the text arm too: one written Period reads back as one
        // value, so a rendering whose months pass twelve carries into years rather than coming back as `P14M`.
        assert(decodePeriodText("P14M") == java.time.Period.of(1, 2, 0))
        assert(decodePeriodText("14 mons") == java.time.Period.of(1, 2, 0))
    }

    "Period text decode raises a typed failure on a total no Period field can hold" in {
        // `java.time.Period.parse` computes `days + weeks * 7` with `Math.multiplyExact` under a catch clause
        // covering `NumberFormatException` alone, so 306783379 weeks raises an unchecked `ArithmeticException`
        // that this decode has to catch and report as the typed leaf its sibling arms raise.
        val weeks = intercept[SqlDecodeIntervalException](decodePeriodText("P306783379W"))
        assert(weeks.field == "days", s"expected the days field, got: ${weeks.field}")
        assert(weeks.value == "2147483653", s"expected the day total that does not fit, got: ${weeks.value}")

        val years = intercept[SqlDecodeIntervalException](decodePeriodText("P200000000Y"))
        assert(years.field == "months", s"expected the months field, got: ${years.field}")
        assert(years.value == "2400000000", s"expected the month total that does not fit, got: ${years.value}")
    }

    "Period text decode raises a typed failure on a rendering it cannot read" in {
        val ex = intercept[SqlDecodeIntervalException](decodePeriodText("not an interval"))
        assert(ex.field == "text", s"expected the text field, got: ${ex.field}")
        assert(ex.value == "not an interval", s"expected the raw text as the value, got: ${ex.value}")
    }

    // ── SqlSchema.Column[java.time.Period] on the INTERVAL wire ──────────────
    //
    // This lives with the Postgres encoder because it needs a backend writer and reader. Wire layout:
    // `(µs: Int64, days: Int32, months: Int32)`; for a Period µs is always 0, months is `toTotalMonths`
    // (years * 12 + months), days is `getDays`.

    /** Encodes a Period through the PostgreSQL param writer and returns the single param's payload. */
    private def pgPeriodBytes(period: java.time.Period)(using kyo.test.AssertScope): Span[Byte] =
        PostgresParamWriter.write(summon[kyo.SqlSchema.Column[java.time.Period]], period).head.encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail("the Period schema produced a NULL param")
    end pgPeriodBytes

    private def periodRow(bytes: Span[Byte]): kyo.SqlRow =
        new kyo.SqlRow(
            kyo.Chunk(Maybe.Present(bytes)),
            kyo.Chunk(kyo.SqlRow.Column("p", PostgresEncoder.OID_INTERVAL)),
            PostgresRowCodec(Format.Binary)
        )

    /** Reads the raw Int64 µs from a 16-byte INTERVAL binary payload. */
    private def readMicros(bytes: Span[Byte]): Long =
        ((bytes(0) & 0xffL) << 56) |
            ((bytes(1) & 0xffL) << 48) |
            ((bytes(2) & 0xffL) << 40) |
            ((bytes(3) & 0xffL) << 32) |
            ((bytes(4) & 0xffL) << 24) |
            ((bytes(5) & 0xffL) << 16) |
            ((bytes(6) & 0xffL) << 8) |
            (bytes(7) & 0xffL)
    end readMicros

    /** Reads the raw Int32 days from a 16-byte INTERVAL binary payload (offset 8). */
    private def readDays(bytes: Span[Byte]): Int =
        ((bytes(8) & 0xff) << 24) |
            ((bytes(9) & 0xff) << 16) |
            ((bytes(10) & 0xff) << 8) |
            (bytes(11) & 0xff)
    end readDays

    /** Reads the raw Int32 months from a 16-byte INTERVAL binary payload (offset 12). */
    private def readMonths(bytes: Span[Byte]): Int =
        ((bytes(12) & 0xff) << 24) |
            ((bytes(13) & 0xff) << 16) |
            ((bytes(14) & 0xff) << 8) |
            (bytes(15) & 0xff)
    end readMonths

    "Period encodes as INTERVAL with months and days, OID 1186 Binary" in {
        val period = java.time.Period.of(1, 6, 15)
        val params = PostgresParamWriter.write(summon[kyo.SqlSchema.Column[java.time.Period]], period)
        assert(params.size == 1)
        assert(params(0).encoder.oid == PostgresEncoder.OID_INTERVAL)
        assert(params(0).encoder.format == Format.Binary)
        succeed
    }

    "Period.ofMonths(13) encodes months=13, days=0, micros=0" in {
        val bytes = pgPeriodBytes(java.time.Period.ofMonths(13))
        assert(bytes.size == 16)
        assert(readMicros(bytes) == 0L)
        assert(readDays(bytes) == 0)
        assert(readMonths(bytes) == 13)
        succeed
    }

    "Period.ofDays(5) encodes months=0, days=5, micros=0" in {
        val bytes = pgPeriodBytes(java.time.Period.ofDays(5))
        assert(bytes.size == 16)
        assert(readMicros(bytes) == 0L)
        assert(readDays(bytes) == 5)
        assert(readMonths(bytes) == 0)
        succeed
    }

    "Period.of(1, 6, 15) encodes months=18 (1*12+6), days=15, micros=0" in {
        val bytes = pgPeriodBytes(java.time.Period.of(1, 6, 15))
        assert(bytes.size == 16)
        assert(readMicros(bytes) == 0L)
        assert(readDays(bytes) == 15)
        assert(readMonths(bytes) == 18) // toTotalMonths: 1*12 + 6 = 18
        succeed
    }

    "negative Period encodes with negative months and days" in {
        val bytes = pgPeriodBytes(java.time.Period.of(-1, -3, -10))
        assert(bytes.size == 16)
        assert(readMicros(bytes) == 0L)
        assert(readDays(bytes) == -10)
        assert(readMonths(bytes) == -15) // -1*12 + -3 = -15
        succeed
    }

    "Period.ZERO encodes as all-zeros" in {
        val bytes = pgPeriodBytes(java.time.Period.ZERO)
        assert(bytes.size == 16)
        assert(readMicros(bytes) == 0L)
        assert(readDays(bytes) == 0)
        assert(readMonths(bytes) == 0)
        succeed
    }

    "Period decodes from INTERVAL with months and days" in {
        val original = java.time.Period.of(2, 3, 7)
        val row      = periodRow(pgPeriodBytes(original))
        kyo.Abort.run(row.decode[java.time.Period]).eval match
            case kyo.Result.Success(decoded) =>
                assert(decoded.toTotalMonths == original.toTotalMonths)
                assert(decoded.getDays == original.getDays)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Period decode raises Decode when the µs field is non-zero" in {
        // A shape Period itself cannot produce (Period has no µs lane), crafted to prove the decoder
        // rejects it rather than silently dropping the sub-day part.
        val arr = new Array[Byte](16)
        arr(7) = 1.toByte // micros = 1, months = 0, days = 0
        val row = periodRow(Span.from(arr))
        kyo.Abort.run[SqlDecodeException](row.decode[java.time.Period]).eval match
            case kyo.Result.Failure(_: SqlDecodeException) => succeed
            case other                                     => fail(s"expected typed Decode failure, got $other")
        end match
    }

    // `Period.equals` compares years, months and days field by field, so a round-trip that agrees on
    // `toTotalMonths` can still hand back a value that is not equal to what was written. The leaves around this one
    // assert the total, which leaves the field split unpinned and lets the two backends' decodes drift apart. The
    // normalised split is what both are held to, so one written Period reads back as one value whichever backend
    // answered.
    "a non-normalised Period decodes to the normalised split, the value MySQL also returns" in {
        val row = periodRow(pgPeriodBytes(java.time.Period.ofMonths(14)))
        kyo.Abort.run(row.decode[java.time.Period]).eval match
            case kyo.Result.Success(decoded) =>
                assert(decoded == java.time.Period.of(1, 2, 0), s"14 months must decode as P1Y2M, got $decoded")
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "Period round-trips through the INTERVAL wire" in {
        val cases = Seq(
            java.time.Period.of(1, 6, 15),
            java.time.Period.of(-2, -5, -20),
            java.time.Period.ofMonths(13),
            java.time.Period.ofDays(5)
        )
        cases.foreach { original =>
            val row = periodRow(pgPeriodBytes(original))
            kyo.Abort.run(row.decode[java.time.Period]).eval match
                case kyo.Result.Success(decoded) =>
                    assert(decoded.toTotalMonths == original.toTotalMonths, s"months mismatch for $original")
                    assert(decoded.getDays == original.getDays, s"days mismatch for $original")
                case other => fail(s"Expected Success for $original but got $other")
            end match
        }
        succeed
    }

end PostgresEncoderIntervalTest
