package kyo.internal.mysql.unmarshaller

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.SqlException
import kyo.internal.mysql.ErrPacket
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.Unmarshaller

/** Unmarshaller for [[ErrPacket]].
  *
  * Wire: 0xFF | LE uint16(errorCode) | '#' | bytes[5](sqlState) | string<EOF>(errorMessage)
  *
  * The reader is positioned AFTER the first byte (0xFF), which the [[GenericResponseUnmarshaller]] has already consumed for dispatch.
  *
  * The '#' marker and SQLSTATE are present in protocol 4.1+ (CLIENT_PROTOCOL_41), which kyo-sql always negotiates.
  *
  * Reference: MySQL Internals — Protocol::ERR_Packet
  */
object ErrPacketUnmarshaller extends Unmarshaller[ErrPacket]:

    def read(buf: MysqlBufferReader)(using Frame): ErrPacket < Abort[SqlException.Decode] =
        buf.readUInt16LE().flatMap { errorCode =>
            // '#' marker — consume and discard (0x23)
            buf.readByte().flatMap { marker =>
                if marker != '#'.toByte then
                    Abort.fail(SqlException.Decode(
                        s"Expected '#' (0x23) SQLSTATE marker in ERR packet, got 0x${(marker & 0xff).toHexString}",
                        Maybe.Absent,
                        summon[Frame]
                    ))
                else
                    val sqlState     = buf.readFixedString(5)
                    val msgBytes     = buf.readRestOfPacket()
                    val errorMessage = new String(msgBytes.toArray, StandardCharsets.UTF_8)
                    ErrPacket(errorCode, sqlState, errorMessage)
            }
        }
    end read

end ErrPacketUnmarshaller
