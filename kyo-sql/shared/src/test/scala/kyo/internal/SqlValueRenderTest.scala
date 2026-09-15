package kyo.internal

import kyo.Chunk
import kyo.Maybe
import kyo.Test

/** Unit tests for [[SqlValueRender]], the one rendering every backend and both wire formats answer with.
  *
  * The renderer takes neutral fields rather than a JDK type, so these leaves are the whole specification of what a value reads as: there
  * is no server in the loop and no platform-supplied `toString` to fall back on. Where a form was chosen because two engines disagreed,
  * the leaf says which disagreement it settles.
  *
  * These live in `shared` deliberately. The render logic was JVM-only in effect before this, covered only by a container suite that runs
  * on one platform, which is how a rendering that used `Float.toString` shipped while answering `12345.599609375` on Scala.js.
  */
class SqlValueRenderTest extends Test:

    "integer" - {
        "renders decimal digits" in {
            assert(SqlValueRender.integer(BigInt(42)) == "42")
            assert(SqlValueRender.integer(BigInt(-7)) == "-7")
            assert(SqlValueRender.integer(BigInt(0)) == "0")
        }

        // The carrier is BigInt because the union of the engines' integer domains does not fit a Long: an unsigned
        // 64-bit column reaches 2^64-1, which a signed Long cannot hold at all.
        "renders a value past the signed 64-bit range" in {
            val max = BigInt("18446744073709551615")
            assert(SqlValueRender.integer(max) == "18446744073709551615")
        }
    }

    "decimal" - {
        "keeps the scale the column declared" in {
            assert(SqlValueRender.decimal(BigDecimal("2.50")) == "2.50")
            assert(SqlValueRender.decimal(BigDecimal("42")) == "42")
        }

        // BigDecimal.toString takes exponent notation once the adjusted exponent falls below -6; no engine's
        // fixed-point output ever does.
        "never takes exponent notation" in {
            assert(SqlValueRender.decimal(BigDecimal("0.0000001")) == "0.0000001")
            assert(SqlValueRender.decimal(BigDecimal("0.000000000001")) == "0.000000000001")
        }
    }

    "float" - {
        "drops the .0 a whole value would otherwise carry" in {
            assert(SqlValueRender.float8(1.0) == "1")
            assert(SqlValueRender.float8(100.0) == "100")
            assert(SqlValueRender.float8(1e6) == "1000000")
        }

        "stays plain inside the width's digit band and takes the exponent outside it" in {
            assert(SqlValueRender.float8(1e14) == "100000000000000")
            assert(SqlValueRender.float8(1e15) == "1e+15")
            assert(SqlValueRender.float8(1.234567890123456e15) == "1.234567890123456e+15")
        }

        // The two widths do not share the band, which is the whole reason the width is a parameter: a four-byte 1e6
        // is past its exact-digit count and an eight-byte 1e6 is not.
        "bands the two widths separately" in {
            assert(SqlValueRender.float4(1e6f) == "1e+06")
            assert(SqlValueRender.float8(1e6) == "1000000")
            assert(SqlValueRender.float4(100000f) == "100000")
            assert(SqlValueRender.float4(1234567f) == "1.234567e+06")
        }

        "pads the exponent to two digits" in {
            assert(SqlValueRender.float8(1e-5) == "1e-05")
            assert(SqlValueRender.float8(-1.5e-7) == "-1.5e-07")
            assert(SqlValueRender.float8(6.02e23) == "6.02e+23")
        }

        // The boundary where a scale-carrying plain rendering would keep a zero the value does not have.
        "leaves no trailing zero at the plain boundary" in {
            assert(SqlValueRender.float8(1e-4) == "0.0001")
            assert(SqlValueRender.float8(2e-4) == "0.0002")
            assert(SqlValueRender.float4(1e-4f) == "0.0001")
            assert(SqlValueRender.float8(1.2345e-4) == "0.00012345")
        }

        // The digits come from a round-trip search rather than from a platform's toString, which on Scala.js widens
        // a float to a double before printing and spells -0.0 as `0`.
        "renders a float at its own width, not widened" in {
            assert(SqlValueRender.float4(0.1f) == "0.1")
            assert(SqlValueRender.float4(12345.6f) == "12345.6")
            assert(SqlValueRender.float4(3.4e38f) == "3.4e+38")
        }

        "keeps the sign of a negative zero" in {
            assert(SqlValueRender.float8(-0.0) == "-0")
            assert(SqlValueRender.float8(0.0) == "0")
            assert(SqlValueRender.float4(-0.0f) == "-0")
        }

        "spells the non-finite values as words" in {
            assert(SqlValueRender.float8(Double.PositiveInfinity) == "Infinity")
            assert(SqlValueRender.float8(Double.NegativeInfinity) == "-Infinity")
            assert(SqlValueRender.float8(Double.NaN) == "NaN")
            assert(SqlValueRender.float4(Float.NaN) == "NaN")
        }
    }

    // A bool is `true`/`false` on both engines' literal syntax. One of them writes `t` in its own text output, which
    // is engine identity rather than the value.
    "bool renders the word, not an engine's letter" in {
        assert(SqlValueRender.bool(true) == "true")
        assert(SqlValueRender.bool(false) == "false")
    }

    "date" - {
        "renders the year padded to four digits" in {
            assert(SqlValueRender.date(2026, 8, 25, bc = false) == "2026-08-25")
            assert(SqlValueRender.date(100, 2, 3, bc = false) == "0100-02-03")
        }

        // Fields rather than a LocalDate: that type numbers 1 BC as year 0 and 44 BC as -43, and prefixes a `+` to
        // any year of five digits or more.
        "renders a year within its era, and a wide year without a sign" in {
            assert(SqlValueRender.date(1, 1, 1, bc = true) == "0001-01-01 BC")
            assert(SqlValueRender.date(44, 3, 15, bc = true) == "0044-03-15 BC")
            assert(SqlValueRender.date(10000, 1, 1, bc = false) == "10000-01-01")
        }

        // Pins that the function is total over its fields. No decoder produces a zero date: one engine refuses to store one under its
        // default sql_mode, and where a permissive mode let one in the decoders refuse it under both wire formats. This is a unit test of
        // the rendering, not a claim that such a value arrives.
        "renders a zero date digit for digit" in {
            assert(SqlValueRender.date(0, 0, 0, bc = false) == "0000-00-00")
            assert(SqlValueRender.date(2024, 0, 15, bc = false) == "2024-00-15")
        }
    }

    "time" - {
        "renders a time of day with the seconds always present" in {
            assert(SqlValueRender.time(false, 10L, 0, 0, 0) == "10:00:00")
            assert(SqlValueRender.time(false, 23L, 59, 59, 0) == "23:59:59")
            assert(SqlValueRender.time(false, 0L, 0, 0, 0) == "00:00:00")
        }

        // The kind's domain is the union of both engines': a signed span that reaches past a day in one, and a
        // 24:00:00 endpoint in the other. Neither fits a LocalTime.
        "renders a span past a day, and a negative one" in {
            assert(SqlValueRender.time(false, 838L, 59, 59, 0) == "838:59:59")
            assert(SqlValueRender.time(true, 838L, 59, 59, 0) == "-838:59:59")
            assert(SqlValueRender.time(true, 10L, 0, 0, 0) == "-10:00:00")
            assert(SqlValueRender.time(false, 24L, 0, 0, 0) == "24:00:00")
        }

        // Trimming makes the rendering value-determined rather than a function of the scale a column declares: an
        // engine pads a six-digit column to `.000000` and this does not.
        "trims the fraction's trailing zeros" in {
            assert(SqlValueRender.time(false, 10L, 0, 0, 500000) == "10:00:00.5")
            assert(SqlValueRender.time(false, 10L, 0, 0, 123456) == "10:00:00.123456")
            assert(SqlValueRender.time(false, 10L, 0, 0, 100) == "10:00:00.0001")
        }
    }

    // Always at least hours and minutes: one engine writes a whole-hour zone bare, and the other rejects that
    // spelling in a temporal literal while accepting the wider one.
    "offset renders hours and minutes, widening to seconds when the zone has them" in {
        assert(SqlValueRender.offset(0) == "+00:00")
        assert(SqlValueRender.offset(2 * 3600) == "+02:00")
        assert(SqlValueRender.offset(-3 * 3600) == "-03:00")
        assert(SqlValueRender.offset(5 * 3600 + 30 * 60) == "+05:30")
        assert(SqlValueRender.offset(5 * 3600 + 30 * 60 + 33) == "+05:30:33")
    }

    "dateTime" - {
        "separates the date and time with a space, not a T" in {
            assert(SqlValueRender.dateTime(2026, 8, 25, bc = false, 10, 0, 0, 0) == "2026-08-25 10:00:00")
            assert(SqlValueRender.dateTime(2026, 8, 25, bc = false, 10, 0, 0, 500000) == "2026-08-25 10:00:00.5")
        }

        "puts the era after the whole value" in {
            assert(SqlValueRender.dateTime(1, 1, 1, bc = true, 0, 0, 0, 0) == "0001-01-01 00:00:00 BC")
        }
    }

    // An instant has no zone of its own, so one has to be chosen, and it must not be the session's: both engines
    // hand a stored instant back shifted by a per-session setting.
    "timestamp renders at UTC" in {
        val epoch = java.time.LocalDateTime.of(2026, 8, 25, 10, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC)
        assert(SqlValueRender.timestamp(epoch, 0) == "2026-08-25 10:00:00+00:00")
        assert(SqlValueRender.timestamp(epoch, 500000) == "2026-08-25 10:00:00.5+00:00")
    }

    "timeWithOffset carries the zone after the time" in {
        assert(SqlValueRender.timeWithOffset(10, 0, 0, 0, 2 * 3600) == "10:00:00+02:00")
        assert(SqlValueRender.timeWithOffset(12, 0, 0, 0, 5 * 3600 + 30 * 60 + 33) == "12:00:00+05:30:33")
    }

    // One spelling for a value an engine offers four of, chosen by a session setting.
    "interval renders ISO-8601" in {
        assert(SqlValueRender.interval(14, 3, 4L * 3600_000_000L + 5L * 60_000_000L + 6_000_000L) == "P1Y2M3DT4H5M6S")
        assert(SqlValueRender.interval(0, 0, 90L * 60_000_000L) == "PT1H30M")
        assert(SqlValueRender.interval(0, 0, 0) == "PT0S")
        assert(SqlValueRender.interval(-14, -3, -(4L * 3600_000_000L + 5L * 60_000_000L + 6_000_000L)) == "P-1Y-2M-3DT-4H-5M-6S")
    }

    "bytes" - {
        "renders lowercase hex behind a prefix" in {
            assert(SqlValueRender.bytes(Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)) == "\\xdeadbeef")
            assert(SqlValueRender.bytes(Array[Byte](0x00, 0x0f, 0x7f)) == "\\x000f7f")
        }

        "renders no digits for an empty value" in {
            assert(SqlValueRender.bytes(Array.empty[Byte]) == "\\x")
        }
    }

    "array" - {
        "renders elements between braces" in {
            assert(SqlValueRender.array(Chunk(Maybe("1"), Maybe("2"))) == "{1,2}")
            assert(SqlValueRender.array(Chunk.empty) == "{}")
        }

        // The typed decode of an array declines a NULL element because the Scala element type cannot hold one; a
        // rendering has no such bottleneck.
        "renders an absent element rather than refusing it" in {
            assert(SqlValueRender.array(Chunk(Maybe("1"), Maybe.empty, Maybe("3"))) == "{1,NULL,3}")
        }

        "quotes an element the grammar would otherwise misread" in {
            assert(SqlValueRender.array(Chunk(Maybe("a b"))) == """{"a b"}""")
            assert(SqlValueRender.array(Chunk(Maybe("a,b"))) == """{"a,b"}""")
            assert(SqlValueRender.array(Chunk(Maybe(""))) == """{""}""")
            assert(SqlValueRender.array(Chunk(Maybe("{}"))) == """{"{}"}""")
        }

        // A bare NULL would be indistinguishable from an absent element, so the text has to be quoted.
        "quotes an element whose text is NULL" in {
            assert(SqlValueRender.array(Chunk(Maybe("NULL"))) == """{"NULL"}""")
            assert(SqlValueRender.array(Chunk(Maybe("null"))) == """{"null"}""")
        }

        "escapes a quote and a backslash inside an element" in {
            assert(SqlValueRender.array(Chunk(Maybe("a\"b"))) == """{"a\"b"}""")
            assert(SqlValueRender.array(Chunk(Maybe("a\\b"))) == """{"a\\b"}""")
        }

        "leaves an ordinary element bare" in {
            assert(SqlValueRender.array(Chunk(Maybe("ada"), Maybe("bob"))) == "{ada,bob}")
        }
    }

end SqlValueRenderTest
