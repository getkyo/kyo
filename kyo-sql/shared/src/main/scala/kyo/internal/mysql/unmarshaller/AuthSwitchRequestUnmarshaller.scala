package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.AuthSwitchRequest
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.Unmarshaller

/** Unmarshaller for [[AuthSwitchRequest]].
  *
  * Wire: 0xFE | NUL-string(pluginName) | bytes<EOF>(pluginData)
  *
  * The reader is positioned AFTER the first byte (0xFE). This message is only produced when [[GenericResponseUnmarshaller]] determines that
  * the 0xFE packet is in an auth context (not an EOF packet).
  *
  * Disambiguation from EOF: the caller checks payload length >= 2 AND the auth context is active.
  *
  * The auth data typically ends with a NUL byte per the MySQL protocol; the trailing NUL is included in pluginData.
  *
  * Reference: MySQL Internals, Protocol::AuthSwitchRequest
  */
object AuthSwitchRequestUnmarshaller extends Unmarshaller[AuthSwitchRequest]:

    def read(buf: MysqlBufferReader)(using Frame): AuthSwitchRequest < Abort[SqlException.Decode] =
        val pluginName = buf.readNulTerminatedString()
        val pluginData = buf.readRestOfPacket()
        AuthSwitchRequest(pluginName, pluginData)
    end read

end AuthSwitchRequestUnmarshaller
