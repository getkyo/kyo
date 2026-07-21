package kyo.internal.mysql

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.marshaller.HandshakeResponse41Marshaller
import kyo.internal.mysql.marshaller.SslRequestMarshaller
import kyo.internal.mysql.unmarshaller.HandshakeV10Unmarshaller

/** Tests for [[HandshakeV10]] decode and [[HandshakeResponse41]] encode.
  *
  * Bytes are constructed to match the MySQL protocol spec exactly.
  */
class HandshakeMessagesTest extends Test:

    private def decode[A](result: A < Abort[SqlException.Decode])(using kyo.test.AssertScope): A =
        Abort.run[SqlException.Decode](result).eval match
            case Result.Success(a) => a
            case Result.Failure(e) => fail(s"Decode failed: $e")
            case Result.Panic(t)   => throw t

    /** Build a synthetic HandshakeV10 packet body.
      *
      * Layout:
      *   uint8(10) | NUL-str(version) | LE uint32(threadId)
      *   | bytes[8](part1) | 0x00
      *   | LE uint16(capLow) | uint8(charset) | LE uint16(statusFlags) | LE uint16(capHigh)
      *   | uint8(authDataLen) | filler[10]
      *   | bytes[max(13, authDataLen-8)](part2), last byte is NUL
      *   | NUL-str(authPluginName)
      */
    private def buildHandshakeV10(
        version: String,
        threadId: Long,
        part1: Array[Byte],     // exactly 8 bytes
        part2Body: Array[Byte], // exactly 12 bytes (without trailing NUL)
        capLow: Int,
        charset: Int,
        statusFlags: Int,
        capHigh: Int,
        authPluginName: String
    ): Array[Byte] =
        val writer = new MysqlBufferWriter
        writer.writeUInt8(10) // protocol version
        writer.writeNulTerminatedString(version)
        writer.writeUInt32LE(threadId)
        writer.writeBytes(part1) // 8 bytes
        writer.writeByte(0)      // filler
        writer.writeUInt16LE(capLow)
        writer.writeUInt8(charset)
        writer.writeUInt16LE(statusFlags)
        writer.writeUInt16LE(capHigh)
        // authPluginDataLen = 8 + part2Body.length + 1 (for the NUL)
        val authDataLen = 8 + part2Body.length + 1
        writer.writeUInt8(authDataLen)
        writer.writeBytes(new Array[Byte](10)) // filler
        // part2: 13 bytes minimum (12 body + 1 NUL)
        writer.writeBytes(part2Body)
        writer.writeByte(0) // trailing NUL of part2
        writer.writeNulTerminatedString(authPluginName)
        writer.toSpan.toArray
    end buildHandshakeV10

    val authPart1: Array[Byte]     = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
    val authPart2Body: Array[Byte] = Array[Byte](9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
    val capLow                     = 0xffff
    val capHigh                    = 0x0001
    val testPacket: Array[Byte] = buildHandshakeV10(
        version = "8.0.34",
        threadId = 42L,
        part1 = authPart1,
        part2Body = authPart2Body,
        capLow = capLow,
        charset = 33,
        statusFlags = 2,
        capHigh = capHigh,
        authPluginName = "caching_sha2_password"
    )

    "HandshakeV10Unmarshaller decodes protocol version 10" in {
        val reader  = MysqlBufferReader(testPacket)
        val decoded = decode(HandshakeV10Unmarshaller.read(reader))
        assert(decoded.protocolVersion == 10)
    }

    "HandshakeV10Unmarshaller decodes server version string correctly" in {
        val reader  = MysqlBufferReader(testPacket)
        val decoded = decode(HandshakeV10Unmarshaller.read(reader))
        assert(decoded.serverVersion == "8.0.34")
    }

    "HandshakeV10Unmarshaller concatenates auth-plugin-data parts into 20-byte salt" in {
        val reader  = MysqlBufferReader(testPacket)
        val decoded = decode(HandshakeV10Unmarshaller.read(reader))
        assert(decoded.authPluginData.size == 20)
        // First 8 bytes should match part1
        val arr = decoded.authPluginData.toArray
        assert(arr(0) == 1.toByte)
        assert(arr(7) == 8.toByte)
        // Bytes 8-19 should match part2Body
        assert(arr(8) == 9.toByte)
        assert(arr(19) == 20.toByte)
    }

    "HandshakeV10Unmarshaller decodes combined capability flags from lower and upper halves" in {
        val reader   = MysqlBufferReader(testPacket)
        val decoded  = decode(HandshakeV10Unmarshaller.read(reader))
        val expected = capLow.toLong | (capHigh.toLong << 16)
        assert(decoded.capabilityFlags == expected)
    }

    "HandshakeV10Unmarshaller decodes auth plugin name 'caching_sha2_password'" in {
        val reader  = MysqlBufferReader(testPacket)
        val decoded = decode(HandshakeV10Unmarshaller.read(reader))
        assert(decoded.authPluginName == "caching_sha2_password")
    }

    "HandshakeResponse41Marshaller sets CLIENT_PROTOCOL_41 in capability flags" in {
        val caps = Capabilities.Default
        val msg = HandshakeResponse41(
            capabilities = caps,
            maxPacket = 16777216L,
            charset = 255,
            username = "root",
            authResponse = Span.from(Array.fill(20)(0x00.toByte)),
            database = Maybe.Absent,
            authPlugin = Maybe.Absent
        )
        val buf = new MysqlBufferWriter
        HandshakeResponse41Marshaller.write(msg, buf)
        val span = buf.toSpan
        // Read back the capability flags (first 4 bytes, LE uint32)
        val reader = MysqlBufferReader(span)
        Abort.run[SqlException.Decode](reader.readUInt32LE()).map {
            case Result.Success(decodedCaps) =>
                assert((decodedCaps & Capabilities.CLIENT_PROTOCOL_41) != 0)
            case other => fail(s"readUInt32LE failed: $other")
        }
    }

    "HandshakeResponse41Marshaller encodes username NUL-terminated" in {
        val msg = HandshakeResponse41(
            capabilities = Capabilities.Default,
            maxPacket = 16777216L,
            charset = 255,
            username = "alice",
            authResponse = Span.from(Array[Byte](1, 2, 3)),
            database = Maybe.Absent,
            authPlugin = Maybe.Absent
        )
        val buf = new MysqlBufferWriter
        HandshakeResponse41Marshaller.write(msg, buf)
        val arr = buf.toSpan.toArray
        // Skip: capabilities(4) + maxPacket(4) + charset(1) + filler(23) = 32 bytes
        val offset = 32
        assert(arr(offset) == 'a'.toByte)
        assert(arr(offset + 1) == 'l'.toByte)
        assert(arr(offset + 2) == 'i'.toByte)
        assert(arr(offset + 3) == 'c'.toByte)
        assert(arr(offset + 4) == 'e'.toByte)
        assert(arr(offset + 5) == 0.toByte) // NUL terminator
    }

    "SslRequestMarshaller produces 32-byte payload" in {
        val msg = SslRequest(
            capabilities = Capabilities.Default | Capabilities.CLIENT_SSL,
            maxPacket = 16777216L,
            charset = 255
        )
        val buf = new MysqlBufferWriter
        SslRequestMarshaller.write(msg, buf)
        assert(buf.toSpan.size == 32)
    }

    "HandshakeV10Unmarshaller decodes thread ID correctly" in {
        val reader  = MysqlBufferReader(testPacket)
        val decoded = decode(HandshakeV10Unmarshaller.read(reader))
        assert(decoded.threadId == 42L)
    }

    "HandshakeV10Unmarshaller fails on non-10 protocol version" in {
        val bad    = Array[Byte](9.toByte) ++ testPacket.tail
        val result = Abort.run[SqlException.Decode](HandshakeV10Unmarshaller.read(MysqlBufferReader(bad))).eval
        assert(result.isFailure)
    }

end HandshakeMessagesTest
