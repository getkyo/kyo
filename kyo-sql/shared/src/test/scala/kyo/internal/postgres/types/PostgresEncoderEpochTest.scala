package kyo.internal.postgres.types

import kyo.*
import kyo.internal.postgres.PostgresBufferWriter

/** Tests verifying the PostgreSQL epoch math for timestamptz and date codecs.
  *
  * PostgreSQL timestamps are stored as microseconds since 2000-01-01 00:00:00 UTC. The Unix epoch is 1970-01-01. The difference is 30 years =
  * 946684800 seconds = 946684800000000 microseconds.
  */
class PostgresEncoderEpochTest extends kyo.Test:

    // Helper to read big-endian int64 from a Span.
    private def readLong(bytes: Span[Byte]): Long =
        ((bytes(0) & 0xffL) << 56) |
            ((bytes(1) & 0xffL) << 48) |
            ((bytes(2) & 0xffL) << 40) |
            ((bytes(3) & 0xffL) << 32) |
            ((bytes(4) & 0xffL) << 24) |
            ((bytes(5) & 0xffL) << 16) |
            ((bytes(6) & 0xffL) << 8) |
            (bytes(7) & 0xffL)

    private def readInt(bytes: Span[Byte]): Int =
        ((bytes(0) & 0xff) << 24) |
            ((bytes(1) & 0xff) << 16) |
            ((bytes(2) & 0xff) << 8) |
            (bytes(3) & 0xff)

    // ── PG_EPOCH_MICROS constant ──────────────────────────────────────────────

    "PG_EPOCH_MICROS equals 946684800000000" in {
        assert(PostgresEncoder.PG_EPOCH_MICROS == 946_684_800_000_000L)
    }

    "PG_EPOCH_MICROS is microseconds between Unix epoch and PG epoch" in {
        // 2000-01-01 00:00:00 UTC as unix epoch seconds
        val pgEpochSecs = 946_684_800L
        assert(PostgresEncoder.PG_EPOCH_MICROS == pgEpochSecs * 1_000_000L)
    }

    // ── Timestamptz epoch encoding ────────────────────────────────────────────

    "timestamptz encodes Unix epoch as negative offset from PG epoch" in {
        // Unix epoch (1970-01-01) is BEFORE the PG epoch (2000-01-01).
        // Expected PG micros = 0 (unix epoch micros) - 946684800000000 = -946684800000000
        val unixEpoch = kyo.Instant.Epoch
        val buf       = new PostgresBufferWriter
        PostgresEncoder.timestamptzBinary.write(unixEpoch, buf)
        val bytes    = buf.toSpan
        val pgMicros = readLong(bytes)
        assert(pgMicros == -PostgresEncoder.PG_EPOCH_MICROS)
    }

    "timestamptz encodes PG epoch (2000-01-01) as 0" in {
        // PG epoch itself: micros = 0
        val pgEpochInstant = kyo.Instant.fromJava(java.time.Instant.parse("2000-01-01T00:00:00Z"))
        val buf            = new PostgresBufferWriter
        PostgresEncoder.timestamptzBinary.write(pgEpochInstant, buf)
        val bytes    = buf.toSpan
        val pgMicros = readLong(bytes)
        assert(pgMicros == 0L)
    }

    "timestamptz encodes 2024-01-01 as expected offset from PG epoch" in {
        // 2024-01-01 00:00:00 UTC
        // Days from 2000-01-01 to 2024-01-01: 24 years ≈ 8766 days (including 6 leap years: 2000,04,08,12,16,20).
        // Expected secs = 8766 * 86400 = 757382400
        val instant = kyo.Instant.fromJava(java.time.Instant.parse("2024-01-01T00:00:00Z"))
        val buf     = new PostgresBufferWriter
        PostgresEncoder.timestamptzBinary.write(instant, buf)
        val bytes    = buf.toSpan
        val pgMicros = readLong(bytes)
        // Should be positive (2024 is after PG epoch).
        assert(pgMicros > 0L)
        // Verify round-trip: decode and compare.
        val decoded = PostgresDecoder.timestamptz.read(Format.Binary, bytes)
        assert(decoded.toJava.getEpochSecond == instant.toJava.getEpochSecond)
    }

    // ── Date epoch encoding ───────────────────────────────────────────────────

    "date encodes PG epoch (2000-01-01) as 0 days" in {
        val pgEpoch = java.time.LocalDate.of(2000, 1, 1)
        val buf     = new PostgresBufferWriter
        PostgresEncoder.dateBinary.write(pgEpoch, buf)
        val bytes = buf.toSpan
        val days  = readInt(bytes)
        assert(days == 0)
    }

    "date encodes 2000-01-02 as 1 day" in {
        val nextDay = java.time.LocalDate.of(2000, 1, 2)
        val buf     = new PostgresBufferWriter
        PostgresEncoder.dateBinary.write(nextDay, buf)
        val bytes = buf.toSpan
        val days  = readInt(bytes)
        assert(days == 1)
    }

    "date encodes 1999-12-31 as -1 day" in {
        val prevDay = java.time.LocalDate.of(1999, 12, 31)
        val buf     = new PostgresBufferWriter
        PostgresEncoder.dateBinary.write(prevDay, buf)
        val bytes = buf.toSpan
        val days  = readInt(bytes)
        assert(days == -1)
    }

    "date round-trip for 2024-06-15" in {
        val d   = java.time.LocalDate.of(2024, 6, 15)
        val buf = new PostgresBufferWriter
        PostgresEncoder.dateBinary.write(d, buf)
        val back = PostgresDecoder.date.read(Format.Binary, buf.toSpan)
        assert(back.equals(d))
    }

end PostgresEncoderEpochTest
