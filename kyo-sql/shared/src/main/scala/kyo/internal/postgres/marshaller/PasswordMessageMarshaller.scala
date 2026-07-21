package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PasswordMessage
import kyo.internal.postgres.PostgresBufferWriter

/** Marshaller for [[PasswordMessage]].
  *
  * Wire format: 'p' | Int32(length) | cstring(password)
  *
  * Length includes the 4-byte length field itself. The password string is NUL-terminated.
  *
  * Reference: PostgreSQL §55.7 "PasswordMessage"
  */
object PasswordMessageMarshaller extends Marshaller[PasswordMessage]:
    def write(msg: PasswordMessage, buf: PostgresBufferWriter): Unit =
        buf.writeByte('p'.toByte)
        val lengthOffset = buf.size
        buf.writeInt32(0) // placeholder
        buf.writeString(msg.password)
        buf.patchInt32(lengthOffset, buf.size - lengthOffset)
    end write
end PasswordMessageMarshaller
