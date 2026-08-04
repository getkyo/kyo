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

end PostgresDecoderTest
