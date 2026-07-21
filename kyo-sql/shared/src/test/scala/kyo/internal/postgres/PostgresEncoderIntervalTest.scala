package kyo.internal.postgres

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Span
import kyo.SqlException
import kyo.Test
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresDecoder
import kyo.internal.postgres.types.PostgresEncoder

/** Tests for the PostgreSQL INTERVAL binary wire codec for java.time.Duration.
  *
  * Tests use explicit byte arrays (constructed from the known wire format) to verify both the encoder and decoder in isolation, no live
  * database required.
  */
class PostgresEncoderIntervalTest extends Test:

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

    "INTERVAL decode raises SqlException.Decode when months != 0" in {
        // months = -1 (e.g. PG INTERVAL '1 month ago')
        val bytes = intervalBytes(0L, 0, -1)
        val ex = intercept[SqlException.Decode] {
            PostgresDecoder.interval.read(Format.Binary, bytes)
        }
        assert(ex.message.contains("months"), s"expected months error, got: ${ex.message}")
        assert(ex.message.contains("-1"), s"expected field value in message, got: ${ex.message}")
        assert(ex.message.contains("java.time.Period"), s"expected Period reference, got: ${ex.message}")
    }

    "INTERVAL decode raises SqlException.Decode when days != 0 and months == 0" in {
        // days = 3, months = 0
        val bytes = intervalBytes(0L, 3, 0)
        val ex = intercept[SqlException.Decode] {
            PostgresDecoder.interval.read(Format.Binary, bytes)
        }
        assert(ex.message.contains("days"), s"expected days error, got: ${ex.message}")
        assert(ex.message.contains("3"), s"expected field value in message, got: ${ex.message}")
        assert(ex.message.contains("java.time.Period"), s"expected Period reference, got: ${ex.message}")
    }

    "INTERVAL decode raises months error before days error when both are non-zero" in {
        // months=1, days=5: months check fires first
        val bytes = intervalBytes(0L, 5, 1)
        val ex = intercept[SqlException.Decode] {
            PostgresDecoder.interval.read(Format.Binary, bytes)
        }
        assert(ex.message.contains("months"), s"expected months error first, got: ${ex.message}")
    }

    // ── Encode/decode round-trip ──────────────────────────────────────────────

    "INTERVAL round-trip: encode then decode restores original Duration" in {
        val values = Seq(
            java.time.Duration.ZERO,
            java.time.Duration.ofHours(1),
            java.time.Duration.ofSeconds(-30),
            java.time.Duration.ofMinutes(90),
            java.time.Duration.ofSeconds(3661, 500_000_000L), // 1h 1m 1s + 500ms
            // PLAN leaf 1 representative: multi-day Duration. Round-trips cleanly via µs
            // because `java.time.Duration` encodes everything as microseconds, the wire-format
            // `days != 0` raise only fires when the SERVER emits a days component, not when the
            // Duration spans multiple calendar days client-side.
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
        // Plan leaf 1 (PHASE-2-AUDIT W-1), explicit `1.day + 2.hours → toHours == 26`.
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

    "INTERVAL text decode raises SqlException.Decode for PG verbose format with months" in {
        val s     = "1 year 2 mons 00:01:02"
        val bytes = Span.from(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val ex = intercept[SqlException.Decode] {
            PostgresDecoder.interval.read(Format.Text, bytes)
        }
        assert(
            ex.message.contains("ISO") || ex.message.contains("cast") || ex.message.contains("intervalstyle"),
            s"expected cast/ISO suggestion, got: ${ex.message}"
        )
    }

end PostgresEncoderIntervalTest
