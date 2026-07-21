package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.EofPacket
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.Unmarshaller

/** Unmarshaller for [[EofPacket]].
  *
  * Wire: 0xFE | LE uint16(warnings) | LE uint16(statusFlags)
  *
  * Sent only when CLIENT_DEPRECATE_EOF is NOT negotiated. kyo-sql always negotiates CLIENT_DEPRECATE_EOF, so EOF packets are replaced by OK
  * packets everywhere. This unmarshaller exists for completeness.
  *
  * The reader is positioned AFTER the first byte (0xFE), which the [[GenericResponseUnmarshaller]] has already consumed for dispatch. The
  * caller must have already determined that the payload length is < 9 bytes (distinguishing from OK 0xFE).
  *
  * Reference: MySQL Internals — Protocol::EOF_Packet
  */
object EofPacketUnmarshaller extends Unmarshaller[EofPacket]:

    def read(buf: MysqlBufferReader)(using Frame): EofPacket < Abort[SqlException.Decode] =
        buf.readUInt16LE().flatMap { warningsInt =>
            buf.readUInt16LE().map { statusFlagsInt =>
                EofPacket(warningsInt.toShort, statusFlagsInt.toShort)
            }
        }
    end read

end EofPacketUnmarshaller
