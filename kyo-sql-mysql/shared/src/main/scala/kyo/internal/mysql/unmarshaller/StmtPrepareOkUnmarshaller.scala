package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.StmtPrepareOk
import kyo.internal.mysql.Unmarshaller

/** Unmarshaller for [[StmtPrepareOk]].
  *
  * Wire: 0x00 | LE uint32(stmtId) | LE uint16(numColumns) | LE uint16(numParams) | 0x00(reserved) | LE uint16(warningCount)
  *
  * The first byte 0x00 has already been consumed by the packet dispatch layer before calling this unmarshaller.
  *
  * After this packet, the server sends:
  *   - `numParams` ColumnDefinition41 packets (if numParams > 0)
  *   - An EOF/OK packet terminator
  *   - `numColumns` ColumnDefinition41 packets (if numColumns > 0)
  *   - An EOF/OK packet terminator
  *
  * Reference: MySQL Internals, COM_STMT_PREPARE_OK
  */
object StmtPrepareOkUnmarshaller extends Unmarshaller[StmtPrepareOk]:

    def read(buf: MysqlBufferReader)(using Frame): StmtPrepareOk < Abort[SqlDecodeException] =
        buf.readUInt32LE().flatMap { stmtIdLong =>
            buf.readUInt16LE().flatMap { numColumnsInt =>
                buf.readUInt16LE().flatMap { numParamsInt =>
                    // reserved byte (0x00), discard
                    buf.readByte().flatMap { _ =>
                        buf.readUInt16LE().map { warningsInt =>
                            StmtPrepareOk(stmtIdLong.toInt, numColumnsInt.toShort, numParamsInt.toShort, warningsInt.toShort)
                        }
                    }
                }
            }
        }
    end read

end StmtPrepareOkUnmarshaller
