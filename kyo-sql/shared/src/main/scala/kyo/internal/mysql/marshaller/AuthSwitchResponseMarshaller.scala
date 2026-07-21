package kyo.internal.mysql.marshaller

import kyo.internal.mysql.AuthSwitchResponse
import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter

/** Marshaller for [[AuthSwitchResponse]].
  *
  * Wire: raw bytes (the auth plugin response; no framing beyond the MySQL packet header)
  *
  * Sent by the client when the server requests an auth plugin switch via [[kyo.internal.mysql.AuthSwitchRequest]].
  *
  * Reference: MySQL Internals, Protocol::AuthSwitchResponse
  */
object AuthSwitchResponseMarshaller extends Marshaller[AuthSwitchResponse]:

    def write(msg: AuthSwitchResponse, buf: MysqlBufferWriter): Unit =
        buf.writeBytes(msg.data)
    end write

end AuthSwitchResponseMarshaller
