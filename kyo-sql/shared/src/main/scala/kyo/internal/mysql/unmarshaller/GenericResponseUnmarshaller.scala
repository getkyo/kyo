package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.AuthMoreData
import kyo.internal.mysql.AuthSwitchRequest
import kyo.internal.mysql.BackendMessage
import kyo.internal.mysql.EofPacket
import kyo.internal.mysql.ErrPacket
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.OkPacket
import kyo.internal.mysql.StmtPrepareOk
import kyo.internal.mysql.Unmarshaller

/** Dispatcher that reads the first byte of a MySQL packet payload and routes to the appropriate unmarshaller.
  *
  * Disambiguation rules (MySQL protocol 4.1 + CLIENT_DEPRECATE_EOF):
  *
  *   - 0x00 → [[OkPacket]] (standard OK) or [[StmtPrepareOk]] when `isStmtPrepareContext`
  *   - 0xFF → [[ErrPacket]]
  *   - 0xFE + `inAuthContext` → [[AuthSwitchRequest]] (plugin switch during auth)
  *   - 0xFE + len >= 7 → [[OkPacket]] (0xFE-encoded OK; sent when CLIENT_DEPRECATE_EOF is negotiated)
  *   - 0xFE + len < 9 → [[EofPacket]] (old-style EOF; only when CLIENT_DEPRECATE_EOF is NOT negotiated)
  *   - 0x01 → [[AuthMoreData]] (server-side caching_sha2_password continuation)
  *
  * kyo-sql always negotiates CLIENT_DEPRECATE_EOF, so in practice 0xFE with len >= 7 is always an OK packet and EOF packets (0xFE, len<9)
  * are never sent by the server. The `inAuthContext` flag MUST be checked before the length test, since AuthSwitchRequest is 0xFE and can
  * be very short.
  *
  * The `totalPayloadLength` parameter must be the total length of the packet payload (including the first 0xFE byte).
  *
  * Reference: MySQL Internals — Generic Response Packets
  */
object GenericResponseUnmarshaller:

    /** Reads a generic server response and dispatches to the correct unmarshaller.
      *
      * @param buf
      *   reader positioned at the START of the payload (first byte NOT yet consumed)
      * @param totalPayloadLength
      *   total length of the payload in bytes (used for 0xFE disambiguation)
      * @param inAuthContext
      *   true when called during the authentication phase (enables 0xFE → AuthSwitchRequest dispatch)
      * @param isStmtPrepareContext
      *   true when called after COM_STMT_PREPARE (enables 0x00 → StmtPrepareOk)
      */
    def read(
        buf: MysqlBufferReader,
        totalPayloadLength: Int,
        inAuthContext: Boolean,
        isStmtPrepareContext: Boolean
    )(using Frame): BackendMessage < Abort[SqlException.Decode] =
        buf.readByte().flatMap { rawByte =>
            val firstByte = rawByte & 0xff
            firstByte match
                case 0x00 =>
                    if isStmtPrepareContext then
                        StmtPrepareOkUnmarshaller.read(buf)
                    else
                        OkPacketUnmarshaller.read(buf)
                case 0xff =>
                    ErrPacketUnmarshaller.read(buf)
                case 0xfe =>
                    if inAuthContext then
                        // In auth context, 0xFE is always AuthSwitchRequest regardless of length
                        AuthSwitchRequestUnmarshaller.read(buf)
                    else if totalPayloadLength >= 7 then
                        // 0xFE-encoded OK packet (CLIENT_DEPRECATE_EOF negotiated, len>=7)
                        OkPacketUnmarshaller.read(buf)
                    else
                        // Old-style EOF packet (CLIENT_DEPRECATE_EOF NOT negotiated, len<9)
                        EofPacketUnmarshaller.read(buf)
                case 0x01 =>
                    AuthMoreDataUnmarshaller.read(buf)
                case other =>
                    Abort.fail(SqlException.Decode(
                        s"Unexpected first byte in generic response: 0x${other.toHexString} (payload length=$totalPayloadLength)",
                        Maybe.Absent,
                        summon[Frame]
                    ))
            end match
        }
    end read

end GenericResponseUnmarshaller
