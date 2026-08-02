package kyo.internal.postgres.marshaller

import kyo.internal.postgres.CancelRequest
import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter

/** Marshaller for [[CancelRequest]].
  *
  * Wire format (no type byte, sent on a fresh connection): Int32(16) | Int32(80877102) | Int32(processId) | Int32(secretKey)
  *
  * Total: exactly 16 bytes. Magic code 80877102 = 0x04D21630 − 1 (the cancel code).
  *
  * Reference: PostgreSQL §55.7 "CancelRequest"
  */
object CancelRequestMarshaller extends Marshaller[CancelRequest]:
    def write(msg: CancelRequest, buf: PostgresBufferWriter): Unit =
        buf.writeInt32(16)
        buf.writeInt32(80877102)
        buf.writeInt32(msg.processId)
        buf.writeInt32(msg.secretKey)
    end write
end CancelRequestMarshaller
