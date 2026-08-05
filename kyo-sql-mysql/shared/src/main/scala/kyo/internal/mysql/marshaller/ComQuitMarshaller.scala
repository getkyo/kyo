package kyo.internal.mysql.marshaller

import kyo.internal.mysql.ComQuit
import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter

/** Marshaller for [[ComQuit]].
  *
  * Wire: 0x01 (single byte)
  *
  * No server response is expected; the server closes the TCP connection.
  *
  * Reference: MySQL Internals, COM_QUIT
  */
object ComQuitMarshaller extends Marshaller[ComQuit.type]:

    private val CommandByte: Byte = 0x01.toByte

    def write(msg: ComQuit.type, buf: MysqlBufferWriter): Unit =
        buf.writeByte(CommandByte)
    end write

end ComQuitMarshaller
