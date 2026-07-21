package kyo.internal.mysql.marshaller

import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter
import kyo.internal.mysql.SslRequest

/** Marshaller for [[SslRequest]].
  *
  * Wire: LE uint32(capabilities) | LE uint32(maxPacketSize) | uint8(charset) | filler[23 zero bytes]
  *
  * This is a short form of HandshakeResponse41 sent before TLS is established. After the server accepts this, the client wraps the socket
  * in TLS and then sends a full HandshakeResponse41.
  *
  * Reference: MySQL Internals — Protocol::SSLRequest
  */
object SslRequestMarshaller extends Marshaller[SslRequest]:

    private val Filler = new Array[Byte](23)

    def write(msg: SslRequest, buf: MysqlBufferWriter): Unit =
        buf.writeUInt32LE(msg.capabilities)
        buf.writeUInt32LE(msg.maxPacket)
        buf.writeUInt8(msg.charset)
        buf.writeBytes(Filler)
    end write

end SslRequestMarshaller
