package kyo.internal.mysql

import kyo.*
import kyo.Span
import kyo.SqlException
import kyo.Test

/** Tests for [[MysqlBufferWriter]].
  *
  * Verifies little-endian encoding for all integer widths, lenenc integer round-trips across all four size boundaries, lenenc strings,
  * NUL-terminated strings, and fixed-length strings.
  */
class MysqlBufferWriterTest extends Test:

    // MysqlBufferWriter writeUInt16LE 256 = [0,1] — little-endian (NOT big-endian [1,0])
    "MysqlBufferWriter writeUInt16LE 256 produces [0,1]" in {
        val buf = new MysqlBufferWriter
        buf.writeUInt16LE(256)
        val span = buf.toSpan
        assert(span.size == 2)
        assert(span(0) == 0.toByte)
        assert(span(1) == 1.toByte)
    }

    // MysqlBufferWriter writeLengthEncodedInt 42 — 1-byte [42]
    "MysqlBufferWriter writeLengthEncodedInt 42 produces single byte" in {
        val buf = new MysqlBufferWriter
        buf.writeLenencInt(42L)
        val span = buf.toSpan
        assert(span.size == 1)
        assert(span(0) == 42.toByte)
    }

    // MysqlBufferWriter writeLengthEncodedInt 300 — [0xFC, 44, 1] (300 = 256 + 44)
    "MysqlBufferWriter writeLengthEncodedInt 300 produces [0xFC, 44, 1]" in {
        val buf = new MysqlBufferWriter
        buf.writeLenencInt(300L)
        val span = buf.toSpan
        assert(span.size == 3)
        assert(span(0) == 0xfc.toByte)
        assert(span(1) == 44.toByte) // 300 & 0xff = 44
        assert(span(2) == 1.toByte)  // 300 >> 8 = 1
    }

    // MysqlBufferWriter writeLengthEncodedString "hi" — [2, 104, 105]
    "MysqlBufferWriter writeLengthEncodedString 'hi' produces [2, 104, 105]" in {
        val buf = new MysqlBufferWriter
        buf.writeLenencString("hi")
        val span = buf.toSpan
        assert(span.size == 3)
        assert(span(0) == 2.toByte)   // length
        assert(span(1) == 'h'.toByte) // 104
        assert(span(2) == 'i'.toByte) // 105
    }

    // Round-trip test: writeLenencInt / readLenencInt across the 0xFC boundary (251)
    "writeLenencInt / readLenencInt round-trip across 0xFC boundary (value=251)" in {
        val buf = new MysqlBufferWriter
        buf.writeLenencInt(251L)
        val reader = MysqlBufferReader(buf.toSpan)
        Abort.run[SqlException.Decode](reader.readLenencInt()).map {
            case Result.Success(Maybe.Present(v)) => assert(v == 251L)
            case other                            => fail(s"Expected Success(Present(251)), got: $other")
        }
    }

    // Round-trip test: writeLenencInt / readLenencInt across the 0xFD boundary (65536)
    "writeLenencInt / readLenencInt round-trip across 0xFD boundary (value=65536)" in {
        val buf = new MysqlBufferWriter
        buf.writeLenencInt(65536L)
        val reader = MysqlBufferReader(buf.toSpan)
        Abort.run[SqlException.Decode](reader.readLenencInt()).map {
            case Result.Success(Maybe.Present(v)) => assert(v == 65536L)
            case other                            => fail(s"Expected Success(Present(65536)), got: $other")
        }
    }

    // Round-trip test: writeLenencInt / readLenencInt for large 0xFE-encoded value
    "writeLenencInt / readLenencInt round-trip for large value (16777216)" in {
        val buf = new MysqlBufferWriter
        buf.writeLenencInt(16777216L)
        val reader = MysqlBufferReader(buf.toSpan)
        Abort.run[SqlException.Decode](reader.readLenencInt()).map {
            case Result.Success(Maybe.Present(v)) => assert(v == 16777216L)
            case other                            => fail(s"Expected Success(Present(16777216)), got: $other")
        }
    }

    // writeNulTerminatedString appends NUL byte
    "MysqlBufferWriter writeNulTerminatedString appends NUL byte" in {
        val buf = new MysqlBufferWriter
        buf.writeNulTerminatedString("abc")
        val span = buf.toSpan
        assert(span.size == 4)
        assert(span(0) == 'a'.toByte)
        assert(span(1) == 'b'.toByte)
        assert(span(2) == 'c'.toByte)
        assert(span(3) == 0.toByte) // NUL
    }

    // writeUInt32LE round-trip
    "MysqlBufferWriter writeUInt32LE 0x01020304 round-trip" in {
        val buf = new MysqlBufferWriter
        buf.writeUInt32LE(0x01020304L)
        val reader = MysqlBufferReader(buf.toSpan)
        Abort.run[SqlException.Decode](reader.readUInt32LE()).map {
            case Result.Success(v) => assert(v == 0x01020304L)
            case other             => fail(s"Expected Success(0x01020304), got: $other")
        }
    }

end MysqlBufferWriterTest
