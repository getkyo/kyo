package kyo.internal.postgres.marshaller

import kyo.internal.postgres.CopyFail
import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter

/** Marshaller for [[CopyFail]] (client → server direction).
  *
  * Wire format: 'f' | Int32(len) | cstring(errorMessage)
  *
  * Length includes the 4-byte length field itself and the NUL terminator.
  *
  * Reference: PostgreSQL §55.7 "CopyFail"
  */
object CopyFailMarshaller extends Marshaller[CopyFail]:
    def write(msg: CopyFail, buf: PostgresBufferWriter): Unit =
        buf.writeByte('f'.toByte)
        val lengthOffset = buf.size
        buf.writeInt32(0)                 // placeholder
        buf.writeString(msg.errorMessage) // NUL-terminated
        buf.patchInt32(lengthOffset, buf.size - lengthOffset)
    end write
end CopyFailMarshaller
