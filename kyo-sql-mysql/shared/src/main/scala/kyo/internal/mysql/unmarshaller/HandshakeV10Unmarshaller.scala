package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.SqlDecodeProtocolFormatException
import kyo.internal.mysql.HandshakeV10
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.Unmarshaller

/** Unmarshaller for [[HandshakeV10]], the initial server greeting.
  *
  * Wire: uint8(protocolVersion) | NUL-string(serverVersion) | LE uint32(threadId)
  *         | bytes[8](authPluginDataPart1) | 0x00(filler)
  *         | LE uint16(capFlagsLow) | uint8(charset) | LE uint16(statusFlags) | LE uint16(capFlagsHigh)
  *         | uint8(authPluginDataLen) | filler[10] | max(13, authPluginDataLen-8)(authPluginDataPart2)
  *         | NUL-string(authPluginName)?
  *
  * The full capability flags: capFlagsLow | (capFlagsHigh << 16)
  * The full auth data: part1[8] ++ part2[max(13, authPluginDataLen-8) trimmed to authPluginDataLen-8 minus NUL]
  *
  * Standard combined auth data for caching_sha2_password is 20 bytes: 8 from part1 + 12 from part2.
  *
  * Reference: MySQL Internals, Protocol::Handshake
  */
object HandshakeV10Unmarshaller extends Unmarshaller[HandshakeV10]:

    def read(buf: MysqlBufferReader)(using Frame): HandshakeV10 < Abort[SqlDecodeException] =
        buf.readUInt8().flatMap { protocolVersion =>
            if protocolVersion != 10 then
                Abort.fail(SqlDecodeProtocolFormatException(protocolVersion.toByte, buf.position))
            else
                val serverVersion = buf.readNulTerminatedString()
                buf.readUInt32LE().flatMap { threadId =>
                    // auth-plugin-data part 1: exactly 8 bytes
                    buf.readBytes(8).flatMap { part1 =>
                        // filler: 1 byte (0x00)
                        buf.readByte().flatMap { _ =>
                            buf.readUInt16LE().flatMap { capFlagsLow =>
                                buf.readUInt8().flatMap { charset =>
                                    buf.readUInt16LE().flatMap { statusFlags =>
                                        buf.readUInt16LE().flatMap { capFlagsHigh =>
                                            buf.readUInt8().flatMap { authDataLen =>
                                                // filler: 10 reserved bytes (discard)
                                                buf.readBytes(10).flatMap { _ =>
                                                    // auth-plugin-data part 2: max(13, authDataLen-8) bytes; last byte is always NUL
                                                    val part2Len = math.max(13, authDataLen - 8)
                                                    buf.readBytes(part2Len).map { part2WithNul =>
                                                        // Trim trailing NUL from part2 (last byte is 0x00)
                                                        val part2Trimmed =
                                                            if part2Len > 0 && part2WithNul(part2Len - 1) == 0.toByte
                                                            then part2WithNul.slice(0, part2Len - 1)
                                                            else part2WithNul
                                                        // Concatenate part1 + part2
                                                        val combined = new Array[Byte](part1.size + part2Trimmed.size)
                                                        val arr1     = part1.toArray
                                                        val arr2     = part2Trimmed.toArray
                                                        java.lang.System.arraycopy(arr1, 0, combined, 0, arr1.length)
                                                        java.lang.System.arraycopy(arr2, 0, combined, arr1.length, arr2.length)
                                                        val authPluginData = Span.from(combined)
                                                        // auth-plugin-name: NUL-terminated string (present if CLIENT_PLUGIN_AUTH)
                                                        val capabilityFlags = capFlagsLow.toLong | (capFlagsHigh.toLong << 16)
                                                        val authPluginName = if buf.remaining > 0 then buf.readNulTerminatedString() else ""
                                                        HandshakeV10(
                                                            protocolVersion,
                                                            serverVersion,
                                                            threadId,
                                                            authPluginData,
                                                            capabilityFlags,
                                                            charset,
                                                            statusFlags,
                                                            authPluginName
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        }
    end read

end HandshakeV10Unmarshaller
