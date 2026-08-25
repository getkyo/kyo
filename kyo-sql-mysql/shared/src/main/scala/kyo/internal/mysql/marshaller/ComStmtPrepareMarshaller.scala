package kyo.internal.mysql.marshaller

import kyo.internal.mysql.ComStmtPrepare
import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter

/** Marshaller for [[ComStmtPrepare]].
  *
  * Wire: 0x16 | UTF-8 SQL bytes (no NUL terminator)
  *
  * The 0x16 byte is the COM_STMT_PREPARE command code. The SQL follows immediately, the packet length header supplies the overall length.
  *
  * Reference: MySQL Internals, COM_STMT_PREPARE
  */
object ComStmtPrepareMarshaller extends Marshaller[ComStmtPrepare]:

    private val CommandByte: Byte = 0x16.toByte

    def write(msg: ComStmtPrepare, buf: MysqlBufferWriter): Unit =
        buf.writeByte(CommandByte)
        buf.writeFixedString(msg.sql)
    end write

end ComStmtPrepareMarshaller
