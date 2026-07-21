package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.SSLRequest

/** Marshaller for [[SSLRequest]].
  *
  * Wire format (no type byte): Int32(8) | Int32(80877103)
  *
  * Total: exactly 8 bytes. Magic code 80877103 = 0x04D2162F.
  *
  * Reference: PostgreSQL §55.7 "SSLRequest"
  */
object SSLRequestMarshaller extends Marshaller[SSLRequest.type]:
    def write(msg: SSLRequest.type, buf: PostgresBufferWriter): Unit =
        buf.writeInt32(8)
        buf.writeInt32(80877103)
end SSLRequestMarshaller
