package kyo.internal.postgres.types

import java.util.UUID
import kyo.*
import kyo.SqlCodec.Format
import kyo.SqlException
import kyo.SqlRow
import kyo.SqlSchema
import kyo.internal.postgres.BoundParam
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.PostgresParamWriter
import kyo.internal.postgres.PostgresRowCodec
import kyo.internal.postgres.TypeRegistry

/** Unit tests for the PG UUID binary codec (OID 2950).
  *
  * Wire format: 16 bytes big-endian, mostSignificantBits (Int64) followed by leastSignificantBits (Int64).
  */
class PostgresEncoderUuidTest extends kyo.Test:

    // UUID equality via CanEqual instance (CanEqual.canEqualAny widens comparison).
    given CanEqual[UUID, UUID] = CanEqual.canEqualAny

    // Helper: encode a UUID to bytes using uuidBinary.
    private def encode(value: UUID): Span[Byte] =
        val buf = new PostgresBufferWriter
        PostgresEncoder.uuidBinary.write(value, buf)
        buf.toSpan
    end encode

    // Helper: decode bytes as UUID in the given format.
    private def decode(format: Format, bytes: Span[Byte]): UUID =
        PostgresDecoder.uuid.read(format, bytes)

    // ── Encoder ──────────────────────────────────────────────────────────────

    "uuid encodes as 16 bytes big-endian (mostSig, leastSig)" in {
        val uuid  = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val bytes = encode(uuid)
        assert(bytes.size == 16)
        val msb = uuid.getMostSignificantBits
        assert(bytes(0) == ((msb >> 56) & 0xff).toByte)
        assert(bytes(1) == ((msb >> 48) & 0xff).toByte)
        assert(bytes(2) == ((msb >> 40) & 0xff).toByte)
        assert(bytes(3) == ((msb >> 32) & 0xff).toByte)
        assert(bytes(4) == ((msb >> 24) & 0xff).toByte)
        assert(bytes(5) == ((msb >> 16) & 0xff).toByte)
        assert(bytes(6) == ((msb >> 8) & 0xff).toByte)
        assert(bytes(7) == (msb & 0xff).toByte)
        val lsb = uuid.getLeastSignificantBits
        assert(bytes(8) == ((lsb >> 56) & 0xff).toByte)
        assert(bytes(9) == ((lsb >> 48) & 0xff).toByte)
        assert(bytes(10) == ((lsb >> 40) & 0xff).toByte)
        assert(bytes(11) == ((lsb >> 32) & 0xff).toByte)
        assert(bytes(12) == ((lsb >> 24) & 0xff).toByte)
        assert(bytes(13) == ((lsb >> 16) & 0xff).toByte)
        assert(bytes(14) == ((lsb >> 8) & 0xff).toByte)
        assert(bytes(15) == (lsb & 0xff).toByte)
    }

    // ── Decoder ──────────────────────────────────────────────────────────────

    "uuid decodes from 16-byte binary" in {
        val uuid    = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val bytes   = encode(uuid)
        val decoded = decode(Format.Binary, bytes)
        assert(decoded == uuid)
    }

    "uuid round-trips through encode + decode" in {
        val uuid    = UUID.randomUUID()
        val bytes   = encode(uuid)
        val decoded = decode(Format.Binary, bytes)
        assert(decoded.getMostSignificantBits == uuid.getMostSignificantBits)
        assert(decoded.getLeastSignificantBits == uuid.getLeastSignificantBits)
    }

    "uuid decodes from canonical text format" in {
        val uuid    = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val s       = uuid.toString
        val bytes   = Span.from(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val decoded = decode(Format.Text, bytes)
        assert(decoded.getMostSignificantBits == uuid.getMostSignificantBits)
        assert(decoded.getLeastSignificantBits == uuid.getLeastSignificantBits)
    }

    "uuid decode from binary with wrong length raises Decode" in {
        // 15-byte buffer is too short for a valid UUID.
        val badBytes = Span.from(Array.fill[Byte](15)(0x00))
        try
            val _ = decode(Format.Binary, badBytes)
            assert(false, "Expected SqlDecodeException to be thrown")
        catch
            case ex: SqlDecodeException =>
                assert(ex.getMessage.contains("16"))
        end try
    }

    // ── Top-level SqlSchema.Column[UUID] round-trip (binary PG path) ─────────
    //
    // The `given SqlSchema.Column[java.util.UUID]` in SqlSchema.scala writes `w.uuid(v)` and reads
    // `r.nextUuid()`, which the PostgreSQL writer and reader map to `uuidBinary` and
    // `PostgresDecoder.uuid`. These tests close that loop without relying on a live PG container.

    private def uuidRowBinary(bytes: Span[Byte]): SqlRow =
        new SqlRow(
            Chunk(Maybe.Present(bytes)),
            Chunk(SqlRow.Column("id", 2950)),
            PostgresRowCodec(Format.Binary)
        )

    "SqlSchema.Column[UUID] writes via uuidBinary and decodes via PostgresDecoder.uuid (PG)" in {
        val column = summon[SqlSchema.Column[UUID]]
        val value  = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

        val params = PostgresParamWriter.write(column, value)
        assert(params.size == 1, s"expected 1 param, got ${params.size}")
        params(0).value match
            case Maybe.Present(bytes: Span[Byte] @unchecked) =>
                val expected = encode(value)
                assert(bytes.size == 16)
                (0 until 16).foreach { i => assert(bytes(i) == expected(i), s"byte $i mismatch") }
            case other => fail(s"expected Present bytes, got $other")
        end match

        kyo.Abort.run(uuidRowBinary(encode(value)).decode[UUID]).map {
            case kyo.Result.Success(decoded) =>
                assert(decoded.getMostSignificantBits == value.getMostSignificantBits)
                assert(decoded.getLeastSignificantBits == value.getLeastSignificantBits)
            case kyo.Result.Failure(e) => fail(s"the row failed to decode: $e")
            case kyo.Result.Panic(t)   => throw t
        }
    }

    // ── A derived row's UUID field uses the same column codec ────────────────
    //
    // The row derivation assembles its codec out of each field's own `SqlSchema.Column`, so a `UUID`
    // field writes through `w.uuid(...)` exactly as a bare `UUID` bind does: one OID 2950 binary
    // param, not the 36-character text form. Pinning both halves against the standalone column is
    // what makes the two paths provably one codec rather than two that happen to agree. The MySQL
    // counterpart lives in `kyo/internal/mysql/types/MysqlEncoderUuidTest.scala`.

    /** The params a whole row occupies, which `PostgresParamWriter.write` cannot take: it accepts the single-column tier only. */
    private def rowParams[A](schema: SqlSchema[A], value: A): Chunk[BoundParam[?]] =
        val writer = new PostgresParamWriter(TypeRegistry.empty)
        schema.write(value, writer)
        writer.params
    end rowParams

    "a derived row's UUID field reaches the wire through the binary uuid column" in {
        case class WithUuid(id: UUID) derives CanEqual

        val value  = WithUuid(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
        val params = rowParams(summon[SqlSchema[WithUuid]], value)
        assert(params.size == 1)
        assert(params(0).encoder.oid == PostgresEncoder.OID_UUID, s"expected OID 2950, got ${params(0).encoder.oid}")
        assert(params(0).encoder.format == Format.Binary)
        params(0).encoded match
            case Maybe.Present(bytes) =>
                assert(bytes.toArray.toSeq == encode(value.id).toArray.toSeq, "the field's bytes are the standalone column's")
            case Maybe.Absent => fail("expected the uuid field to carry bytes")
        end match

        // The read side correspondingly takes the 16-byte binary column, the same one the standalone codec reads.
        val binaryRow = new SqlRow(
            Chunk(Maybe.Present(encode(value.id))),
            Chunk(SqlRow.Column("id", 2950)),
            PostgresRowCodec(Format.Binary)
        )
        kyo.Abort.run(binaryRow.decode[WithUuid]).map {
            case kyo.Result.Success(decoded) => assert(decoded == value)
            case kyo.Result.Failure(e)       => fail(s"the row failed to decode: $e")
            case kyo.Result.Panic(t)         => throw t
        }
    }

end PostgresEncoderUuidTest
