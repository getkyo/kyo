package kyo.internal.postgres.types

import java.time.LocalTime
import java.time.OffsetTime
import java.time.ZoneOffset
import kyo.*
import kyo.internal.postgres.PostgresBufferWriter

/** Unit tests for the PG timetz binary codec (OID 1266).
  *
  * Wire format: Int64 microseconds-of-day (big-endian) followed by Int32 offset_seconds (negated, big-endian). PG convention: west-of-UTC
  * offsets are stored positive (e.g. UTC-05:00 → +18000).
  */
class PostgresEncoderTimetzTest extends kyo.Test:

    given CanEqual[OffsetTime, OffsetTime] = CanEqual.canEqualAny

    // Helper: encode an OffsetTime to bytes via timetzBinary.
    private def encode(value: OffsetTime): Span[Byte] =
        val buf = new PostgresBufferWriter
        PostgresEncoder.timetzBinary.write(value, buf)
        buf.toSpan
    end encode

    // Helper: decode bytes as OffsetTime using timetz decoder.
    private def decode(format: Format, bytes: Span[Byte]): OffsetTime =
        PostgresDecoder.timetz.read(format, bytes)

    // ── Encoder ──────────────────────────────────────────────────────────────

    "timetz encodes OffsetTime as (us_of_day, offset_seconds)" in {
        // 13:30:00.000000 at UTC+02:00
        val t     = OffsetTime.of(LocalTime.of(13, 30, 0, 0), ZoneOffset.ofHours(2))
        val bytes = encode(t)
        // Total: 8 bytes us_of_day + 4 bytes offset = 12 bytes
        assert(bytes.size == 12)
        // microseconds since midnight: 13*3600 + 30*60 = 48600 seconds = 48600_000_000 µs
        val expectedMicros = 48_600_000_000L
        val hi             = ((expectedMicros >> 32) & 0xffffffffL).toInt
        val lo             = (expectedMicros & 0xffffffffL).toInt
        val actualHi =
            ((bytes(0) & 0xff) << 24) | ((bytes(1) & 0xff) << 16) | ((bytes(2) & 0xff) << 8) | (bytes(3) & 0xff)
        val actualLo =
            ((bytes(4) & 0xff) << 24) | ((bytes(5) & 0xff) << 16) | ((bytes(6) & 0xff) << 8) | (bytes(7) & 0xff)
        assert(actualHi == hi)
        assert(actualLo == lo)
        // offset field: negated getTotalSeconds → UTC+02:00 has +7200 s → stored as -7200
        val actualOffset =
            ((bytes(8) & 0xff) << 24) | ((bytes(9) & 0xff) << 16) | ((bytes(10) & 0xff) << 8) | (bytes(11) & 0xff)
        assert(actualOffset == -7200)
    }

    "timetz encodes negative offset (west of UTC)" in {
        // 08:00:00.000000 at UTC-05:00
        val t     = OffsetTime.of(LocalTime.of(8, 0, 0, 0), ZoneOffset.ofHours(-5))
        val bytes = encode(t)
        assert(bytes.size == 12)
        // microseconds since midnight: 8*3600 = 28800 seconds = 28800_000_000 µs
        val expectedMicros = 28_800_000_000L
        val hi             = ((expectedMicros >> 32) & 0xffffffffL).toInt
        val lo             = (expectedMicros & 0xffffffffL).toInt
        val actualHi =
            ((bytes(0) & 0xff) << 24) | ((bytes(1) & 0xff) << 16) | ((bytes(2) & 0xff) << 8) | (bytes(3) & 0xff)
        val actualLo =
            ((bytes(4) & 0xff) << 24) | ((bytes(5) & 0xff) << 16) | ((bytes(6) & 0xff) << 8) | (bytes(7) & 0xff)
        assert(actualHi == hi)
        assert(actualLo == lo)
        // UTC-05:00 has getTotalSeconds = -18000 → stored as +18000
        val actualOffset =
            ((bytes(8) & 0xff) << 24) | ((bytes(9) & 0xff) << 16) | ((bytes(10) & 0xff) << 8) | (bytes(11) & 0xff)
        assert(actualOffset == 18000)
    }

    // ── Decoder ──────────────────────────────────────────────────────────────

    "timetz decodes from binary" in {
        // Build 12-byte payload for 10:15:30.000000 UTC+00:00 manually.
        // microseconds: 10*3600 + 15*60 + 30 = 36930 s = 36930_000_000 µs
        val micros       = 36_930_000_000L
        val offsetStored = 0 // UTC+00:00 → negate(0) = 0
        val arr          = new Array[Byte](12)
        arr(0) = ((micros >> 56) & 0xff).toByte
        arr(1) = ((micros >> 48) & 0xff).toByte
        arr(2) = ((micros >> 40) & 0xff).toByte
        arr(3) = ((micros >> 32) & 0xff).toByte
        arr(4) = ((micros >> 24) & 0xff).toByte
        arr(5) = ((micros >> 16) & 0xff).toByte
        arr(6) = ((micros >> 8) & 0xff).toByte
        arr(7) = (micros & 0xff).toByte
        arr(8) = ((offsetStored >> 24) & 0xff).toByte
        arr(9) = ((offsetStored >> 16) & 0xff).toByte
        arr(10) = ((offsetStored >> 8) & 0xff).toByte
        arr(11) = (offsetStored & 0xff).toByte
        val bytes   = Span.from(arr)
        val decoded = decode(Format.Binary, bytes)
        assert(decoded == OffsetTime.of(LocalTime.of(10, 15, 30, 0), ZoneOffset.UTC))
    }

    // ── Round-trip ───────────────────────────────────────────────────────────

    "timetz round-trips" in {
        val original = OffsetTime.of(LocalTime.of(23, 59, 59, 123_456_000), ZoneOffset.ofHoursMinutes(5, 30))
        val bytes    = encode(original)
        val decoded  = decode(Format.Binary, bytes)
        // Nanoseconds beyond microsecond precision are truncated on encode.
        val expected = OffsetTime.of(
            LocalTime.of(23, 59, 59, 123_456_000),
            ZoneOffset.ofHoursMinutes(5, 30)
        )
        assert(decoded == expected)
    }

end PostgresEncoderTimetzTest
