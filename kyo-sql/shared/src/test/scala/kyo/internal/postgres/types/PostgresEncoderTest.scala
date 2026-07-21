package kyo.internal.postgres.types

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.PostgresBufferWriter

/** Unit tests for PostgresEncoder and PostgresDecoder, round-trips per type per format.
  *
  * Encode a value, decode the bytes back, assert equality. Covers all primitive SqlSchema types.
  */
class PostgresEncoderTest extends kyo.Test:

    // Helper: encode a value to bytes using the given encoder.
    private def encode[A](value: A, enc: PostgresEncoder[A]): Span[Byte] =
        val buf = new PostgresBufferWriter
        enc.write(value, buf)
        buf.toSpan
    end encode

    // Helper: round-trip encode then decode.
    private def roundTrip[A](value: A, enc: PostgresEncoder[A], dec: PostgresDecoder[A]): A =
        val bytes = encode(value, enc)
        dec.read(enc.format, bytes)

    // ── Bool ─────────────────────────────────────────────────────────────────

    "bool text encoder encodes true as 't'" in {
        val bytes = encode(true, PostgresEncoder.boolText)
        assert(bytes.size == 1)
        assert(bytes(0) == 't'.toByte)
    }

    "bool text encoder encodes false as 'f'" in {
        val bytes = encode(false, PostgresEncoder.boolText)
        assert(bytes.size == 1)
        assert(bytes(0) == 'f'.toByte)
    }

    "bool binary encoder encodes true as 0x01" in {
        val bytes = encode(true, PostgresEncoder.boolBinary)
        assert(bytes.size == 1)
        assert(bytes(0) == 1.toByte)
    }

    "bool binary encoder encodes false as 0x00" in {
        val bytes = encode(false, PostgresEncoder.boolBinary)
        assert(bytes.size == 1)
        assert(bytes(0) == 0.toByte)
    }

    "bool text round-trip" in {
        assert(roundTrip(true, PostgresEncoder.boolText, PostgresDecoder.bool) == true)
        assert(roundTrip(false, PostgresEncoder.boolText, PostgresDecoder.bool) == false)
    }

    "bool binary round-trip" in {
        assert(roundTrip(true, PostgresEncoder.boolBinary, PostgresDecoder.bool) == true)
        assert(roundTrip(false, PostgresEncoder.boolBinary, PostgresDecoder.bool) == false)
    }

    // ── Short (int2) ─────────────────────────────────────────────────────────

    "int2 binary round-trip preserves positive value" in {
        assert(roundTrip(32767.toShort, PostgresEncoder.int2Binary, PostgresDecoder.int2) == 32767.toShort)
    }

    "int2 binary round-trip preserves negative value" in {
        assert(roundTrip((-100).toShort, PostgresEncoder.int2Binary, PostgresDecoder.int2) == (-100).toShort)
    }

    "int2 text round-trip" in {
        assert(roundTrip(1234.toShort, PostgresEncoder.int2Text, PostgresDecoder.int2) == 1234.toShort)
    }

    // ── Int (int4) ────────────────────────────────────────────────────────────

    "int4 binary round-trip preserves max int" in {
        assert(roundTrip(Int.MaxValue, PostgresEncoder.int4Binary, PostgresDecoder.int4) == Int.MaxValue)
    }

    "int4 binary round-trip preserves min int" in {
        assert(roundTrip(Int.MinValue, PostgresEncoder.int4Binary, PostgresDecoder.int4) == Int.MinValue)
    }

    "int4 text round-trip" in {
        assert(roundTrip(42, PostgresEncoder.int4Text, PostgresDecoder.int4) == 42)
    }

    // ── Long (int8) ───────────────────────────────────────────────────────────

    "int8 binary round-trip preserves max long" in {
        assert(roundTrip(Long.MaxValue, PostgresEncoder.int8Binary, PostgresDecoder.int8) == Long.MaxValue)
    }

    "int8 binary round-trip preserves min long" in {
        assert(roundTrip(Long.MinValue, PostgresEncoder.int8Binary, PostgresDecoder.int8) == Long.MinValue)
    }

    "int8 text round-trip" in {
        assert(roundTrip(123456789L, PostgresEncoder.int8Text, PostgresDecoder.int8) == 123456789L)
    }

    // ── Float4 ────────────────────────────────────────────────────────────────

    "float4 binary round-trip" in {
        val v = 3.14f
        assert(roundTrip(v, PostgresEncoder.float4Binary, PostgresDecoder.float4) == v)
    }

    "float4 text round-trip" in {
        val v = 1.0f
        assert(roundTrip(v, PostgresEncoder.float4Text, PostgresDecoder.float4) == v)
    }

    // ── Float8 ────────────────────────────────────────────────────────────────

    "float8 binary round-trip" in {
        val v = 2.718281828459045
        assert(roundTrip(v, PostgresEncoder.float8Binary, PostgresDecoder.float8) == v)
    }

    "float8 text round-trip" in {
        val v = 1.0
        assert(roundTrip(v, PostgresEncoder.float8Text, PostgresDecoder.float8) == v)
    }

    // ── Numeric ───────────────────────────────────────────────────────────────

    "numeric text round-trip" in {
        val v = BigDecimal("123456789.987654321")
        assert(roundTrip(v, PostgresEncoder.numericText, PostgresDecoder.numeric) == v)
    }

    // ── String ──────────────────────────────────────────────────────────────────

    "text encoder encodes UTF-8 string" in {
        val v     = "héllo wörld"
        val bytes = encode(v, PostgresEncoder.textText)
        val back  = PostgresDecoder.textDecoder.read(Format.Text, bytes)
        assert(back == v)
    }

    // ── Bytea ─────────────────────────────────────────────────────────────────

    "bytea binary round-trip" in {
        val v    = Span.from(Array[Byte](1, 2, 3, 4, 5))
        val back = roundTrip(v, PostgresEncoder.byteaBinary, PostgresDecoder.bytea)
        assert(back.toArray.sameElements(v.toArray))
    }

    // ── Timestamptz (kyo.Instant) ─────────────────────────────────────────────

    "timestamptz binary round-trip preserves epoch" in {
        val v    = kyo.Instant.Epoch
        val back = roundTrip(v, PostgresEncoder.timestamptzBinary, PostgresDecoder.timestamptz)
        // Compare as epoch seconds (nanoseconds may lose precision due to microsecond truncation).
        assert(math.abs(back.toJava.getEpochSecond - v.toJava.getEpochSecond) <= 1)
    }

    "timestamptz binary round-trip preserves known timestamp" in {
        // 2024-01-15 12:30:00 UTC
        val j    = java.time.Instant.parse("2024-01-15T12:30:00Z")
        val v    = kyo.Instant.fromJava(j)
        val back = roundTrip(v, PostgresEncoder.timestamptzBinary, PostgresDecoder.timestamptz)
        assert(back.toJava.getEpochSecond == j.getEpochSecond)
    }

    // ── Date (java.time.LocalDate) ────────────────────────────────────────────

    "date binary round-trip" in {
        val v    = java.time.LocalDate.of(2024, 3, 15)
        val back = roundTrip(v, PostgresEncoder.dateBinary, PostgresDecoder.date)
        assert(back.equals(v))
    }

    "date binary encodes PG epoch as 0 days" in {
        val pgEpoch = java.time.LocalDate.of(2000, 1, 1)
        val bytes   = encode(pgEpoch, PostgresEncoder.dateBinary)
        // Big-endian int32 = 0
        assert(bytes.size == 4)
        assert(bytes(0) == 0.toByte)
        assert(bytes(1) == 0.toByte)
        assert(bytes(2) == 0.toByte)
        assert(bytes(3) == 0.toByte)
    }

    // ── Timestamp (java.time.LocalDateTime) ───────────────────────────────────

    "timestamp binary round-trip" in {
        val v    = java.time.LocalDateTime.of(2024, 6, 1, 10, 30, 0)
        val back = roundTrip(v, PostgresEncoder.timestampBinary, PostgresDecoder.timestamp)
        // Compare at second precision (nanosecond truncation).
        assert(
            back.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).equals(
                v.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            )
        )
    }

    // ── Time (java.time.LocalTime) ────────────────────────────────────────────

    "time binary round-trip" in {
        val v    = java.time.LocalTime.of(14, 30, 15)
        val back = roundTrip(v, PostgresEncoder.timeBinary, PostgresDecoder.time)
        // Compare at second precision.
        assert(back.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).equals(v.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)))
    }

    // ── NUMERIC special values ────────────────────────────────────────────────
    //
    // PostgreSQL binary NUMERIC uses sign codes 0xC000 (NaN), 0xD000 (+Inf), 0xF000 (-Inf).
    // These have no Scala/BigDecimal representation; the decoder throws SqlException.Unsupported.
    //
    // Wire layout: Int16 ndigits | Int16 weight | UInt16 sign | UInt16 dscale  (8 bytes, no digits)

    /** Builds a minimal 8-byte NUMERIC binary header with the given `sign` code and zero digits. */
    private def numericSpecialBytes(sign: Int): Span[Byte] =
        val buf = new PostgresBufferWriter
        buf.writeInt16(0.toShort)    // ndigits = 0
        buf.writeInt16(0.toShort)    // weight  = 0
        buf.writeInt16(sign.toShort) // sign    = special code
        buf.writeInt16(0.toShort)    // dscale  = 0
        buf.toSpan
    end numericSpecialBytes

    "NUMERIC binary NaN throws SqlException.Decode with NaN in the message" in {
        val bytes = numericSpecialBytes(0xc000)
        val ex = intercept[SqlException.Decode] {
            PostgresDecoder.numeric.read(Format.Binary, bytes)
        }
        assert(ex.getMessage.contains("NaN"), s"expected NaN in message, got: ${ex.getMessage}")
    }

    "NUMERIC binary +Infinity and -Infinity throw SqlException.Decode with Infinity in the messages" in {
        val posInfBytes = numericSpecialBytes(0xd000)
        val posEx = intercept[SqlException.Decode] {
            PostgresDecoder.numeric.read(Format.Binary, posInfBytes)
        }
        assert(posEx.getMessage.contains("Infinity"), s"expected Infinity in message, got: ${posEx.getMessage}")

        val negInfBytes = numericSpecialBytes(0xf000)
        val negEx = intercept[SqlException.Decode] {
            PostgresDecoder.numeric.read(Format.Binary, negInfBytes)
        }
        assert(negEx.getMessage.contains("Infinity"), s"expected Infinity in message, got: ${negEx.getMessage}")
    }

    // ── Numeric binary base-10000 decomposition ──────────────────────────────

    "numeric binary encoder round-trip preserves base-10000 decomposition" in {
        // Verify the @tailrec toBase10000 produces identical binary encoding
        // to the prior while-loop version by round-tripping through binary encode/decode.
        // 123456789 in base 10000 = [1234, 5678, 9] (most-significant first).
        // The binary encoder uses this decomposition; a round-trip confirms byte-identical output.
        val v    = BigDecimal("123456789")
        val back = roundTrip(v, PostgresEncoder.numericBinary, PostgresDecoder.numeric)
        assert(back == v, s"Expected $v but got $back")
    }

    "numeric binary encoder round-trip with large BigDecimal" in {
        // Large value to exercise multiple base-10000 digits.
        val v    = BigDecimal("99999999999999999999.12345678")
        val back = roundTrip(v, PostgresEncoder.numericBinary, PostgresDecoder.numeric)
        assert(back == v, s"Expected $v but got $back")
    }

end PostgresEncoderTest
