package kyo.internal.mysql.marshaller

import kyo.internal.mysql.AuthMoreDataResponse
import kyo.internal.mysql.Marshaller
import kyo.internal.mysql.MysqlBufferWriter

/** Marshaller for [[AuthMoreDataResponse]] (client-side auth continuation).
  *
  * Wire: raw bytes (the continuation payload; typically RSA-OAEP encrypted password or plaintext over TLS)
  *
  * Sent during caching_sha2_password full-auth when the server issues AuthMoreData(0x04).
  *
  * Reference: MySQL Internals, caching_sha2_password Authentication
  */
object AuthMoreDataMarshaller extends Marshaller[AuthMoreDataResponse]:

    def write(msg: AuthMoreDataResponse, buf: MysqlBufferWriter): Unit =
        buf.writeBytes(msg.data)
    end write

end AuthMoreDataMarshaller
