package kyo.internal.postgres.types

import java.util.UUID
import kyo.*
import kyo.SqlException
import kyo.SqlRow
import kyo.SqlSchema
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.PostgresBufferWriter

/** Unit tests for the PG UUID binary codec (OID 2950).
  *
  * Wire format: 16 bytes big-endian — mostSignificantBits (Int64) followed by leastSignificantBits (Int64).
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
            assert(false, "Expected SqlException.Decode to be thrown")
        catch
            case ex: SqlException.Decode =>
                assert(ex.getMessage.contains("16"))
        end try
    }

    // ── Top-level SqlSchema[UUID] round-trip (binary PG path) ────────────────
    //
    // The `given SqlSchema[java.util.UUID]` in SqlSchema.scala routes through
    // `pw.custom("uuid", uuidBinary.write(v), Format.Binary)` on the write side and through
    // `PostgresDecoder.uuid.read(Format.Binary, pr.custom("uuid"))` on the read side. These
    // tests close that loop without relying on a live PG container.

    private def uuidRowBinary(value: UUID): SqlRow =
        new SqlRow(
            Chunk(Maybe.Present(encode(value))),
            Chunk(FieldDescription("id", 0, 0, 2950, 16, 0, 1)),
            Format.Binary
        )

    "SqlSchema[UUID] writes via uuidBinary and decodes via PostgresDecoder.uuid (PG)" in {
        val schema = summon[SqlSchema[UUID]]
        val value  = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

        val params = schema.writePostgres(value)
        assert(params.size == 1, s"expected 1 param, got ${params.size}")
        params(0).value match
            case Maybe.Present(bytes: Span[Byte] @unchecked) =>
                val expected = encode(value)
                assert(bytes.size == 16)
                (0 until 16).foreach { i => assert(bytes(i) == expected(i), s"byte $i mismatch") }
            case other => fail(s"expected Present bytes, got $other")
        end match

        kyo.Abort.run(schema.readPostgres(uuidRowBinary(value))).map {
            case kyo.Result.Success(decoded) =>
                assert(decoded.getMostSignificantBits == value.getMostSignificantBits)
                assert(decoded.getLeastSignificantBits == value.getLeastSignificantBits)
            case kyo.Result.Failure(e) => fail(s"readPostgres failed: $e")
            case kyo.Result.Panic(t)   => throw t
        }
    }

    // ── Top-level SqlSchema[UUID] round-trip (string MySQL path) ─────────────

    private def uuidRowMysql(value: UUID): SqlRow =
        val raw = value.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        new SqlRow(
            Chunk(Maybe.Present(Span.from(raw))),
            Chunk(FieldDescription("id", 0, 0, 0, 0, 0, 0)),
            Format.Binary
        )
    end uuidRowMysql

    "SqlSchema[UUID] writes via string fallback and decodes via string (MySQL)" in {
        val schema = summon[SqlSchema[UUID]]
        val value  = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

        val params = schema.writeMysql(value)
        assert(params.size == 1, s"expected 1 param, got ${params.size}")
        params(0).value match
            case Maybe.Present(s: String) => assert(s == value.toString)
            case other                    => fail(s"expected Present string, got $other")
        end match

        kyo.Abort.run(schema.readMysql(uuidRowMysql(value))).map {
            case kyo.Result.Success(decoded) =>
                assert(decoded.getMostSignificantBits == value.getMostSignificantBits)
                assert(decoded.getLeastSignificantBits == value.getLeastSignificantBits)
            case kyo.Result.Failure(e) => fail(s"readMysql failed: $e")
            case kyo.Result.Panic(t)   => throw t
        }
    }

    // ── Schema.derived[CaseClassWithUuid] — structural gap exposure ──────────
    //
    // `kyo.internal.SerializationMacro` hardcodes a string-based UUID arm at compile time
    // (`writer.string(value.toString)` / `UUID.fromString(reader.string())`), which means
    // `SqlSchema.derived[T]` for a case class containing a `UUID` field does NOT consult the
    // `given SqlSchema[java.util.UUID]` defined in `SqlSchema.scala`. The case-class field
    // path therefore goes through the string codec (PG textText / MySQL string), bypassing
    // the binary `pw.custom("uuid", _, Binary)` route. These two leaves pin that behavior so
    // any future macro change that consults the given Schema instance becomes test-visible.

    "Schema.derived[CaseClassWithUuid] takes string path on PG (kyo-schema macro inlining)" in {
        case class WithUuid(id: UUID) derives CanEqual
        given SqlSchema[WithUuid] = SqlSchema.derived

        val schema = SqlSchema[WithUuid]
        val value  = WithUuid(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))

        val params = schema.writePostgres(value)
        assert(params.size == 1)
        params(0).value match
            case Maybe.Present(s: String) =>
                // Confirms the derived-write took the string arm, not pw.custom("uuid", ...).
                assert(s == value.id.toString, s"expected toString form, got '$s'")
            case other => fail(s"expected Present string (macro inlining via writer.string), got $other")
        end match

        // The read side correspondingly expects a text-format UUID column, not binary.
        val textRow = new SqlRow(
            Chunk(Maybe.Present(Span.from(value.id.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)))),
            Chunk(FieldDescription("id", 0, 0, 2950, 36, 0, 0)),
            Format.Text
        )
        kyo.Abort.run(schema.readPostgres(textRow)).map {
            case kyo.Result.Success(decoded) => assert(decoded == value)
            case kyo.Result.Failure(e)       => fail(s"readPostgres failed: $e")
            case kyo.Result.Panic(t)         => throw t
        }
    }

    "Schema.derived[CaseClassWithUuid] takes string path on MySQL" in {
        case class WithUuid(id: UUID) derives CanEqual
        given SqlSchema[WithUuid] = SqlSchema.derived

        val schema = SqlSchema[WithUuid]
        val value  = WithUuid(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))

        val params = schema.writeMysql(value)
        assert(params.size == 1)
        params(0).value match
            case Maybe.Present(s: String) => assert(s == value.id.toString)
            case other                    => fail(s"expected Present string, got $other")
        end match

        kyo.Abort.run(schema.readMysql(uuidRowMysql(value.id))).map {
            case kyo.Result.Success(decoded) => assert(decoded == value)
            case kyo.Result.Failure(e)       => fail(s"readMysql failed: $e")
            case kyo.Result.Panic(t)         => throw t
        }
    }

end PostgresEncoderUuidTest
