package kyo.internal.mysql

import kyo.Chunk
import kyo.Maybe
import kyo.Span
import kyo.SqlCodec.Format
import kyo.SqlDecodeValueRangeException
import kyo.SqlRow
import kyo.SqlSchema
import kyo.Test
import kyo.internal.mysql.types.MysqlEncoder

/** Tests for the MySQL SHORT (TYPE_SHORT = 0x02) wire codec.
  *
  * Verifies that [[MysqlEncoder.shortEncoder]] produces byte-exact 2-byte little-endian representations of Scala [[Short]] values as
  * required by the MySQL binary protocol (§14.7.4), and that a `SHORT` column carrying those bytes reads back as the same value.
  *
  * The read leg goes through [[MysqlRowReader]], which is the decoder a `Short` field really meets: this backend carries no per-type
  * decoder table, so there is no second reader for the assertions to exercise instead.
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

    /** Reads `body` back as a `SHORT` column value, through the reader a `Short` field decodes through. */
    private def decode(body: Array[Byte]): Short =
        val row = new SqlRow(
            Chunk(Maybe.Present(Span.from(body))),
            Chunk(SqlRow.Column("n", MysqlColumnToken(MysqlEncoder.TYPE_SHORT, 0, charset = 45))),
            MysqlRowCodec(Format.Binary)
        )
        new MysqlRowReader(row, Format.Binary)(using kyo.Frame.derive).short()
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

    "a SHORT column decodes from 2 bytes LE (Short.MaxValue)" in {
        val body   = Array[Byte](0xff.toByte, 0x7f.toByte) // 0x7FFF LE = 32767
        val result = decode(body)
        assert(result == Short.MaxValue, s"expected ${Short.MaxValue}, got $result")
    }

    "a SHORT column decodes from 2 bytes LE (Short.MinValue)" in {
        val body   = Array[Byte](0x00.toByte, 0x80.toByte) // 0x8000 LE = -32768
        val result = decode(body)
        assert(result == Short.MinValue, s"expected ${Short.MinValue}, got $result")
    }

    "a SHORT column decodes zero from 2 zero bytes" in {
        val body   = Array[Byte](0x00.toByte, 0x00.toByte)
        val result = decode(body)
        assert(result == 0.toShort, s"expected 0, got $result")
    }

    // ── Declared-width tests ──────────────────────────────────────────────────
    //
    // A `SHORT` column is two bytes wide under the binary protocol (§14.7.4), and
    // `BinaryResultsetRowUnmarshaller.readColumnValue` reads exactly two for it, so neither a one-byte nor a
    // three-byte payload is a value this column can carry. Both must be reported, not coerced onto another type's arm
    // by the payload's byte count. The one-byte leaf is asserted first, and the three-byte leaf pins the pair so a
    // later change cannot split them.

    "a one-byte payload on a SHORT column is reported, not read as if the column were narrower" in {
        // Read at the TINY width this decodes as 7, which is a number the column never sent.
        val ex = intercept[SqlDecodeValueRangeException](decode(Array[Byte](0x07.toByte)))
        assert(ex.scalaType == "Short", s"expected the Short target named, got ${ex.scalaType}")
        assert(ex.wireValue == "1 bytes", s"expected the payload's own width reported, got ${ex.wireValue}")
        assert(
            ex.wireDescription == "integer column declared 2 bytes wide",
            s"expected the column's declared width reported, got ${ex.wireDescription}"
        )
    }

    "a three-byte payload on a SHORT column is reported against the same declared width" in {
        val ex = intercept[SqlDecodeValueRangeException](decode(Array[Byte](0x01.toByte, 0x02.toByte, 0x03.toByte)))
        assert(ex.scalaType == "Short", s"expected the Short target named, got ${ex.scalaType}")
        assert(ex.wireValue == "3 bytes", s"expected the payload's own width reported, got ${ex.wireValue}")
        assert(
            ex.wireDescription == "integer column declared 2 bytes wide",
            s"both widths must be refused by the column's declared width, got ${ex.wireDescription}"
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

    // Encodes `value` via shortEncoder and wraps the bytes in a single-column MySQL row. Mirrors the
    // structure produced by BinaryResultsetRowUnmarshaller for a SHORT column in extended-protocol
    // results: the struct body, no length prefix, and no PostgreSQL-style type token.
    private def shortRow(value: Short): SqlRow =
        val buf = new MysqlBufferWriter
        MysqlEncoder.shortEncoder.write(value, buf)
        new SqlRow(
            Chunk(Maybe.Present(buf.toSpan)),
            Chunk(SqlRow.Column("n", 0)),
            MysqlRowCodec(Format.Binary)
        )
    end shortRow

    /** The params a whole row occupies, which `MysqlParamWriter.write` cannot take: it accepts the single-column tier only. */
    private def rowParams[A](schema: SqlSchema[A], value: A): Chunk[BoundMysqlParam[?]] =
        val writer = new MysqlParamWriter()(using kyo.Frame.derive)
        schema.write(value, writer)
        writer.params
    end rowParams

    "case class with Short field round-trips via MySQL" in {
        case class Row(n: Short) derives CanEqual

        val schema = summon[SqlSchema[Row]]
        val value  = Row(42.toShort)
        val params = rowParams(schema, value)
        assert(params.size == 1, s"expected 1 param, got ${params.size}")
        val p = params(0)
        assert(
            p.encoder.mysqlType == MysqlEncoder.TYPE_SHORT,
            s"expected TYPE_SHORT (${MysqlEncoder.TYPE_SHORT}), got ${p.encoder.mysqlType}"
        )
        // Real read leg: feed shortEncoder bytes through the row's MySQL codec.
        kyo.Abort.run(shortRow(value.n).decode[Row](using kyo.Frame.derive, schema)).map {
            case kyo.Result.Success(decoded) => assert(decoded == value, s"round-trip mismatch: $decoded != $value")
            case kyo.Result.Failure(e)       => fail(s"the row failed to decode: $e")
            case kyo.Result.Panic(t)         => throw t
        }
    }

    "negative and Short.MaxValue round-trip through the MySQL param writer and row codec" in {
        case class Row(n: Short) derives CanEqual

        val schema = summon[SqlSchema[Row]]
        val rows   = List(Row(Short.MaxValue), Row(Short.MinValue), Row((-1).toShort), Row(0.toShort), Row(12345.toShort))

        kyo.Kyo.foreach(rows) { row =>
            val params = rowParams(schema, row)
            assert(params.size == 1 && params(0).encoder.mysqlType == MysqlEncoder.TYPE_SHORT)
            kyo.Abort.run(shortRow(row.n).decode[Row](using kyo.Frame.derive, schema)).map {
                case kyo.Result.Success(decoded) => assert(decoded == row, s"round-trip mismatch: $decoded != $row")
                case kyo.Result.Failure(e)       => fail(s"the row failed to decode for $row: $e")
                case kyo.Result.Panic(t)         => throw t
            }
        }.map(_ => succeed)
    }

end MysqlEncoderShortTest
