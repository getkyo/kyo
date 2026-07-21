package kyo.internal.mysql.marshaller

import kyo.internal.mysql.ComStmtReset
import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter

/** Marshaller for [[ComStmtReset]].
  *
  * Wire: 0x1A | LE uint32(stmtId)
  *
  * The server responds with an OK or ERR packet.
  *
  * Reference: MySQL Internals — COM_STMT_RESET
  */
object ComStmtResetMarshaller extends Marshaller[ComStmtReset]:

    private val CommandByte: Byte = 0x1a.toByte

    def write(msg: ComStmtReset, buf: MysqlBufferWriter): Unit =
        buf.writeByte(CommandByte)
        buf.writeUInt32LE(msg.stmtId.toLong)
    end write

end ComStmtResetMarshaller
