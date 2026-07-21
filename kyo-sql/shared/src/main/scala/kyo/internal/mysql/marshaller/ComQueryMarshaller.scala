package kyo.internal.mysql.marshaller

import java.nio.charset.StandardCharsets
import kyo.internal.mysql.ComQuery
import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter

/** Marshaller for [[ComQuery]].
  *
  * Wire: 0x03 | UTF-8 SQL bytes (no NUL terminator)
  *
  * The 0x03 byte is the COM_QUERY command code. The SQL follows immediately with no length prefix, the packet length header supplies the
  * overall length.
  *
  * Reference: MySQL Internals, COM_QUERY
  */
object ComQueryMarshaller extends Marshaller[ComQuery]:

    private val CommandByte: Byte = 0x03.toByte

    def write(msg: ComQuery, buf: MysqlBufferWriter): Unit =
        buf.writeByte(CommandByte)
        buf.writeFixedString(msg.sql)
    end write

end ComQueryMarshaller
