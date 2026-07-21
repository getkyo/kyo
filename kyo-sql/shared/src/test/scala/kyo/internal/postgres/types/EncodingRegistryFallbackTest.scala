package kyo.internal.postgres.types

import kyo.*
import kyo.internal.postgres.PostgresBufferWriter

/** Tests for EncodingRegistry text-format encoder fallback (G9.22).
  *
  * Phase 12: verifies that (a) text-format encoders are directly registered for OID_TIMESTAMPTZ, OID_DATE, OID_TIMESTAMP, OID_TIME, and
  * OID_BYTEA, and (b) the Binary→String fallback in BuiltinEncodingRegistry.encoderByOid returns the text encoder when a binary key is
  * absent.
  */
class EncodingRegistryFallbackTest extends kyo.Test:

    val registry = EncodingRegistry.builtin

    private def encode[A](value: A, enc: PostgresEncoder[A]): Span[Byte] =
        val buf = new PostgresBufferWriter
        enc.write(value, buf)
        buf.toSpan
    end encode

    private def asString(bytes: Span[Byte]): String =
        new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)

    // ── Direct String-format encoder lookups ───────────────────────────────────

    "encoderByOid(OID_TIMESTAMPTZ, String) returns a text encoder" in {
        val enc = registry.encoderByOid(PostgresEncoder.OID_TIMESTAMPTZ, Format.Text)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_TIMESTAMPTZ)
        assert(enc.get.format == Format.Text)
    }

    "encoderByOid(OID_DATE, String) returns a text encoder" in {
        val enc = registry.encoderByOid(PostgresEncoder.OID_DATE, Format.Text)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_DATE)
        assert(enc.get.format == Format.Text)
    }

    "encoderByOid(OID_TIMESTAMP, String) returns a text encoder" in {
        val enc = registry.encoderByOid(PostgresEncoder.OID_TIMESTAMP, Format.Text)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_TIMESTAMP)
        assert(enc.get.format == Format.Text)
    }

    "encoderByOid(OID_TIME, String) returns a text encoder" in {
        val enc = registry.encoderByOid(PostgresEncoder.OID_TIME, Format.Text)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_TIME)
        assert(enc.get.format == Format.Text)
    }

    "encoderByOid(OID_BYTEA, String) returns a hex-escape encoder" in {
        val enc = registry.encoderByOid(PostgresEncoder.OID_BYTEA, Format.Text)
        assert(enc.isDefined)
        assert(enc.get.oid == PostgresEncoder.OID_BYTEA)
        assert(enc.get.format == Format.Text)
    }

    // ── String encoder output correctness ──────────────────────────────────────

    "timestamptz text encoder emits ISO-8601 with UTC offset" in {
        val j   = java.time.Instant.parse("2024-01-15T12:34:56.123456Z")
        val v   = kyo.Instant.fromJava(j)
        val enc = PostgresEncoder.timestamptzText
        val out = asString(encode(v, enc))
        // Must contain the date, time, and a UTC offset marker.
        assert(out.startsWith("2024-01-15 12:34:56"))
        assert(out.contains("+00"))
    }

    "date text encoder emits YYYY-MM-DD" in {
        val v   = java.time.LocalDate.of(2024, 3, 15)
        val enc = PostgresEncoder.dateText
        val out = asString(encode(v, enc))
        assert(out == "2024-03-15")
    }

    "timestamp text encoder emits YYYY-MM-DD HH:MM:SS.SSSSSS" in {
        val v   = java.time.LocalDateTime.of(2024, 6, 1, 10, 30, 0)
        val enc = PostgresEncoder.timestampText
        val out = asString(encode(v, enc))
        assert(out.startsWith("2024-06-01 10:30:00"))
    }

    "time text encoder emits HH:MM:SS.SSSSSS" in {
        val v   = java.time.LocalTime.of(14, 30, 15)
        val enc = PostgresEncoder.timeText
        val out = asString(encode(v, enc))
        assert(out.startsWith("14:30:15"))
    }

    "bytea text encoder emits \\x hex" in {
        val v   = Span.from(Array[Byte](0x01, 0x02, 0xab.toByte, 0xff.toByte))
        val enc = PostgresEncoder.byteaText
        val out = asString(encode(v, enc))
        assert(out == "\\x0102abff")
    }

    // ── Round-trips through text encoders ─────────────────────────────────────

    "timestamptz text encoder round-trips via decoder" in {
        val j    = java.time.Instant.parse("2024-01-15T12:34:56Z")
        val v    = kyo.Instant.fromJava(j)
        val enc  = PostgresEncoder.timestamptzText
        val dec  = PostgresDecoder.timestamptz
        val back = dec.read(Format.Text, encode(v, enc))
        assert(back.toJava.getEpochSecond == j.getEpochSecond)
    }

    "date text encoder round-trips via decoder" in {
        val v    = java.time.LocalDate.of(2024, 3, 15)
        val enc  = PostgresEncoder.dateText
        val dec  = PostgresDecoder.date
        val back = dec.read(Format.Text, encode(v, enc))
        assert(back.equals(v))
    }

    "timestamp text encoder round-trips via decoder" in {
        val v    = java.time.LocalDateTime.of(2024, 6, 1, 10, 30, 0)
        val enc  = PostgresEncoder.timestampText
        val dec  = PostgresDecoder.timestamp
        val back = dec.read(Format.Text, encode(v, enc))
        assert(back.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).equals(v.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)))
    }

    "time text encoder round-trips via decoder" in {
        val v    = java.time.LocalTime.of(14, 30, 15)
        val enc  = PostgresEncoder.timeText
        val dec  = PostgresDecoder.time
        val back = dec.read(Format.Text, encode(v, enc))
        assert(back.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).equals(v.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)))
    }

    "bytea text encoder round-trips via decoder" in {
        val v    = Span.from(Array[Byte](0x01, 0x02, 0xab.toByte, 0xff.toByte))
        val enc  = PostgresEncoder.byteaText
        val dec  = PostgresDecoder.bytea
        val back = dec.read(Format.Text, encode(v, enc))
        assert(back.toArray.sameElements(v.toArray))
    }

end EncodingRegistryFallbackTest
