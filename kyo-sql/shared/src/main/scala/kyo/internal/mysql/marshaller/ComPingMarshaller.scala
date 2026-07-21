package kyo.internal.mysql.marshaller

import kyo.internal.mysql.ComPing
import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter

/** Marshaller for [[ComPing]].
  *
  * Wire: 0x0E (single byte)
  *
  * The server responds with an OK packet. Used as a connection keep-alive.
  *
  * Reference: MySQL Internals — COM_PING
  */
object ComPingMarshaller extends Marshaller[ComPing.type]:

    private val CommandByte: Byte = 0x0e.toByte

    def write(msg: ComPing.type, buf: MysqlBufferWriter): Unit =
        buf.writeByte(CommandByte)
    end write

end ComPingMarshaller
