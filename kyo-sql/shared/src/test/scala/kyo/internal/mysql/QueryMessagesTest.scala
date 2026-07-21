package kyo.internal.mysql

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.marshaller.ComQueryMarshaller
import kyo.internal.mysql.unmarshaller.ErrPacketUnmarshaller
import kyo.internal.mysql.unmarshaller.GenericResponseUnmarshaller
import kyo.internal.mysql.unmarshaller.OkPacketUnmarshaller
import kyo.internal.mysql.unmarshaller.ResultsetRowUnmarshaller

/** Tests for COM_QUERY encode, OkPacket / ErrPacket decode, and ResultsetRow decode. */
class QueryMessagesTest extends Test:

    private def decode[A](result: A < Abort[SqlException.Decode])(using kyo.test.AssertScope): A =
        Abort.run[SqlException.Decode](result).eval match
            case Result.Success(a) => a
            case Result.Failure(e) => fail(s"Decode failed: $e")
            case Result.Panic(t)   => throw t

    // ComQueryMarshaller prefixes 0x03 command byte
    "ComQueryMarshaller prefixes 0x03 command byte" in {
        val buf = new MysqlBufferWriter
        ComQueryMarshaller.write(ComQuery("SELECT 1"), buf)
        val span = buf.toSpan
        assert(span(0) == 0x03.toByte)
    }

    // ComQueryMarshaller encodes SQL bytes after command byte
    "ComQueryMarshaller encodes SQL bytes after command byte" in {
        val sql = "SELECT 1"
        val buf = new MysqlBufferWriter
        ComQueryMarshaller.write(ComQuery(sql), buf)
        val arr     = buf.toSpan.toArray
        val encoded = new String(arr.drop(1), java.nio.charset.StandardCharsets.UTF_8)
        assert(encoded == sql)
    }

    // OkPacketUnmarshaller decodes affected rows as lenenc-int (affectedRows=1)
    "OkPacketUnmarshaller decodes affected rows lenenc-int (value=1)" in {
        // Wire (after 0x00): lenenc-int(1) + lenenc-int(0) + uint16(0) + uint16(0)
        val body = Array[Byte](
            1,    // affectedRows = 1 (1-byte lenenc)
            0,    // lastInsertId = 0
            0, 0, // statusFlags
            0, 0  // warnings
        )
        val reader  = MysqlBufferReader(body)
        val decoded = decode(OkPacketUnmarshaller.read(reader))
        assert(decoded.affectedRows == 1L)
        assert(decoded.lastInsertId == 0L)
    }

    // OkPacketUnmarshaller decodes 0xFE variant (CLIENT_DEPRECATE_EOF, len>=7)
    "OkPacketUnmarshaller decodes 0xFE OK variant when len>=7" in {
        // First byte is 0xFE in the GenericResponseUnmarshaller dispatch with len>=7 → OK
        // Build a packet: 0xFE + ok body
        val bodyWithTag = Array[Byte](
            0xfe.toByte, // first byte consumed by GenericResponseUnmarshaller, but OkPacketUnmarshaller reads after it
            1,           // affectedRows = 1
            0,           // lastInsertId = 0
            0,
            0, // statusFlags
            0,
            0 // warnings
        )
        val totalLen = bodyWithTag.length
        // Simulate GenericResponseUnmarshaller dispatching to OkPacketUnmarshaller for 0xFE + len>=7
        val fullReader = MysqlBufferReader(bodyWithTag)
        val result     = decode(GenericResponseUnmarshaller.read(fullReader, totalLen, inAuthContext = false, isStmtPrepareContext = false))
        result match
            case ok: OkPacket => assert(ok.affectedRows == 1L)
            case other        => fail(s"Expected OkPacket, got $other")
    }

    // ErrPacketUnmarshaller decodes error code
    "ErrPacketUnmarshaller decodes error code 1045" in {
        // After 0xFF: uint16 LE(1045) + '#' + 5-char sqlState + message
        val errorCode = 1045 // 0x0415
        val body = Array[Byte](
            (errorCode & 0xff).toByte,        // low byte
            ((errorCode >> 8) & 0xff).toByte, // high byte
            '#'.toByte,                       // marker
            '2'.toByte,
            '8'.toByte,
            '0'.toByte,
            '0'.toByte,
            '0'.toByte, // SQLSTATE "28000"
            'A'.toByte,
            'c'.toByte,
            'c'.toByte,
            'e'.toByte,
            's'.toByte,
            's'.toByte,
            ' '.toByte,
            'd'.toByte,
            'e'.toByte,
            'n'.toByte,
            'i'.toByte,
            'e'.toByte,
            'd'.toByte
        )
        val reader  = MysqlBufferReader(body)
        val decoded = decode(ErrPacketUnmarshaller.read(reader))
        assert(decoded.errorCode == 1045)
    }

    // ErrPacketUnmarshaller decodes SQLSTATE
    "ErrPacketUnmarshaller decodes SQLSTATE 42000" in {
        val errorCode = 1064 // 0x0428
        val body = Array[Byte](
            (errorCode & 0xff).toByte,
            ((errorCode >> 8) & 0xff).toByte,
            '#'.toByte,
            '4'.toByte,
            '2'.toByte,
            '0'.toByte,
            '0'.toByte,
            '0'.toByte, // "42000"
            'e'.toByte,
            'r'.toByte,
            'r'.toByte
        )
        val reader  = MysqlBufferReader(body)
        val decoded = decode(ErrPacketUnmarshaller.read(reader))
        assert(decoded.sqlState == "42000")
    }

    // ErrPacketUnmarshaller decodes error message as rest-of-packet
    "ErrPacketUnmarshaller decodes error message from rest of packet" in {
        val errorCode = 2000
        val message   = "Unknown error"
        val body =
            val w = new MysqlBufferWriter
            w.writeUInt16LE(errorCode)
            w.writeByte('#'.toByte)
            w.writeFixedString("HY000")
            w.writeFixedString(message)
            w.toSpan.toArray
        end body
        val reader  = MysqlBufferReader(body)
        val decoded = decode(ErrPacketUnmarshaller.read(reader))
        assert(decoded.errorMessage == message)
    }

    // ResultSetRowUnmarshaller NULL column 0xFB → Absent
    "ResultsetRowUnmarshaller NULL column 0xFB decodes as Absent" in {
        // Two columns: first is NULL (0xFB), second is "hi" (lenenc-str)
        val body = Array[Byte](
            0xfb.toByte, // NULL
            2.toByte,
            'h'.toByte,
            'i'.toByte // lenenc-str "hi"
        )
        val reader  = MysqlBufferReader(body)
        val decoded = decode(ResultsetRowUnmarshaller(2).read(reader))
        assert(decoded.values(0) == Maybe.Absent)
        decoded.values(1) match
            case Maybe.Present(b) =>
                assert(b.size == 2)
                assert(b(0) == 'h'.toByte)
                assert(b(1) == 'i'.toByte)
            case Maybe.Absent => fail("Expected Present for column 1")
        end match
    }

    // ResultSetRowUnmarshaller non-null column as lenenc-string
    "ResultsetRowUnmarshaller non-null column decoded as lenenc-string bytes" in {
        val body    = Array[Byte](3.toByte, 'a'.toByte, 'b'.toByte, 'c'.toByte)
        val reader  = MysqlBufferReader(body)
        val decoded = decode(ResultsetRowUnmarshaller(1).read(reader))
        decoded.values(0) match
            case Maybe.Present(bytes) =>
                assert(bytes.size == 3)
                assert(bytes(0) == 'a'.toByte)
            case Maybe.Absent => fail("Expected Present")
        end match
    }

    // GenericResponseUnmarshaller dispatches 0x00 to OkPacket
    "GenericResponseUnmarshaller dispatches 0x00 first byte to OkPacket" in {
        val body   = Array[Byte](0x00.toByte, 0, 0, 0, 0, 0, 0)
        val reader = MysqlBufferReader(body)
        val result = decode(GenericResponseUnmarshaller.read(reader, body.length, inAuthContext = false, isStmtPrepareContext = false))
        assert(result.isInstanceOf[OkPacket])
    }

    // GenericResponseUnmarshaller dispatches 0xFF to ErrPacket
    "GenericResponseUnmarshaller dispatches 0xFF first byte to ErrPacket" in {
        val body =
            val w = new MysqlBufferWriter
            w.writeByte(0xff.toByte)
            w.writeUInt16LE(1000)
            w.writeByte('#'.toByte)
            w.writeFixedString("HY000")
            w.writeFixedString("error")
            w.toSpan.toArray
        end body
        val reader = MysqlBufferReader(body)
        val result = decode(GenericResponseUnmarshaller.read(reader, body.length, inAuthContext = false, isStmtPrepareContext = false))
        assert(result.isInstanceOf[ErrPacket])
    }

    // OkPacketUnmarshaller: statusFlags and warnings decoded correctly
    "OkPacketUnmarshaller decodes statusFlags and warnings from wire bytes" in {
        val body = Array[Byte](
            0, // affectedRows
            0, // lastInsertId
            0x02.toByte,
            0x00.toByte, // statusFlags = 2 (SERVER_STATUS_AUTOCOMMIT)
            0x05.toByte,
            0x00.toByte // warnings = 5
        )
        val reader  = MysqlBufferReader(body)
        val decoded = decode(OkPacketUnmarshaller.read(reader))
        assert(decoded.statusFlags == 2.toShort)
        assert(decoded.warnings == 5.toShort)
    }

end QueryMessagesTest
