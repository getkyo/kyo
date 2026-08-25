package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Close
import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter

/** Marshaller for [[Close]].
  *
  * Wire format: 'C' | Int32(length) | Byte1(target) | cstring(name)
  *
  * target: 'S' for a prepared statement, 'P' for a portal. Length includes the 4-byte length field itself.
  *
  * Reference: PostgreSQL §55.7 "Close"
  */
object CloseMarshaller extends Marshaller[Close]:
    def write(msg: Close, buf: PostgresBufferWriter): Unit =
        buf.framed('C'.toByte) {
            buf.writeByte(msg.target)
            buf.writeString(msg.name)
        }
    end write
end CloseMarshaller
