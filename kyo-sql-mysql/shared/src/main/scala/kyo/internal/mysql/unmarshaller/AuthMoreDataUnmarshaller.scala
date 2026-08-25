package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.mysql.AuthMoreData
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.Unmarshaller

/** Unmarshaller for [[AuthMoreData]] (server side).
  *
  * Wire: 0x01 | bytes<EOF>(data)
  *
  * The reader is positioned AFTER the first byte (0x01), which the [[GenericResponseUnmarshaller]] has already consumed for dispatch.
  *
  * The data byte has sub-values for caching_sha2_password:
  *   - 0x03 = fast-path success (cached credentials accepted; no further auth needed)
  *   - 0x04 = full auth required (client must respond with encrypted or plaintext password)
  *   - other = raw RSA public key bytes or other plugin-specific data
  *
  * Reference: MySQL Internals, caching_sha2_password Authentication
  */
object AuthMoreDataUnmarshaller extends Unmarshaller[AuthMoreData]:

    def read(buf: MysqlBufferReader)(using Frame): AuthMoreData < Abort[SqlDecodeException] =
        val data = buf.readRestOfPacket()
        AuthMoreData(data)
    end read

end AuthMoreDataUnmarshaller
