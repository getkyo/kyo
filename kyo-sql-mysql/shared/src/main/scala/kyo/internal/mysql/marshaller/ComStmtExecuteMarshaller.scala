package kyo.internal.mysql.marshaller

import kyo.Maybe
import kyo.internal.mysql.ComStmtExecute
import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter

/** Marshaller for [[ComStmtExecute]].
  *
  * Wire:
  *   0x17 | LE uint32(stmtId) | uint8(flags) | LE uint32(iterationCount=1)
  *   | null-bitmap[ceil((numParams+7)/8) bytes]
  *   | uint8(newParamsBound)
  *   | [LE uint16(typeAndFlag)]* (only when newParamsBound=1)
  *   | param-values* (binary-encoded non-null param values, in column order)
  *
  * Each two-byte type descriptor packs: low byte = MySQL type code, high byte = unsigned flag (0=signed, 1=unsigned).
  * This matches the MySQL binary protocol: `int2store(pos, type | (unsigned_flag << 8))`.
  *
  * NULL bitmap layout: bit (N) of the null-bitmap (0-indexed) corresponds to parameter N.
  * Unlike the binary result-set row null-bitmap, there is NO 2-bit offset for parameters.
  *
  * Reference: MySQL Internals, COM_STMT_EXECUTE
  */
object ComStmtExecuteMarshaller extends Marshaller[ComStmtExecute]:

    private val CommandByte: Byte = 0x17.toByte

    def write(msg: ComStmtExecute, buf: MysqlBufferWriter): Unit =
        buf.writeByte(CommandByte)
        buf.writeUInt32LE(msg.stmtId.toLong)
        buf.writeUInt8(msg.flags)
        // iteration-count (always 1)
        buf.writeUInt32LE(1L)

        val numParams = msg.params.size
        if numParams > 0 then
            // Build and write null-bitmap (no offset for execute params)
            val bitmapLen = (numParams + 7) / 8
            val bitmap    = new Array[Byte](bitmapLen)
            var i         = 0
            msg.params.foreach { param =>
                param match
                    case Maybe.Absent =>
                        bitmap(i / 8) = (bitmap(i / 8) | (1 << (i % 8))).toByte
                    case Maybe.Present(_) => ()
                end match
                i += 1
            }
            buf.writeBytes(bitmap)

            // new-params-bound flag
            buf.writeUInt8(msg.newParamsBound)

            // If newParamsBound=1, write param type descriptors.
            // Each descriptor is exactly 2 bytes: low byte = type code, high byte = unsigned flag.
            // Matches MySQL binary protocol: int2store(pos, type | (unsigned_flag << 8)).
            if msg.newParamsBound == 1 then
                msg.paramTypes.foreach { case (mysqlType, isUnsigned) =>
                    buf.writeUInt16LE(mysqlType | (isUnsigned << 8))
                }
            end if

            // Write non-null param values in order
            msg.params.foreach {
                case Maybe.Present(value) => buf.writeBytes(value)
                case Maybe.Absent         => ()
            }
        else
            // No params: null-bitmap is empty, write newParamsBound=0
            buf.writeUInt8(msg.newParamsBound)
        end if
    end write

end ComStmtExecuteMarshaller
