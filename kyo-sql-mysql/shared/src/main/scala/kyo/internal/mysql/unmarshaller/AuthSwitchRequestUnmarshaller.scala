package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlDecodeException
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
  * The server terminates the nonce with a NUL, as it does the HandshakeV10 nonce, and the trailing NUL is trimmed the same way
  * [[HandshakeV10Unmarshaller]] trims it: every plugin hashes the 20 nonce bytes, so a scramble that kept the terminator authenticates
  * nothing. The server never generates a NUL inside a nonce, so only the terminator is removed.
  *
  * Reference: MySQL Internals, Protocol::AuthSwitchRequest
  */
object AuthSwitchRequestUnmarshaller extends Unmarshaller[AuthSwitchRequest]:

    def read(buf: MysqlBufferReader)(using Frame): AuthSwitchRequest < Abort[SqlDecodeException] =
        val pluginName = buf.readNulTerminatedString()
        val data       = buf.readRestOfPacket()
        val pluginData =
            if data.size > 0 && data(data.size - 1) == 0.toByte then data.slice(0, data.size - 1)
            else data
        AuthSwitchRequest(pluginName, pluginData)
    end read

end AuthSwitchRequestUnmarshaller
