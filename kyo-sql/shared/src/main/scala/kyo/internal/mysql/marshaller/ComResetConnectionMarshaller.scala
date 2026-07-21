package kyo.internal.mysql.marshaller

import kyo.internal.mysql.ComResetConnection
import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter

/** Marshaller for [[ComResetConnection]].
  *
  * Wire: 0x1F (single byte)
  *
  * The server responds with an OK packet. Session state is reset without re-handshaking.
  *
  * Reference: MySQL Internals — COM_RESET_CONNECTION
  */
object ComResetConnectionMarshaller extends Marshaller[ComResetConnection.type]:

    private val CommandByte: Byte = 0x1f.toByte

    def write(msg: ComResetConnection.type, buf: MysqlBufferWriter): Unit =
        buf.writeByte(CommandByte)
    end write

end ComResetConnectionMarshaller
