package kyo.internal.mysql.marshaller

import kyo.internal.mysql.ComStmtClose
import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter

/** Marshaller for [[ComStmtClose]].
  *
  * Wire: 0x19 | LE uint32(stmtId)
  *
  * No server response is expected; the server silently frees the statement.
  *
  * Reference: MySQL Internals, COM_STMT_CLOSE
  */
object ComStmtCloseMarshaller extends Marshaller[ComStmtClose]:

    private val CommandByte: Byte = 0x19.toByte

    def write(msg: ComStmtClose, buf: MysqlBufferWriter): Unit =
        buf.writeByte(CommandByte)
        buf.writeUInt32LE(msg.stmtId.toLong)
    end write

end ComStmtCloseMarshaller
