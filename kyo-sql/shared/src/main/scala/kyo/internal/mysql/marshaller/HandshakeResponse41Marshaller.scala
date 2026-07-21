package kyo.internal.mysql.marshaller

import kyo.Maybe
import kyo.internal.mysql.Capabilities
import kyo.internal.mysql.HandshakeResponse41
import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter

/** Marshaller for [[HandshakeResponse41]].
  *
  * Wire (protocol 4.1):
  *   LE uint32(capabilities) | LE uint32(maxPacketSize) | uint8(charset) | filler[23 zero bytes]
  *   | NUL-string(username)
  *   | lenenc-int(authResponseLen) + bytes(authResponse)  [when CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA is set]
  *     OR uint8(authResponseLen) + bytes(authResponse)    [otherwise]
  *   | NUL-string(database)   [when CLIENT_CONNECT_WITH_DB is set]
  *   | NUL-string(authPlugin) [when CLIENT_PLUGIN_AUTH is set]
  *
  * kyo-sql always sets CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA so we use the lenenc form.
  *
  * Reference: MySQL Internals — Protocol::HandshakeResponse41
  */
object HandshakeResponse41Marshaller extends Marshaller[HandshakeResponse41]:

    private val Filler = new Array[Byte](23)

    def write(msg: HandshakeResponse41, buf: MysqlBufferWriter): Unit =
        // Capability flags (4 bytes LE — only lower 32 bits used in older MySQL, MySQL 8+ extends to 4 bytes)
        buf.writeUInt32LE(msg.capabilities)
        // Max packet size (4 bytes LE)
        buf.writeUInt32LE(msg.maxPacket)
        // Charset (1 byte)
        buf.writeUInt8(msg.charset)
        // Filler: 23 zero bytes
        buf.writeBytes(Filler)
        // Username (NUL-terminated)
        buf.writeNulTerminatedString(msg.username)
        // Auth response (lenenc-int length + raw bytes)
        buf.writeLenencInt(msg.authResponse.size.toLong)
        buf.writeBytes(msg.authResponse)
        // Optional database name
        msg.database match
            case Maybe.Present(db) => buf.writeNulTerminatedString(db)
            case Maybe.Absent      => ()
        // Optional auth plugin name
        msg.authPlugin match
            case Maybe.Present(plugin) => buf.writeNulTerminatedString(plugin)
            case Maybe.Absent          => ()
    end write

end HandshakeResponse41Marshaller
