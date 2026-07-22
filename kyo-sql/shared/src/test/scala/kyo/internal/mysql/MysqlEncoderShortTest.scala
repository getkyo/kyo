package kyo.internal.mysql

import kyo.Chunk
import kyo.Maybe
import kyo.Span
import kyo.SqlDecodeException
import kyo.SqlException
import kyo.SqlRow
import kyo.Test
import kyo.internal.mysql.types.MysqlDecoder
import kyo.internal.mysql.types.MysqlEncoder
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.types.Format

/** Tests for the MySQL SHORT (TYPE_SHORT = 0x02) wire codec.
  *
  * Verifies that [[MysqlEncoder.shortEncoder]] and [[MysqlDecoder.shortDecoder]] produce and consume byte-exact 2-byte little-endian
  * representations of Scala [[Short]] values as required by the MySQL binary protocol (§14.7.4).
  *
  * All tests are pure unit tests on wire bytes; no MySQL container or network I/O is required.
  */
class MysqlEncoderShortTest extends Test:

    // ── Helpers ───────────────────────────────────────────────────────────────

    private def encode(value: Short): Array[Byte] =
        val buf = new MysqlBufferWriter
        MysqlEncoder.shortEncoder.write(value, buf)
        buf.toSpan.toArray
    end encode

    private def decode(body: Array[Byte]): Short =
        kyo.Abort.run(MysqlDecoder.shortDecoder.decode(Span.from(body))).eval match
            case kyo.Result.Success(s) => s
            case kyo.Result.Failure(e) => throw e
            case kyo.Result.Panic(t)   => throw t
        end match
    end decode

    private def roundTrip(value: Short): Short =
        decode(encode(value))
    end roundTrip

    // ── typeCode test ─────────────────────────────────────────────────────────

    "shortEncoder has mysqlType = TYPE_SHORT (0x02)" in {
        assert(MysqlEncoder.shortEncoder.mysqlType == MysqlEncoder.TYPE_SHORT)
        assert(MysqlEncoder.shortEncoder.mysqlType == 0x02)
    }

    // ── Encode tests ─────────────────────────────────────────────────────────

    "shortEncoder encodes Short.MaxValue as 2 bytes LE" in {
        // Short.MaxValue = 32767 = 0x7FFF; LE → [0xFF, 0x7F]
        val bytes = encode(Short.MaxValue)
        assert(bytes.length == 2, s"expected 2 bytes, got ${bytes.length}")
        assert(bytes(0) == 0xff.toByte, s"expected low byte 0xFF, got 0x${(bytes(0) & 0xff).toHexString}")
        assert(bytes(1) == 0x7f.toByte, s"expected high byte 0x7F, got 0x${(bytes(1) & 0xff).toHexString}")
    }

    "shortEncoder encodes Short.MinValue as 2 bytes LE" in {
        // Short.MinValue = -32768 = 0x8000; LE → [0x00, 0x80]
        val bytes = encode(Short.MinValue)
        assert(bytes.length == 2, s"expected 2 bytes, got ${bytes.length}")
        assert(bytes(0) == 0x00.toByte, s"expected low byte 0x00, got 0x${(bytes(0) & 0xff).toHexString}")
        assert(bytes(1) == 0x80.toByte, s"expected high byte 0x80, got 0x${(bytes(1) & 0xff).toHexString}")
    }

    "shortEncoder encodes 0 as 2 zero bytes" in {
        val bytes = encode(0.toShort)
        assert(bytes.length == 2, s"expected 2 bytes, got ${bytes.length}")
        assert(bytes(0) == 0x00.toByte, s"expected 0x00 at byte 0, got 0x${(bytes(0) & 0xff).toHexString}")
        assert(bytes(1) == 0x00.toByte, s"expected 0x00 at byte 1, got 0x${(bytes(1) & 0xff).toHexString}")
    }

    "shortEncoder encodes -1 as 2 bytes LE (0xFF, 0xFF)" in {
        // -1 = 0xFFFF; LE → [0xFF, 0xFF]
        val bytes = encode((-1).toShort)
        assert(bytes.length == 2, s"expected 2 bytes, got ${bytes.length}")
        assert(bytes(0) == 0xff.toByte, s"expected 0xFF at byte 0, got 0x${(bytes(0) & 0xff).toHexString}")
        assert(bytes(1) == 0xff.toByte, s"expected 0xFF at byte 1, got 0x${(bytes(1) & 0xff).toHexString}")
    }

    // ── Decode tests ─────────────────────────────────────────────────────────

    "shortDecoder decodes from 2 bytes LE (Short.MaxValue)" in {
        val body   = Array[Byte](0xff.toByte, 0x7f.toByte) // 0x7FFF LE = 32767
        val result = decode(body)
        assert(result == Short.MaxValue, s"expected ${Short.MaxValue}, got $result")
    }

    "shortDecoder decodes from 2 bytes LE (Short.MinValue)" in {
        val body   = Array[Byte](0x00.toByte, 0x80.toByte) // 0x8000 LE = -32768
        val result = decode(body)
        assert(result == Short.MinValue, s"expected ${Short.MinValue}, got $result")
    }

    "shortDecoder decodes zero from 2 zero bytes" in {
        val body   = Array[Byte](0x00.toByte, 0x00.toByte)
        val result = decode(body)
        assert(result == 0.toShort, s"expected 0, got $result")
    }

    "shortDecoder fails on fewer than 2 bytes" in {
        val ex = intercept[SqlDecodeException] {
            decode(Array[Byte](0x01.toByte))
        }
        assert(
            ex.getMessage.contains("SHORT") || ex.getMessage.contains("2 bytes"),
            s"expected SHORT size-error message, got: ${ex.getMessage}"
        )
    }

    // ── Round-trip tests ──────────────────────────────────────────────────────

    "Short round-trips through encode + decode (Short.MaxValue)" in {
        assert(roundTrip(Short.MaxValue) == Short.MaxValue)
    }

    "Short round-trips through encode + decode (Short.MinValue)" in {
        assert(roundTrip(Short.MinValue) == Short.MinValue)
    }

    "Short round-trips through encode + decode (0)" in {
        assert(roundTrip(0.toShort) == 0.toShort)
    }

    "Short round-trips through encode + decode (negative and positive values)" in {
        val values: List[Short] = List(-1, 1, -100, 100, -32767, 32767).map(_.toShort)
        assert(
            values.forall { v => roundTrip(v) == v },
            s"round-trip failed for one or more values"
        )
    }

    // ── Schema-derived round-trip tests (real write + read via SqlSchema) ────

    // Encodes `value` via shortEncoder and wraps the bytes in a single-column SqlRow whose
    // FieldDescription marks the column as binary-format SHORT. Mirrors the structure produced
    // by BinaryResultsetRowUnmarshaller for a SHORT column in extended-protocol results.
    private def shortRow(value: Short): SqlRow =
        val buf = new MysqlBufferWriter
        MysqlEncoder.shortEncoder.write(value, buf)
        new SqlRow(
            Chunk(Maybe.Present(buf.toSpan)),
            Chunk(FieldDescription("n", 0, 0, 0, 0, 0, 0)),
            Format.Binary
        )
    end shortRow

    "case class with Short field round-trips via MySQL" in {
        case class Row(n: Short) derives CanEqual
        import kyo.SqlSchema
        given SqlSchema[Row] = SqlSchema.derived

        val schema = SqlSchema[Row]
        val value  = Row(42.toShort)
        val params = schema.writeMysql(value)(using kyo.Frame.derive)
        assert(params.size == 1, s"expected 1 param, got ${params.size}")
        val p = params(0)
        assert(
            p.encoder.mysqlType == MysqlEncoder.TYPE_SHORT,
            s"expected TYPE_SHORT (${MysqlEncoder.TYPE_SHORT}), got ${p.encoder.mysqlType}"
        )
        // Real read leg: feed shortEncoder bytes into MysqlRowReader via Schema.readMysql.
        kyo.Abort.run(schema.readMysql(shortRow(value.n))(using kyo.Frame.derive)).map {
            case kyo.Result.Success(decoded) => assert(decoded == value, s"round-trip mismatch: $decoded != $value")
            case kyo.Result.Failure(e)       => fail(s"readMysql failed: $e")
            case kyo.Result.Panic(t)         => throw t
        }
    }

    "negative and Short.MaxValue round-trip via schema writeMysql + readMysql" in {
        case class Row(n: Short) derives CanEqual
        import kyo.SqlSchema
        given SqlSchema[Row] = SqlSchema.derived

        val schema = SqlSchema[Row]
        val rows   = List(Row(Short.MaxValue), Row(Short.MinValue), Row(-1.toShort), Row(0.toShort), Row(12345.toShort))

        kyo.Kyo.foreach(rows) { row =>
            val params = schema.writeMysql(row)(using kyo.Frame.derive)
            assert(params.size == 1 && params(0).encoder.mysqlType == MysqlEncoder.TYPE_SHORT)
            kyo.Abort.run(schema.readMysql(shortRow(row.n))(using kyo.Frame.derive)).map {
                case kyo.Result.Success(decoded) => assert(decoded == row, s"round-trip mismatch: $decoded != $row")
                case kyo.Result.Failure(e)       => fail(s"readMysql failed for $row: $e")
                case kyo.Result.Panic(t)         => throw t
            }
        }.map(_ => succeed)
    }

    // ── given/decoder structural inertness probes ─────────────────────────────
    //
    // The given MysqlDecoder[Short] and the shortDecoder singleton are not consumed by
    // MysqlRowReader (which uses an inline readInt2LE helper). These two leaves keep the
    // singleton and the given exercised so any structural drift (signature change, mysqlType
    // mismatch, decoder removal) breaks the build/tests rather than silently going unnoticed.

    "given MysqlDecoder[Short] is summonable and points to shortDecoder singleton" in {
        val summoned = summon[MysqlDecoder[Short]]
        // Identity check: the given is the same singleton instance as the public val.
        assert(summoned eq MysqlDecoder.shortDecoder, "given MysqlDecoder[Short] must alias shortDecoder")
    }

    "shortDecoder decodes the exact bytes emitted by shortEncoder (singleton symmetry)" in {
        val values: List[Short] = List(0, 1, -1, 32767, -32768, 12345, -9999).map(_.toShort)
        assert(
            values.forall { v =>
                // encode → 2-byte LE → decode via the singleton (not the inlined RowReader helper).
                val encoded = encode(v)
                decode(encoded) == v
            },
            "encoder/decoder singleton symmetry broken"
        )
    }

end MysqlEncoderShortTest
