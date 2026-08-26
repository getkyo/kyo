package kyo.internal.postgres.types

import java.nio.charset.StandardCharsets
import kyo.Span
import kyo.SqlCodec.Format
import kyo.SqlDecodeByteaException
import kyo.SqlDecodeNumericException
import kyo.Test

/** Unit tests for the [[PostgresDecoder]] text-format paths, driven from the rendering the server writes rather than from an encoder.
  *
  * Every column of a simple query comes back in text format, so each decoder's text arm is a read path in its own right. The leaves here
  * hand it the literal rendering PostgreSQL produces, which pins both the shape accepted and the value recovered; the binary arms are
  * covered where they are reached from, in `PostgresRowReaderTest` and the per-type `PostgresEncoder*Test` suites.
  *
  * `bytea` gets the widest treatment because its rendering is chosen by the session's `bytea_output` rather than by the column type, so
  * neither the column OID nor the requested Scala type says which of the two shapes the bytes are in.
  */
class PostgresDecoderTest extends Test:

    private def textBytes(s: String): Span[Byte] =
        Span.from(s.getBytes(StandardCharsets.UTF_8))

    private def decodeText(s: String): Seq[Byte] =
        PostgresDecoder.bytea.read(Format.Text, textBytes(s)).toArray.toSeq

    private def decodeBool(s: String): Boolean =
        PostgresDecoder.bool.read(Format.Text, textBytes(s))

    // ── bytea_output = hex, the default since PG 9.0 ─────────────────────────────────

    "bytea hex decodes two digits per byte" in {
        assert(decodeText("\\x00ff7f") == Seq[Byte](0x00.toByte, 0xff.toByte, 0x7f.toByte))
    }

    "bytea hex accepts upper-case digits" in {
        assert(decodeText("\\xDEADBEEF") == Seq[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte))
    }

    "bytea hex decodes an empty payload as no bytes" in {
        assert(decodeText("\\x") == Seq.empty[Byte])
    }

    // `hex.grouped(2)` leaves an odd payload's final group one character long, and parsing that as a byte decodes
    // `\x0102 0` to three bytes ending in 0x00 instead of reporting a truncated payload.
    "bytea hex rejects an odd digit count instead of parsing a half byte" in {
        val ex = intercept[SqlDecodeByteaException](decodeText("\\x0102030"))
        assert(ex.subtype == SqlDecodeByteaException.Subtype.OddHexLength, s"got subtype ${ex.subtype}")
        assert(ex.payloadLength == 7, s"expected the payload's 7 characters reported, got ${ex.payloadLength}")
    }

    // `Integer.parseInt(h, 16)` raises a NumberFormatException, which is not a SqlException and so escapes every
    // `Abort.recover[SqlException]` on the decode path unless the decoder reports the digit itself.
    "bytea hex rejects a non-hex digit as a typed decode failure" in {
        val ex = intercept[SqlDecodeByteaException](decodeText("\\x00zz"))
        assert(ex.subtype == SqlDecodeByteaException.Subtype.HexDigit, s"got subtype ${ex.subtype}")
    }

    // ── bytea_output = escape ────────────────────────────────────────────────────────
    //
    // `bytea_output` is settable per session, per database and per role, so a result can arrive in this
    // rendering on a connection that asked for nothing unusual. Returning the bytes as-is would make `\001`
    // the four ASCII characters `\`, `0`, `0`, `1` instead of the single byte 0x01.

    "bytea escape decodes a three-digit octal escape as one byte" in {
        assert(decodeText("\\001") == Seq[Byte](0x01.toByte))
    }

    "bytea escape decodes a doubled backslash as one backslash" in {
        assert(decodeText("\\\\") == Seq[Byte]('\\'.toByte))
    }

    "bytea escape keeps printable bytes literally" in {
        assert(decodeText("abc") == Seq[Byte]('a'.toByte, 'b'.toByte, 'c'.toByte))
    }

    "bytea escape decodes a mixed payload in order" in {
        // What PostgreSQL renders for the four bytes 0x01 'a' 0x5c 0xff under bytea_output = escape.
        assert(decodeText("\\001a\\\\\\377") == Seq[Byte](0x01.toByte, 'a'.toByte, 0x5c.toByte, 0xff.toByte))
    }

    "bytea escape decodes the full octal range at both ends" in {
        assert(decodeText("\\000\\377") == Seq[Byte](0x00.toByte, 0xff.toByte))
    }

    "bytea escape decodes an empty payload as no bytes" in {
        assert(decodeText("") == Seq.empty[Byte])
    }

    "bytea escape rejects a backslash that starts no escape sequence" in {
        val ex = intercept[SqlDecodeByteaException](decodeText("\\9"))
        assert(ex.subtype == SqlDecodeByteaException.Subtype.EscapeSequence, s"got subtype ${ex.subtype}")
    }

    "bytea escape rejects a trailing backslash" in {
        val ex = intercept[SqlDecodeByteaException](decodeText("ab\\"))
        assert(ex.subtype == SqlDecodeByteaException.Subtype.EscapeSequence, s"got subtype ${ex.subtype}")
    }

    // An octal escape whose digits run past the end of the payload is truncated rather than valid, and the
    // three-digit check is what separates it from `\1` followed by nothing.
    "bytea escape rejects a truncated octal escape" in {
        val ex = intercept[SqlDecodeByteaException](decodeText("\\01"))
        assert(ex.subtype == SqlDecodeByteaException.Subtype.EscapeSequence, s"got subtype ${ex.subtype}")
    }

    // ── Binary format is unchanged: the bytes ARE the value ──────────────────────────

    "bytea binary passes the bytes through untouched, including a backslash" in {
        val raw = Span.from(Array[Byte]('\\'.toByte, 0x00.toByte, 0x30.toByte))
        assert(PostgresDecoder.bytea.read(Format.Binary, raw).toArray.toSeq == Seq[Byte]('\\'.toByte, 0x00.toByte, 0x30.toByte))
    }

    // ── bool text decoding is case-insensitive, matching PostgreSQL's own `bool` input parser ──

    "bool text decodes the server's own t/f rendering" in {
        assert(decodeBool("t"))
        assert(!decodeBool("f"))
    }

    // The recognised-rendering set covers every case spelling of the boolean literals, so "TRUE", "True" and
    // "true" all decode as the literal. A set that included one spelling but not another would send the
    // unmatched one to the numeric parse, which raises a decode exception.
    "bool text accepts TRUE, True and true identically" in {
        assert(decodeBool("TRUE"))
        assert(decodeBool("True"))
        assert(decodeBool("true"))
    }

    "bool text accepts FALSE, False and false identically" in {
        assert(!decodeBool("FALSE"))
        assert(!decodeBool("False"))
        assert(!decodeBool("false"))
    }

    "bool text accepts yes/no and on/off case-insensitively" in {
        assert(decodeBool("YES"))
        assert(decodeBool("On"))
        assert(!decodeBool("NO"))
        assert(!decodeBool("Off"))
    }

    "bool text still accepts the numeric fallback for 0 and 1" in {
        assert(decodeBool("1"))
        assert(!decodeBool("0"))
    }

    // PostgreSQL only ever renders `t` or `f` in text format, so this arm is unreachable through the
    // driver's own read paths; it is exercised here directly to pin the typed-failure answer.
    "bool text rejects an unrecognised rendering as a typed decode failure rather than answering false" in {
        val ex = intercept[SqlDecodeNumericException](decodeBool("maybe"))
        assert(ex.text == "maybe")
        assert(ex.subtype == SqlDecodeNumericException.Subtype.Parse)
    }

    // ── The three array decoders resolve the element format from the array ───────────
    //
    // These are the decoders `nextArrayOfInt`, `nextArrayOfString` and `nextArrayOfJson` reach. An array reader
    // built with no format passes `Format.Binary` for every element, which sends a `simpleQuery` result to the
    // binary header parser instead of reading its elements as raw UTF-8 text.

    "int4Array decodes a text-format rendering" in {
        val bytes = textBytes("{1,-2,3}")
        assert(PostgresDecoder.int4Array.read(Format.Text, bytes) == kyo.Chunk(1, -2, 3))
    }

    "textArray decodes a text-format rendering, resolving quotes" in {
        val bytes = textBytes("""{a,"b,c",""}""")
        assert(PostgresDecoder.textArray.read(Format.Text, bytes) == kyo.Chunk("a", "b,c", ""))
    }

    "jsonbArray decodes a text-format rendering, whose elements carry no version byte" in {
        // A text-format jsonb[] renders each document as quoted JSON with the inner quotes escaped.
        val bytes = textBytes("""{"{\"a\": 1}","{\"b\": 2}"}""")
        assert(PostgresDecoder.jsonbArray.read(Format.Text, bytes) == kyo.Chunk("""{"a": 1}""", """{"b": 2}"""))
    }

    // ── The numeric decoders parse the digits a text-format column renders ───────────
    //
    // A text-format numeric column is its own rendering, not a big-endian payload, so each of these parses the
    // digits rather than reading bytes at the width its Scala type would have written.

    "int2 text decodes a rendered short" in {
        assert(PostgresDecoder.int2.read(Format.Text, textBytes("1234")) == 1234.toShort)
    }

    "int4 text decodes a rendered int" in {
        assert(PostgresDecoder.int4.read(Format.Text, textBytes("42")) == 42)
    }

    "int8 text decodes a rendered long" in {
        assert(PostgresDecoder.int8.read(Format.Text, textBytes("123456789")) == 123456789L)
    }

    "float4 text decodes a rendered float" in {
        assert(PostgresDecoder.float4.read(Format.Text, textBytes("1.0")) == 1.0f)
    }

    "float8 text decodes a rendered double" in {
        assert(PostgresDecoder.float8.read(Format.Text, textBytes("1.0")) == 1.0)
    }

    // ── The temporal decoders read the renderings PostgreSQL writes ──────────────────
    //
    // The timestamp family is rendered with a space between the date and the time where ISO-8601 uses `T`, and
    // `timestamptz` closes with a two-digit offset (`+00`) where ISO-8601 wants `+00:00`; both are normalised
    // before the parse, so a rendering that reaches these arms unaltered is what pins that normalisation.

    "timestamptz text decodes the space-separated rendering with a two-digit offset" in {
        val expected = java.time.Instant.parse("2024-01-15T12:34:56Z")
        val decoded  = PostgresDecoder.timestamptz.read(Format.Text, textBytes("2024-01-15 12:34:56.000000+00"))
        assert(decoded.toJava.getEpochSecond == expected.getEpochSecond)
    }

    "date text decodes the YYYY-MM-DD rendering" in {
        val decoded = PostgresDecoder.date.read(Format.Text, textBytes("2024-03-15"))
        assert(decoded.equals(java.time.LocalDate.of(2024, 3, 15)))
    }

    "timestamp text decodes the space-separated rendering" in {
        val decoded = PostgresDecoder.timestamp.read(Format.Text, textBytes("2024-06-01 10:30:00.000000"))
        assert(decoded.equals(java.time.LocalDateTime.of(2024, 6, 1, 10, 30, 0)))
    }

    "time text decodes the HH:MM:SS rendering with microseconds" in {
        val decoded = PostgresDecoder.time.read(Format.Text, textBytes("14:30:15.000000"))
        assert(decoded.equals(java.time.LocalTime.of(14, 30, 15)))
    }

    "int4Array still decodes the binary wire form" in {
        val buf = new java.io.ByteArrayOutputStream
        def int32BE(v: Int): Unit =
            buf.write((v >> 24) & 0xff)
            buf.write((v >> 16) & 0xff)
            buf.write((v >> 8) & 0xff)
            buf.write(v & 0xff)
        end int32BE
        int32BE(1)  // ndim
        int32BE(0)  // hasNulls
        int32BE(23) // elemOID int4
        int32BE(2)  // dim_size
        int32BE(1)  // lbound
        int32BE(4)
        int32BE(7)
        int32BE(4)
        int32BE(8)
        assert(PostgresDecoder.int4Array.read(Format.Binary, Span.from(buf.toByteArray)) == kyo.Chunk(7, 8))
    }

    // ── float4 and float8 rendered as the server renders them ───────────────────────
    //
    // `float4Text` and `float8Text` re-render a binary-protocol float so it reads as the string the text
    // protocol would have carried for the same stored value. Every expectation below is what PostgreSQL 16
    // writes for that value at the default `extra_float_digits`, read off the server rather than derived
    // from Java, because Java's own rendering is what these decoders exist to correct.
    //
    // The two widths do NOT share a threshold. The server stays in plain notation while the decimal exponent
    // is below the type's significant-digit count and switches after: FLT_DIG (6) for a float4, DBL_DIG (15)
    // for a float8. So a float4 1e6 is `1e+06` where a float8 1e6 is `1000000`.

    private def binaryFloat4(value: Float): Span[Byte] =
        val bits = java.lang.Float.floatToIntBits(value)
        Span.from(Array.tabulate(4)(i => (bits >>> (24 - i * 8)).toByte))

    private def binaryFloat8(value: Double): Span[Byte] =
        val bits = java.lang.Double.doubleToLongBits(value)
        Span.from(Array.tabulate(8)(i => (bits >>> (56 - i * 8)).toByte))

    private def renderFloat4(value: Float): String =
        PostgresDecoder.float4Text.read(Format.Binary, binaryFloat4(value))

    private def renderFloat8(value: Double): String =
        PostgresDecoder.float8Text.read(Format.Binary, binaryFloat8(value))

    "float8 drops the .0 Java appends to a whole value" in {
        assert(renderFloat8(1.0) == "1", s"1.0: expected 1, got ${renderFloat8(1.0)}")
        assert(renderFloat8(100.0) == "100", s"100.0: expected 100, got ${renderFloat8(100.0)}")
        assert(renderFloat8(1e6) == "1000000", s"1e6: expected 1000000, got ${renderFloat8(1e6)}")
    }

    "float8 stays plain below DBL_DIG and takes the exponent after it" in {
        assert(renderFloat8(1e14) == "100000000000000", s"1e14: expected 100000000000000, got ${renderFloat8(1e14)}")
        assert(renderFloat8(1e15) == "1e+15", s"1e15: expected 1e+15, got ${renderFloat8(1e15)}")
        assert(
            renderFloat8(1.234567890123456e15) == "1.234567890123456e+15",
            s"1.234567890123456e15: expected 1.234567890123456e+15, got ${renderFloat8(1.234567890123456e15)}"
        )
    }

    "float8 pads the exponent to two digits, as the server does" in {
        assert(renderFloat8(1e-5) == "1e-05", s"1e-5: expected 1e-05, got ${renderFloat8(1e-5)}")
        assert(renderFloat8(-1.5e-7) == "-1.5e-07", s"-1.5e-7: expected -1.5e-07, got ${renderFloat8(-1.5e-7)}")
        assert(renderFloat8(6.02e23) == "6.02e+23", s"6.02e23: expected 6.02e+23, got ${renderFloat8(6.02e23)}")
    }

    // `BigDecimal("1.0E-4").toPlainString` is "0.00010", and trimming only a trailing ".0" leaves that last
    // zero standing. -4 is the one negative exponent reaching the plain branch at all, since Java writes
    // plain from 1e-3 up, so every d x 10^-4 came out one digit wider than the server writes it.
    "float8 leaves no trailing zero at the plain boundary" in {
        assert(renderFloat8(1e-4) == "0.0001", s"1e-4: expected 0.0001, got ${renderFloat8(1e-4)}")
        assert(renderFloat8(2e-4) == "0.0002", s"2e-4: expected 0.0002, got ${renderFloat8(2e-4)}")
        assert(renderFloat8(9e-4) == "0.0009", s"9e-4: expected 0.0009, got ${renderFloat8(9e-4)}")
        assert(renderFloat8(1.2345e-4) == "0.00012345", s"1.2345e-4: expected 0.00012345, got ${renderFloat8(1.2345e-4)}")
    }

    "float8 keeps the sign of a negative zero" in {
        assert(renderFloat8(-0.0) == "-0", s"-0.0: expected -0, got ${renderFloat8(-0.0)}")
        assert(renderFloat8(0.0) == "0", s"0.0: expected 0, got ${renderFloat8(0.0)}")
    }

    "float8 spells the non-finite values as the server spells them" in {
        assert(
            renderFloat8(Double.PositiveInfinity) == "Infinity",
            s"Double.PositiveInfinity: expected Infinity, got ${renderFloat8(Double.PositiveInfinity)}"
        )
        assert(
            renderFloat8(Double.NegativeInfinity) == "-Infinity",
            s"Double.NegativeInfinity: expected -Infinity, got ${renderFloat8(Double.NegativeInfinity)}"
        )
        assert(renderFloat8(Double.NaN) == "NaN", s"Double.NaN: expected NaN, got ${renderFloat8(Double.NaN)}")
    }

    // Rendering a float4 at the float8 threshold keeps plain notation across nine exponents the server has
    // already left, which is most of a float4's usable range: everything from 1e6 up read back in the wrong
    // notation.
    "float4 takes the exponent at FLT_DIG, not at DBL_DIG" in {
        assert(renderFloat4(1e6f) == "1e+06", s"1e6f: expected 1e+06, got ${renderFloat4(1e6f)}")
        assert(renderFloat4(1.5e6f) == "1.5e+06", s"1.5e6f: expected 1.5e+06, got ${renderFloat4(1.5e6f)}")
        assert(renderFloat4(1234567f) == "1.234567e+06", s"1234567f: expected 1.234567e+06, got ${renderFloat4(1234567f)}")
        assert(renderFloat4(1e7f) == "1e+07", s"1e7f: expected 1e+07, got ${renderFloat4(1e7f)}")
        assert(renderFloat4(3.4e38f) == "3.4e+38", s"3.4e38f: expected 3.4e+38, got ${renderFloat4(3.4e38f)}")
    }

    "float4 stays plain below FLT_DIG" in {
        assert(renderFloat4(1f) == "1", s"1f: expected 1, got ${renderFloat4(1f)}")
        assert(renderFloat4(100f) == "100", s"100f: expected 100, got ${renderFloat4(100f)}")
        assert(renderFloat4(100000f) == "100000", s"100000f: expected 100000, got ${renderFloat4(100000f)}")
        assert(renderFloat4(123456f) == "123456", s"123456f: expected 123456, got ${renderFloat4(123456f)}")
        assert(renderFloat4(12345.6f) == "12345.6", s"12345.6f: expected 12345.6, got ${renderFloat4(12345.6f)}")
        assert(renderFloat4(1.2345f) == "1.2345", s"1.2345f: expected 1.2345, got ${renderFloat4(1.2345f)}")
    }

    "float4 leaves no trailing zero at the plain boundary" in {
        assert(renderFloat4(1e-4f) == "0.0001", s"1e-4f: expected 0.0001, got ${renderFloat4(1e-4f)}")
        assert(renderFloat4(2e-4f) == "0.0002", s"2e-4f: expected 0.0002, got ${renderFloat4(2e-4f)}")
    }

    "float4 spells the non-finite values as the server spells them" in {
        assert(
            renderFloat4(Float.PositiveInfinity) == "Infinity",
            s"Float.PositiveInfinity: expected Infinity, got ${renderFloat4(Float.PositiveInfinity)}"
        )
        assert(
            renderFloat4(Float.NegativeInfinity) == "-Infinity",
            s"Float.NegativeInfinity: expected -Infinity, got ${renderFloat4(Float.NegativeInfinity)}"
        )
        assert(renderFloat4(Float.NaN) == "NaN", s"Float.NaN: expected NaN, got ${renderFloat4(Float.NaN)}")
    }

    // ── numeric rendered as `numeric_out` writes it ─────────────────────────────────
    //
    // A numeric's scale rides the wire, so the rendering keeps the trailing zeros it names. What it never takes is
    // exponent notation: `numeric_out` writes 0.0000001 plainly, while BigDecimal.toString switches to it once the
    // adjusted exponent is below -6. Expectations read off PostgreSQL 16.

    private def numericBinaryBytes(digits: Seq[Int], weight: Int, sign: Int, dscale: Int): Span[Byte] =
        val buf = new java.io.ByteArrayOutputStream
        def writeInt16BE(v: Int): Unit =
            buf.write((v >> 8) & 0xff)
            buf.write(v & 0xff)
        writeInt16BE(digits.size)
        writeInt16BE(weight)
        writeInt16BE(sign)
        writeInt16BE(dscale)
        digits.foreach(writeInt16BE)
        Span.from(buf.toByteArray)
    end numericBinaryBytes

    "numeric renders a small value plainly, as numeric_out does" in {
        // 0.0000001: one base-10000 digit of 10, at weight -2, with dscale 7 (10 * 10000^-2 = 1e-7).
        val bytes = numericBinaryBytes(Seq(10), weight = -2, sign = 0, dscale = 7)
        assert(PostgresDecoder.numericText.read(Format.Binary, bytes) == "0.0000001")
    }

    "numeric keeps the trailing zeros its scale carries" in {
        // 2.50: digits 2 and 5000 at weight 0, with dscale 2.
        val bytes = numericBinaryBytes(Seq(2, 5000), weight = 0, sign = 0, dscale = 2)
        assert(PostgresDecoder.numericText.read(Format.Binary, bytes) == "2.50")
    }

    // ── the era, the wide years, and a second-precision zone ────────────────────────
    //
    // Three legal PostgreSQL values whose Java counterpart spells them differently. Expectations read off
    // PostgreSQL 16: a date's range runs from 4713 BC to 5874897 AD, and `timetz` accepts a zone to the second.

    private def binaryInt32(value: Int): Span[Byte] =
        Span.from(Array.tabulate(4)(i => ((value >>> (24 - i * 8)) & 0xff).toByte))

    private def binaryInt64(value: Long): Span[Byte] =
        Span.from(Array.tabulate(8)(i => ((value >>> (56 - i * 8)) & 0xff).toByte))

    /** A `date` column's binary payload: days from the PostgreSQL epoch, 2000-01-01. */
    private def dateBytes(date: java.time.LocalDate): Span[Byte] =
        binaryInt32(java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(2000, 1, 1), date).toInt)

    /** A `timetz` column's binary payload: microseconds since midnight, then the zone's seconds WEST of UTC. */
    private def timetzBytes(time: java.time.LocalTime, offsetSeconds: Int): Span[Byte] =
        val micros = binaryInt64(time.toNanoOfDay / 1000L)
        val zone   = binaryInt32(-offsetSeconds)
        Span.from(micros.toArray ++ zone.toArray)
    end timetzBytes

    "a date in the BC era is written with its era's year, not the proleptic one" in {
        // 1 BC is proleptic year 0, and 44 BC is -43.
        val one = PostgresDecoder.dateText.read(Format.Binary, dateBytes(java.time.LocalDate.of(0, 1, 1)))
        assert(one == "0001-01-01 BC", s"expected 0001-01-01 BC, got $one")
        val ides = PostgresDecoder.dateText.read(Format.Binary, dateBytes(java.time.LocalDate.of(-43, 3, 15)))
        assert(ides == "0044-03-15 BC", s"expected 0044-03-15 BC, got $ides")
    }

    "a date past four digits is written without the + LocalDate prefixes" in {
        val wide = PostgresDecoder.dateText.read(Format.Binary, dateBytes(java.time.LocalDate.of(10000, 1, 1)))
        assert(wide == "10000-01-01", s"expected 10000-01-01, got $wide")
    }

    "an ordinary date still pads its year to four digits" in {
        val padded = PostgresDecoder.dateText.read(Format.Binary, dateBytes(java.time.LocalDate.of(100, 2, 3)))
        assert(padded == "0100-02-03", s"expected 0100-02-03, got $padded")
        val plain = PostgresDecoder.dateText.read(Format.Binary, dateBytes(java.time.LocalDate.of(2026, 8, 25)))
        assert(plain == "2026-08-25", s"expected 2026-08-25, got $plain")
    }

    "a timetz zone keeps its seconds" in {
        // '12:00:00+05:30:33', which the server accepts and writes back whole.
        val offset   = 5 * 3600 + 30 * 60 + 33
        val rendered = PostgresDecoder.timetzText.read(Format.Binary, timetzBytes(java.time.LocalTime.NOON, offset))
        assert(rendered == "12:00:00+05:30:33", s"expected 12:00:00+05:30:33, got $rendered")
    }

    "a timetz zone on the hour or the minute stays short" in {
        val onHour = PostgresDecoder.timetzText.read(Format.Binary, timetzBytes(java.time.LocalTime.of(10, 0), 2 * 3600))
        assert(onHour == "10:00:00+02", s"expected 10:00:00+02, got $onHour")
        val onMinute = PostgresDecoder.timetzText.read(Format.Binary, timetzBytes(java.time.LocalTime.NOON, 5 * 3600 + 30 * 60))
        assert(onMinute == "12:00:00+05:30", s"expected 12:00:00+05:30, got $onMinute")
    }

end PostgresDecoderTest
