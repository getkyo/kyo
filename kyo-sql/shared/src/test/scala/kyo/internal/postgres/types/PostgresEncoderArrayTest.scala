package kyo.internal.postgres.types

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.PostgresArrayReader
import kyo.internal.postgres.PostgresBufferWriter

/** Unit tests for PostgreSQL binary array encoder and decoder.
  *
  * Tests cover int4[] (OID 1007) and text[] (OID 1009) round-trips, wire byte assertions, empty arrays, and truncated-data decode errors.
  */
class PostgresEncoderArrayTest extends kyo.Test:

    // Helper: encode a Seq[A] to bytes using the given encoder.
    private def encode[A](value: Seq[A], enc: PostgresEncoder[A], elemOid: Int, arrayOid: Int): Span[Byte] =
        val buf    = new PostgresBufferWriter
        val arrEnc = PostgresEncoder.arrayEncoder(enc, elemOid, arrayOid)
        arrEnc.write(value, buf)
        buf.toSpan
    end encode

    // Helper: read a 4-byte big-endian Int from a Span at the given offset.
    private def readInt32BE(bytes: Span[Byte], offset: Int): Int =
        ((bytes(offset) & 0xff) << 24) |
            ((bytes(offset + 1) & 0xff) << 16) |
            ((bytes(offset + 2) & 0xff) << 8) |
            (bytes(offset + 3) & 0xff)

    // ── int4[] wire bytes ────────────────────────────────────────────────────

    "int4ArrayBinary encodes Chunk(1,2,3) as expected wire bytes" in {
        val buf = new PostgresBufferWriter
        PostgresEncoder.int4ArrayBinary.write(Seq(1, 2, 3), buf)
        val bytes = buf.toSpan
        // Header: ndim(4) + hasnulls(4) + elemOid(4) + dim_size(4) + lbound(4) = 20 bytes
        // Each element: elemLen(4) + 4 bytes = 8 bytes × 3 = 24 bytes
        // Total: 44 bytes
        assert(bytes.size == 44)
        assert(readInt32BE(bytes, 0) == 1)  // ndim
        assert(readInt32BE(bytes, 4) == 0)  // hasnulls
        assert(readInt32BE(bytes, 8) == 23) // elemOid = OID_INT4
        assert(readInt32BE(bytes, 12) == 3) // dim_size
        assert(readInt32BE(bytes, 16) == 1) // lbound
        // First element: length=4, value=1
        assert(readInt32BE(bytes, 20) == 4)
        assert(readInt32BE(bytes, 24) == 1)
        // Second element: length=4, value=2
        assert(readInt32BE(bytes, 28) == 4)
        assert(readInt32BE(bytes, 32) == 2)
        // Third element: length=4, value=3
        assert(readInt32BE(bytes, 36) == 4)
        assert(readInt32BE(bytes, 40) == 3)
    }

    "int4ArrayBinary decodes from wire bytes" in {
        val buf = new PostgresBufferWriter
        PostgresEncoder.int4ArrayBinary.write(Seq(10, 20, 30), buf)
        val bytes  = buf.toSpan
        val result = PostgresDecoder.int4Array.read(Format.Binary, bytes)
        assert(result == Chunk(10, 20, 30))
    }

    "int4ArrayBinary round-trips empty array" in {
        val buf = new PostgresBufferWriter
        PostgresEncoder.int4ArrayBinary.write(Seq.empty, buf)
        val bytes = buf.toSpan
        // Empty array: PG sends ndim=0 header (12 bytes) with no dim or element data.
        // Our encoder writes ndim=1 with dim_size=0, which is 20 bytes (header only, no elements).
        assert(bytes.size == 20)
        assert(readInt32BE(bytes, 0) == 1)  // ndim
        assert(readInt32BE(bytes, 12) == 0) // dim_size = 0
        val result = PostgresDecoder.int4Array.read(Format.Binary, bytes)
        assert(result == Chunk.empty)
    }

    // ── text[] round-trip ────────────────────────────────────────────────────

    "textArrayBinary round-trips Chunk(a, b, c)" in {
        val input = Seq("alpha", "beta", "gamma")
        val buf   = new PostgresBufferWriter
        PostgresEncoder.textArrayBinary.write(input, buf)
        val bytes = buf.toSpan
        // Verify header
        assert(readInt32BE(bytes, 0) == 1)  // ndim
        assert(readInt32BE(bytes, 8) == 25) // elemOid = OID_TEXT
        assert(readInt32BE(bytes, 12) == 3) // dim_size
        val decoded = PostgresDecoder.textArray.read(Format.Binary, bytes)
        assert(decoded == Chunk("alpha", "beta", "gamma"))
    }

    "textArrayBinary round-trips empty array" in {
        val buf = new PostgresBufferWriter
        PostgresEncoder.textArrayBinary.write(Seq.empty, buf)
        val bytes   = buf.toSpan
        val decoded = PostgresDecoder.textArray.read(Format.Binary, bytes)
        assert(decoded == Chunk.empty)
    }

    // ── decode raises Decode on truncated bytes ──────────────────────────────

    "array decode raises SqlDecodeException on truncated bytes" in {
        // Supply only 4 bytes, far too short for the 12-byte minimum header. The decoder must surface
        // the truncation as a typed `SqlDecodeException`, NOT as a NullPointerException / ArrayIndex
        // / similar reflection-layer panic. Per Phase 16 audit W-1, the previous assertion
        // (`result.isFailure || result.isPanic`) passed on any throwable and did not discriminate.
        val truncated = Span.from(Array[Byte](0, 0, 0, 1))
        val result    = Result.catching[Throwable](PostgresDecoder.int4Array.read(Format.Binary, truncated))
        result match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"expected Result.Failure(SqlDecodeException), got: $other")
        end match
    }

    // ── OID constants ────────────────────────────────────────────────────────

    "int4ArrayBinary has OID 1007" in {
        assert(PostgresEncoder.int4ArrayBinary.oid == 1007)
    }

    "textArrayBinary has OID 1009" in {
        assert(PostgresEncoder.textArrayBinary.oid == 1009)
    }

    "int4ArrayBinary uses Binary format" in {
        assert(PostgresEncoder.int4ArrayBinary.format == Format.Binary)
    }

    "textArrayBinary uses Binary format" in {
        assert(PostgresEncoder.textArrayBinary.format == Format.Binary)
    }

    "OID_INT4_ARRAY constant is 1007" in {
        assert(PostgresEncoder.OID_INT4_ARRAY == 1007)
    }

    "OID_TEXT_ARRAY constant is 1009" in {
        assert(PostgresEncoder.OID_TEXT_ARRAY == 1009)
    }

    "OID_JSONB_ARRAY constant is 3807" in {
        assert(PostgresEncoder.OID_JSONB_ARRAY == 3807)
    }

    // ── jsonb[] wire bytes ───────────────────────────────────────────────────

    "jsonbArrayBinary has OID 3807" in {
        assert(PostgresEncoder.jsonbArrayBinary.oid == 3807)
    }

    "jsonbArrayBinary uses Binary format" in {
        assert(PostgresEncoder.jsonbArrayBinary.format == Format.Binary)
    }

    "jsonbArrayBinary encodes Chunk of JSON elements with version-prefixed payloads" in {
        val input = Seq("""{"a":1}""", """{"b":2}""")
        val buf   = new PostgresBufferWriter
        PostgresEncoder.jsonbArrayBinary.write(input, buf)
        val bytes = buf.toSpan
        // Header (20 bytes): ndim=1, hasnulls=0, elemOid=3802 (jsonb), dim_size=2, lbound=1
        assert(readInt32BE(bytes, 0) == 1)
        assert(readInt32BE(bytes, 4) == 0)
        assert(readInt32BE(bytes, 8) == 3802)
        assert(readInt32BE(bytes, 12) == 2)
        assert(readInt32BE(bytes, 16) == 1)
        // First element: length=8 (1 version byte + 7 text bytes), then 0x01 + UTF-8
        assert(readInt32BE(bytes, 20) == 8)
        assert(bytes(24) == 0x01.toByte)
        val firstText = new String(bytes.toArray.slice(25, 32), java.nio.charset.StandardCharsets.UTF_8)
        assert(firstText == """{"a":1}""")
        // Second element: length=8, 0x01 + UTF-8
        assert(readInt32BE(bytes, 32) == 8)
        assert(bytes(36) == 0x01.toByte)
        val secondText = new String(bytes.toArray.slice(37, 44), java.nio.charset.StandardCharsets.UTF_8)
        assert(secondText == """{"b":2}""")
    }

    "jsonbArrayBinary round-trips" in {
        val input = Seq("""[1,2,3]""", """{"k":"v"}""", "null")
        val buf   = new PostgresBufferWriter
        PostgresEncoder.jsonbArrayBinary.write(input, buf)
        val decoded = PostgresDecoder.jsonbArray.read(Format.Binary, buf.toSpan)
        assert(decoded == Chunk("""[1,2,3]""", """{"k":"v"}""", "null"))
    }

    "jsonbArrayBinary round-trips empty array" in {
        val buf = new PostgresBufferWriter
        PostgresEncoder.jsonbArrayBinary.write(Seq.empty, buf)
        val decoded = PostgresDecoder.jsonbArray.read(Format.Binary, buf.toSpan)
        assert(decoded == Chunk.empty)
    }

    "jsonbArray decode raises SqlDecodeException on truncated bytes" in {
        val truncated = Span.from(Array[Byte](0, 0, 0, 1))
        val result    = Result.catching[Throwable](PostgresDecoder.jsonbArray.read(Format.Binary, truncated))
        result match
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"expected Result.Failure(SqlDecodeException), got: $other")
        end match
    }

end PostgresEncoderArrayTest
