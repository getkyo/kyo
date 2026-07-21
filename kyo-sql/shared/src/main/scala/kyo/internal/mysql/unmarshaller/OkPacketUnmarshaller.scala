package kyo.internal.mysql.unmarshaller

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.SqlException
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.OkPacket
import kyo.internal.mysql.Unmarshaller

/** Unmarshaller for [[OkPacket]].
  *
  * Wire: 0x00 (or 0xFE if CLIENT_DEPRECATE_EOF and payload length >= 7)
  *   | lenenc-int(affectedRows) | lenenc-int(lastInsertId) | LE uint16(statusFlags) | LE uint16(warnings)
  *   | string<EOF>(info)?       [when CLIENT_SESSION_TRACK is NOT set or SERVER_SESSION_STATE_CHANGED is not set]
  *   | lenenc-string(info)?     [when CLIENT_SESSION_TRACK and SERVER_SESSION_STATE_CHANGED are set]
  *   | lenenc-string(sessionStateInfo)?  [when SERVER_SESSION_STATE_CHANGED is set]
  *
  * The reader is positioned AFTER the first byte (0x00 or 0xFE), which the [[GenericResponseUnmarshaller]] has already consumed for
  * dispatch.
  *
  * Reference: MySQL Internals — Protocol::OK_Packet
  *
  * Note: SERVER_SESSION_STATE_CHANGED = 0x4000 in statusFlags.
  */
object OkPacketUnmarshaller extends Unmarshaller[OkPacket]:

    private val ServerSessionStateChanged = 0x4000

    def read(buf: MysqlBufferReader)(using Frame): OkPacket < Abort[SqlException.Decode] =
        buf.readLenencInt().flatMap {
            case Maybe.Absent =>
                Abort.fail(SqlException.Decode("Unexpected 0xFF sentinel in OK packet affectedRows field", Maybe.Absent, summon[Frame]))
            case Maybe.Present(affectedRows) =>
                buf.readLenencInt().flatMap {
                    case Maybe.Absent =>
                        Abort.fail(SqlException.Decode(
                            "Unexpected 0xFF sentinel in OK packet lastInsertId field",
                            Maybe.Absent,
                            summon[Frame]
                        ))
                    case Maybe.Present(lastInsertId) =>
                        buf.readUInt16LE().flatMap { statusFlagsInt =>
                            buf.readUInt16LE().flatMap { warningsInt =>
                                val statusFlags = statusFlagsInt.toShort
                                val warnings    = warningsInt.toShort
                                // Optional info / session state info
                                if buf.remaining == 0 then
                                    OkPacket(affectedRows, lastInsertId, statusFlags, warnings, Maybe.Absent, Maybe.Absent)
                                else if (statusFlagsInt & ServerSessionStateChanged) != 0 then
                                    // CLIENT_SESSION_TRACK + SERVER_SESSION_STATE_CHANGED: lenenc-string(info) + lenenc-string(sessionStateInfo)
                                    val infoEffect: Maybe[String] < Abort[SqlException.Decode] =
                                        if buf.remaining > 0 then buf.readLenencString().map(Maybe.Present(_))
                                        else Maybe.Absent
                                    infoEffect.flatMap { info =>
                                        val sessionStateInfoEffect: Maybe[String] < Abort[SqlException.Decode] =
                                            if buf.remaining > 0 then buf.readLenencString().map(Maybe.Present(_))
                                            else Maybe.Absent
                                        sessionStateInfoEffect.map { sessionStateInfo =>
                                            OkPacket(affectedRows, lastInsertId, statusFlags, warnings, info, sessionStateInfo)
                                        }
                                    }
                                else
                                    // Plain info: rest of packet as UTF-8
                                    val infoBytes = buf.readRestOfPacket()
                                    val info      = Maybe.Present(new String(infoBytes.toArray, StandardCharsets.UTF_8))
                                    OkPacket(affectedRows, lastInsertId, statusFlags, warnings, info, Maybe.Absent)
                                end if
                            }
                        }
                }
        }
    end read

end OkPacketUnmarshaller
