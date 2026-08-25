package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.SASLInitialResponse

/** Marshaller for [[SASLInitialResponse]].
  *
  * Wire format: 'p' | Int32(length) | cstring(mechanism) | Int32(clientFirstMessageLen) | bytes
  *
  * Length includes the 4-byte length field itself.
  *
  * Reference: PostgreSQL §55.7 "SASLInitialResponse"
  */
object SASLInitialResponseMarshaller extends Marshaller[SASLInitialResponse]:
    def write(msg: SASLInitialResponse, buf: PostgresBufferWriter): Unit =
        buf.framed('p'.toByte) {
            buf.writeString(msg.mechanism)
            val cfm = msg.clientFirstMessage.toArray
            buf.writeInt32(cfm.length)
            buf.writeBytes(cfm)
        }
    end write
end SASLInitialResponseMarshaller
