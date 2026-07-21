package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.StartupMessage

/** Marshaller for [[StartupMessage]].
  *
  * Wire format (no type byte, special framing): Int32(length) | Int32(196608) | cstring(key) cstring(value) ... | 0x00
  *
  * Length includes the 4-byte length field itself. Protocol version 196608 = 3.0 (0x00030000).
  *
  * Reference: PostgreSQL §55.7 "StartupMessage"
  */
object StartupMessageMarshaller extends Marshaller[StartupMessage]:

    private val protocolVersion = 196608 // 0x00030000 = v3.0

    def write(msg: StartupMessage, buf: PostgresBufferWriter): Unit =
        // Reserve space for the Int32 length; we'll patch it after writing the body.
        val lengthOffset = buf.size
        buf.writeInt32(0) // placeholder

        buf.writeInt32(protocolVersion)

        msg.parameters.foreach { case (k, v) =>
            buf.writeString(k)
            buf.writeString(v)
        }

        // Trailing NUL terminator for the parameter list
        buf.writeByte(0.toByte)

        // Back-patch the length (includes the 4-byte length field itself)
        buf.patchInt32(lengthOffset, buf.size - lengthOffset)
    end write

end StartupMessageMarshaller
