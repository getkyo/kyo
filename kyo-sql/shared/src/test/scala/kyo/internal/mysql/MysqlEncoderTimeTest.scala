package kyo.internal.mysql

import kyo.Maybe
import kyo.Span
import kyo.SqlDecodeException
import kyo.SqlDecodeTemporalException
import kyo.SqlException
import kyo.SqlRequestDurationOverflowException
import kyo.Test
import kyo.internal.mysql.types.MysqlDecoder
import kyo.internal.mysql.types.MysqlEncoder

/** Tests for the MySQL TIME wire codec for [[java.time.Duration]].
  *
  * Verifies that [[MysqlEncoder.durationEncoder]] and [[MysqlDecoder.durationDecoder]] produce and consume byte-exact wire representations
  * of the MySQL TIME binary protocol format (§14.7.4).
  *
  * All tests are pure unit tests on wire bytes; no MySQL container or network I/O is required.
  */
class MysqlEncoderTimeTest extends Test:

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Encodes `value` using the duration encoder and returns the raw wire bytes. */
    private def encode(value: java.time.Duration): Array[Byte] =
        val buf = new MysqlBufferWriter
        MysqlEncoder.durationEncoder.write(value, buf)
        buf.toSpan.toArray
    end encode

    /** Decodes raw TIME struct body bytes using the duration decoder (no Abort context, throws on failure).
      *
      * The body is the TIME struct content delivered by BinaryResultsetRowUnmarshaller after stripping the MySQL length prefix byte.
      */
    private def decode(body: Array[Byte]): java.time.Duration =
        kyo.Abort.run(MysqlDecoder.durationDecoder.decode(Span.from(body))).eval match
            case kyo.Result.Success(d) => d
            case kyo.Result.Failure(e) => throw e
            case kyo.Result.Panic(t)   => throw t
        end match
    end decode

    /** Encodes `value` then decodes by stripping the length prefix byte that encode() writes.
      *
      * The MySQL wire encoder writes a length-prefix byte followed by the struct body. On read, BinaryResultsetRowUnmarshaller strips the
      * length prefix, so the decoder receives only the body. This helper mirrors that stripping for round-trip tests.
      */
    private def roundTrip(value: java.time.Duration): java.time.Duration =
        val encoded = encode(value)
        // encoded[0] is the MySQL length prefix; the body is everything after it.
        val body = if encoded.length <= 1 then Array.empty[Byte] else encoded.slice(1, encoded.length)
        decode(body)
    end roundTrip

    // ── Encode tests ─────────────────────────────────────────────────────────

    "TIME encodes Duration.ZERO as single 0x00 byte (length=0)" in {
        val bytes = encode(java.time.Duration.ZERO)
        assert(bytes.length == 1, s"expected 1 byte, got ${bytes.length}")
        assert(bytes(0) == 0x00.toByte, s"expected 0x00 length byte, got 0x${(bytes(0) & 0xff).toHexString}")
    }

    "TIME encodes Duration.ofHours(1) as 9 bytes with length=8, isNeg=0" in {
        val bytes = encode(java.time.Duration.ofHours(1))
        assert(bytes.length == 9, s"expected 9 bytes (1 length + 8 body), got ${bytes.length}")
        assert(bytes(0) == 0x08.toByte, s"expected length=8, got 0x${(bytes(0) & 0xff).toHexString}")
        assert(bytes(1) == 0x00.toByte, s"expected isNegative=0, got ${bytes(1)}")
        // days = 0 (bytes 2-5 LE)
        assert(bytes(2) == 0x00.toByte && bytes(3) == 0x00.toByte && bytes(4) == 0x00.toByte && bytes(5) == 0x00.toByte, "days should be 0")
        assert(bytes(6) == 0x01.toByte, s"expected hours=1, got ${bytes(6)}")
        assert(bytes(7) == 0x00.toByte, s"expected minutes=0, got ${bytes(7)}")
        assert(bytes(8) == 0x00.toByte, s"expected seconds=0, got ${bytes(8)}")
    }

    "TIME encodes negative Duration with isNegative byte = 1" in {
        val bytes = encode(java.time.Duration.ofHours(-8))
        assert(bytes.length == 9, s"expected 9 bytes, got ${bytes.length}")
        assert(bytes(0) == 0x08.toByte, "length should be 8")
        assert(bytes(1) == 0x01.toByte, s"expected isNegative=1, got ${bytes(1)}")
        assert(bytes(6) == 0x08.toByte, s"expected hours=8, got ${bytes(6)}")
    }

    "TIME encodes fractional Duration with length=12" in {
        // Duration.ofSeconds(3, 500_000_000L) → 500ms = 500_000 micros
        val value = java.time.Duration.ofSeconds(3, 500_000_000L)
        val bytes = encode(value)
        assert(bytes.length == 13, s"expected 13 bytes (1 length + 12 body), got ${bytes.length}")
        assert(bytes(0) == 0x0c.toByte, s"expected length=12, got 0x${(bytes(0) & 0xff).toHexString}")
        assert(bytes(1) == 0x00.toByte, "isNegative should be 0")
        // micros = 500_000 = 0x0007A120 LE → bytes 9..12 = 0x20, 0xA1, 0x07, 0x00
        assert(bytes(9) == 0x20.toByte, s"micros[0] mismatch: ${(bytes(9) & 0xff).toHexString}")
        assert(bytes(10) == 0xa1.toByte, s"micros[1] mismatch: ${(bytes(10) & 0xff).toHexString}")
        assert(bytes(11) == 0x07.toByte, s"micros[2] mismatch: ${(bytes(11) & 0xff).toHexString}")
        assert(bytes(12) == 0x00.toByte, s"micros[3] mismatch: ${(bytes(12) & 0xff).toHexString}")
    }

    "TIME encode raises ArithmeticException on day-count overflow" in {
        // The encoder throws SqlRequestDurationOverflowException when the duration's
        // total-day count exceeds Int.MaxValue, carrying the overflowing day count.
        val hugeSeconds = (Int.MaxValue.toLong + 1L) * 86400L
        val value       = java.time.Duration.ofSeconds(hugeSeconds)
        val ex = intercept[SqlRequestDurationOverflowException] {
            encode(value)
        }
        assert(ex.totalDays > Int.MaxValue.toLong, s"expected totalDays > Int.MaxValue, got: ${ex.totalDays}")
    }

    // ── Decode tests ─────────────────────────────────────────────────────────

    "TIME decodes Duration.ZERO from empty body (length=0 TIME struct)" in {
        // MySQL strips the 0x00 length prefix; the body for a zero duration is 0 bytes.
        val result = decode(Array.empty[Byte])
        assert(result.equals(java.time.Duration.ZERO), s"expected ZERO, got $result")
    }

    "TIME decodes Duration.ofHours(1) from 8-byte struct" in {
        val body = Array[Byte](
            0x00,                   // is_negative = 0
            0x00, 0x00, 0x00, 0x00, // days = 0 LE
            0x01,                   // hours = 1
            0x00,                   // minutes = 0
            0x00                    // seconds = 0
        )
        val result = decode(body)
        assert(result.equals(java.time.Duration.ofHours(1)), s"expected 1h, got $result")
    }

    "TIME decodes negative Duration from 8-byte struct with isNeg=1" in {
        val body = Array[Byte](
            0x01,                   // is_negative = 1
            0x00, 0x00, 0x00, 0x00, // days = 0 LE
            0x02,                   // hours = 2
            0x00,                   // minutes = 0
            0x00                    // seconds = 0
        )
        val result = decode(body)
        assert(result.equals(java.time.Duration.ofHours(-2)), s"expected -2h, got $result")
    }

    "TIME decodes fractional Duration from 12-byte struct" in {
        // 0 days, 0 hours, 0 minutes, 3 seconds, 500_000 micros = 500ms
        val micros = 500_000
        val body = Array[Byte](
            0x00, // is_negative = 0
            0x00,
            0x00,
            0x00,
            0x00, // days = 0 LE
            0x00, // hours = 0
            0x00, // minutes = 0
            0x03, // seconds = 3
            (micros & 0xff).toByte,
            ((micros >> 8) & 0xff).toByte,
            ((micros >> 16) & 0xff).toByte,
            ((micros >> 24) & 0xff).toByte
        )
        val result = decode(body)
        assert(result.equals(java.time.Duration.ofSeconds(3, 500_000_000L)), s"expected 3.5s, got $result")
    }

    "TIME decode raises Decode on unexpected length" in {
        // 5 bytes is not a valid TIME struct length (must be 0, 8, or 12).
        val badBody = Array[Byte](0x00.toByte, 0x01.toByte, 0x02.toByte, 0x03.toByte, 0x04.toByte)
        val ex = intercept[SqlDecodeException] {
            decode(badBody)
        }
        assert(
            ex.getMessage.contains("unexpected struct length 5"),
            s"expected length-error message naming 5, got: ${ex.getMessage}"
        )
    }

    // ── Round-trip tests ─────────────────────────────────────────────────────

    "Duration.ofHours(1) round-trips through encode + decode" in {
        val original = java.time.Duration.ofHours(1)
        val result   = roundTrip(original)
        assert(result.equals(original), s"round-trip failed: got $result")
    }

    "Duration.ofSeconds(-30, 500_000_000) round-trips (negative + fractional)" in {
        val original = java.time.Duration.ofSeconds(-30, 500_000_000L)
        val result   = roundTrip(original)
        assert(result.equals(original), s"round-trip failed for $original: got $result")
    }

    "Duration.ZERO round-trips" in {
        val result = roundTrip(java.time.Duration.ZERO)
        assert(result.equals(java.time.Duration.ZERO), s"round-trip failed for ZERO: got $result")
    }

    "Duration.ofDays(34).plusHours(1) round-trips" in {
        val original = java.time.Duration.ofDays(34).plusHours(1)
        val result   = roundTrip(original)
        assert(result.equals(original), s"round-trip failed: got $result")
    }

end MysqlEncoderTimeTest
