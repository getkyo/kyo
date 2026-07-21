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
        buf.writeByte('p'.toByte)
        val lengthOffset = buf.size
        buf.writeInt32(0) // placeholder
        buf.writeString(msg.mechanism)
        val cfm = msg.clientFirstMessage.toArray
        buf.writeInt32(cfm.length)
        buf.writeBytes(cfm)
        buf.patchInt32(lengthOffset, buf.size - lengthOffset)
    end write
end SASLInitialResponseMarshaller
