package kyo.internal.mysql

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.marshaller.ComStmtExecuteMarshaller
import kyo.internal.mysql.marshaller.ComStmtPrepareMarshaller
import kyo.internal.mysql.unmarshaller.BinaryResultsetRowUnmarshaller
import kyo.internal.mysql.unmarshaller.ColumnDefinition41Unmarshaller
import kyo.internal.mysql.unmarshaller.EofPacketUnmarshaller
import kyo.internal.mysql.unmarshaller.GenericResponseUnmarshaller
import kyo.internal.mysql.unmarshaller.StmtPrepareOkUnmarshaller

/** Tests for COM_STMT_PREPARE / COM_STMT_EXECUTE encode, StmtPrepareOk decode, BinaryResultsetRow decode. */
class StmtMessagesTest extends Test:

    private def decode[A](result: A < Abort[SqlException.Decode])(using kyo.test.AssertScope): A =
        Abort.run[SqlException.Decode](result).eval match
            case Result.Success(a) => a
            case Result.Failure(e) => fail(s"Decode failed: $e")
            case Result.Panic(t)   => throw t

    // ComStmtPrepareMarshaller prefixes 0x16
    "ComStmtPrepareMarshaller prefixes 0x16 command byte" in {
        val buf = new MysqlBufferWriter
        ComStmtPrepareMarshaller.write(ComStmtPrepare("SELECT ?"), buf)
        assert(buf.toSpan(0) == 0x16.toByte)
    }

    // ComStmtPrepareMarshaller encodes SQL
    "ComStmtPrepareMarshaller encodes SQL after command byte" in {
        val sql = "SELECT ?"
        val buf = new MysqlBufferWriter
        ComStmtPrepareMarshaller.write(ComStmtPrepare(sql), buf)
        val arr     = buf.toSpan.toArray
        val decoded = new String(arr.drop(1), java.nio.charset.StandardCharsets.UTF_8)
        assert(decoded == sql)
    }

    // PrepareOkUnmarshaller decodes stmtId, numCols, numParams
    "StmtPrepareOkUnmarshaller decodes stmtId, numColumns, numParams" in {
        // After 0x00 first byte: LE uint32(stmtId=7) + LE uint16(numCols=3) + LE uint16(numParams=2) + 0x00 + LE uint16(warnings=0)
        val body = Array[Byte](
            7, 0, 0, 0, // stmtId = 7
            3, 0,       // numColumns = 3
            2, 0,       // numParams = 2
            0,          // reserved
            0, 0        // warnings = 0
        )
        val reader  = MysqlBufferReader(body)
        val decoded = decode(StmtPrepareOkUnmarshaller.read(reader))
        assert(decoded.stmtId == 7)
        assert(decoded.numColumns == 3.toShort)
        assert(decoded.numParams == 2.toShort)
        assert(decoded.warnings == 0.toShort)
    }

    // ComStmtExecuteMarshaller encodes null-bitmap correctly (2 params, param1 null)
    "ComStmtExecuteMarshaller encodes null-bitmap: 2 params, param 1 null → bit 1 set" in {
        val msg = ComStmtExecute(
            stmtId = 1,
            flags = 0,
            params = Chunk(Maybe.Present(Span.from(Array[Byte](42))), Maybe.Absent),
            paramTypes = Chunk((0x01, 0), (0x01, 0)), // TINY
            newParamsBound = 1
        )
        val buf = new MysqlBufferWriter
        ComStmtExecuteMarshaller.write(msg, buf)
        val arr = buf.toSpan.toArray
        // Layout: 0x17(1) + stmtId(4) + flags(1) + iterCount(4) = 10 bytes before null-bitmap
        val bitmapOffset = 10
        // null-bitmap for 2 params: 1 byte; bit 0 = param0 (Present), bit 1 = param1 (Absent)
        val bitmap = arr(bitmapOffset) & 0xff
        assert((bitmap & 0x01) == 0) // param 0 is NOT null
        assert((bitmap & 0x02) != 0) // param 1 IS null
    }

    // ComStmtExecuteMarshaller encodes new-params-bound flag
    "ComStmtExecuteMarshaller encodes newParamsBound=1 on first execute" in {
        val msg = ComStmtExecute(
            stmtId = 1,
            flags = 0,
            params = Chunk(Maybe.Present(Span.from(Array[Byte](5)))),
            paramTypes = Chunk((0x01, 0)),
            newParamsBound = 1
        )
        val buf = new MysqlBufferWriter
        ComStmtExecuteMarshaller.write(msg, buf)
        val arr          = buf.toSpan.toArray
        val bitmapOffset = 10 // 0x17(1) + stmtId(4) + flags(1) + iterCount(4)
        val bitmapLen    = 1  // ceil((1+7)/8) = 1
        val flagOffset   = bitmapOffset + bitmapLen
        assert(arr(flagOffset) == 1.toByte) // newParamsBound = 1
    }

    // ComStmtExecuteMarshaller newParamsBound=0 on re-execute
    "ComStmtExecuteMarshaller encodes newParamsBound=0 on re-execute" in {
        val msg = ComStmtExecute(
            stmtId = 1,
            flags = 0,
            params = Chunk(Maybe.Present(Span.from(Array[Byte](5)))),
            paramTypes = Chunk((0x01, 0)),
            newParamsBound = 0
        )
        val buf = new MysqlBufferWriter
        ComStmtExecuteMarshaller.write(msg, buf)
        val arr          = buf.toSpan.toArray
        val bitmapOffset = 10
        val bitmapLen    = 1
        val flagOffset   = bitmapOffset + bitmapLen
        assert(arr(flagOffset) == 0.toByte) // newParamsBound = 0
    }

    // BinaryResultSetRowUnmarshaller null-bitmap offset-by-2 (column 0 null: bit 2 of byte 0)
    "BinaryResultsetRowUnmarshaller: column 0 null when bit 2 of first bitmap byte is set" in {
        // 1 column; bitmap len = ceil((1+2+7)/8) = 1
        // Column 0 null → bit (0+2) = bit 2 set → bitmap byte = 0b00000100 = 4
        val bitmap = 0x04.toByte // bit 2 set
        val body   = Array[Byte](bitmap)
        // No value bytes follow (column is null)
        val reader  = MysqlBufferReader(body)
        val decoded = decode(BinaryResultsetRowUnmarshaller(1, Chunk(0x03)).read(reader))
        assert(decoded.values(0) == Maybe.Absent)
    }

    // BinaryResultSetRowUnmarshaller LONG column 4 bytes LE → 42
    "BinaryResultsetRowUnmarshaller LONG column reads 4 bytes little-endian (value=42)" in {
        // 1 column LONG (type 0x03); bitmap = 0 (not null)
        // bitmap: bit 2 = 0 (not null); bitmap byte = 0
        val body = Array[Byte](
            0x00.toByte, // null-bitmap (no nulls)
            42,
            0,
            0,
            0 // LONG value = 42 in LE
        )
        val reader  = MysqlBufferReader(body)
        val decoded = decode(BinaryResultsetRowUnmarshaller(1, Chunk(0x03)).read(reader))
        decoded.values(0) match
            case Maybe.Present(bytes) =>
                assert(bytes.size == 4)
                // LE: bytes[0] is the low byte
                val value = (bytes(0) & 0xff) | ((bytes(1) & 0xff) << 8) | ((bytes(2) & 0xff) << 16) | ((bytes(3) & 0xff) << 24)
                assert(value == 42)
            case Maybe.Absent => fail("Expected Present")
        end match
    }

    // EofPacketUnmarshaller first byte 0xFE len<9 → EofPacket
    "EofPacketUnmarshaller decodes warnings and statusFlags" in {
        // Body after 0xFE (already consumed): uint16(warnings=3) + uint16(statusFlags=2)
        val body    = Array[Byte](3, 0, 2, 0)
        val reader  = MysqlBufferReader(body)
        val decoded = decode(EofPacketUnmarshaller.read(reader))
        assert(decoded.warnings == 3.toShort)
        assert(decoded.statusFlags == 2.toShort)
    }

    // GenericResponseUnmarshaller: 0xFE + len<9 → EofPacket
    "GenericResponseUnmarshaller dispatches 0xFE+len<9 to EofPacket" in {
        val body    = Array[Byte](0xfe.toByte, 0, 0, 0, 0) // 5 bytes total < 9
        val reader  = MysqlBufferReader(body)
        val decoded = decode(GenericResponseUnmarshaller.read(reader, body.length, inAuthContext = false, isStmtPrepareContext = false))
        assert(decoded.isInstanceOf[EofPacket])
    }

    // ColumnDefinition41Unmarshaller decodes type byte
    "ColumnDefinition41Unmarshaller decodes columnType correctly" in {
        // Build a minimal ColumnDefinition41 body
        val buf = new MysqlBufferWriter
        buf.writeLenencString("def")      // catalog
        buf.writeLenencString("testdb")   // schema
        buf.writeLenencString("mytable")  // table
        buf.writeLenencString("mytable")  // orgTable
        buf.writeLenencString("id")       // name
        buf.writeLenencString("id")       // orgName
        buf.writeLenencInt(12L)           // fixed-length field marker (0x0C)
        buf.writeUInt16LE(33)             // charset (utf8)
        buf.writeUInt32LE(11L)            // columnLength
        buf.writeUInt8(0x03)              // columnType = LONG (0x03)
        buf.writeUInt16LE(0x0001)         // flags (NOT_NULL)
        buf.writeUInt8(0)                 // decimals
        buf.writeBytes(Array[Byte](0, 0)) // filler

        val reader  = MysqlBufferReader(buf.toSpan)
        val decoded = decode(ColumnDefinition41Unmarshaller.read(reader))
        assert(decoded.columnType == 0x03) // LONG
        assert(decoded.name == "id")
        assert(decoded.schema == "testdb")
    }

    // ColumnDefinition41Unmarshaller decodes unsigned flag
    "ColumnDefinition41Unmarshaller decodes UNSIGNED flag from flags field" in {
        val UnsignedFlag = 0x0020 // MySQL UNSIGNED flag
        val buf          = new MysqlBufferWriter
        buf.writeLenencString("def")
        buf.writeLenencString("db")
        buf.writeLenencString("t")
        buf.writeLenencString("t")
        buf.writeLenencString("n")
        buf.writeLenencString("n")
        buf.writeLenencInt(12L)
        buf.writeUInt16LE(63)           // charset
        buf.writeUInt32LE(20L)          // columnLength
        buf.writeUInt8(0x08)            // columnType = LONGLONG
        buf.writeUInt16LE(UnsignedFlag) // UNSIGNED flag
        buf.writeUInt8(0)
        buf.writeBytes(Array[Byte](0, 0))

        val reader  = MysqlBufferReader(buf.toSpan)
        val decoded = decode(ColumnDefinition41Unmarshaller.read(reader))
        assert((decoded.flags & UnsignedFlag) != 0)
    }

    // AuthSwitchRequestUnmarshaller decodes plugin name and data
    "AuthSwitchRequestUnmarshaller decodes plugin name NUL-terminated + auth data" in {
        val buf = new MysqlBufferWriter
        buf.writeNulTerminatedString("caching_sha2_password")
        val salt = Array.fill(20)(0x7f.toByte)
        buf.writeBytes(salt)

        val reader  = MysqlBufferReader(buf.toSpan)
        val decoded = decode(kyo.internal.mysql.unmarshaller.AuthSwitchRequestUnmarshaller.read(reader))
        assert(decoded.pluginName == "caching_sha2_password")
        assert(decoded.pluginData.size == 20)
        assert(decoded.pluginData(0) == 0x7f.toByte)
    }

    // AuthMoreDataUnmarshaller decodes 0x03 fast-ok
    "AuthMoreDataUnmarshaller decodes 0x03 fast-path success byte" in {
        val body    = Array[Byte](0x03.toByte)
        val reader  = MysqlBufferReader(body)
        val decoded = decode(kyo.internal.mysql.unmarshaller.AuthMoreDataUnmarshaller.read(reader))
        assert(decoded.data.size == 1)
        assert(decoded.data(0) == 0x03.toByte)
    }

    // AuthMoreDataUnmarshaller decodes 0x04 full-auth-needed
    "AuthMoreDataUnmarshaller decodes 0x04 full-auth-required byte" in {
        val body    = Array[Byte](0x04.toByte)
        val reader  = MysqlBufferReader(body)
        val decoded = decode(kyo.internal.mysql.unmarshaller.AuthMoreDataUnmarshaller.read(reader))
        assert(decoded.data.size == 1)
        assert(decoded.data(0) == 0x04.toByte)
    }

    // BinaryResultsetRowUnmarshaller Chunk.newBuilder: multi-column row preserves element order
    "BinaryResultsetRowUnmarshaller readColumns yields correct Chunk for 3-column row (null, LONG, TINY)" in {
        // 3 columns: col0=null, col1=LONG(42), col2=TINY(7)
        // null-bitmap: (3+2+7)/8 = 12/8 = 1 byte (integer division in the implementation)
        // col0 null → bit (0+2)=2 → byte 0, bit 2 → 0b00000100 = 0x04
        val bitmapByte = 0x04.toByte
        val body = Array[Byte](
            bitmapByte,
            42,
            0,
            0,
            0, // col1: LONG(0x03) = 4 bytes LE = 42
            7  // col2: TINY(0x01) = 1 byte = 7
        )
        val reader  = MysqlBufferReader(body)
        val decoded = decode(BinaryResultsetRowUnmarshaller(3, Chunk(0x03, 0x03, 0x01)).read(reader))
        // Verify 3 values in correct order
        assert(decoded.values.size == 3)
        assert(decoded.values(0) == Maybe.Absent)
        decoded.values(1) match
            case Maybe.Present(bytes) =>
                assert(bytes.size == 4)
                val v = (bytes(0) & 0xff) | ((bytes(1) & 0xff) << 8) | ((bytes(2) & 0xff) << 16) | ((bytes(3) & 0xff) << 24)
                assert(v == 42)
            case Maybe.Absent => fail("col1 should be Present")
        end match
        decoded.values(2) match
            case Maybe.Present(bytes) =>
                assert(bytes.size == 1)
                assert((bytes(0) & 0xff) == 7)
            case Maybe.Absent => fail("col2 should be Present")
        end match
    }

    // ResultsetRowUnmarshaller Chunk.newBuilder: multi-column row preserves element order
    "ResultsetRowUnmarshaller readColumns yields correct Chunk for 3-column row (NULL, str, str)" in {
        import kyo.internal.mysql.unmarshaller.ResultsetRowUnmarshaller
        val buf = new MysqlBufferWriter
        buf.writeUInt8(0xfb) // col0: NULL sentinel
        buf.writeLenencString("hello")
        buf.writeLenencString("world")

        val reader  = MysqlBufferReader(buf.toSpan)
        val decoded = decode(ResultsetRowUnmarshaller(3).read(reader))
        assert(decoded.values.size == 3)
        assert(decoded.values(0) == Maybe.Absent)
        decoded.values(1) match
            case Maybe.Present(bytes) =>
                assert(new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8) == "hello")
            case Maybe.Absent => fail("col1 should be Present")
        end match
        decoded.values(2) match
            case Maybe.Present(bytes) =>
                assert(new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8) == "world")
            case Maybe.Absent => fail("col2 should be Present")
        end match
    }

end StmtMessagesTest
