package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Execute
import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter

/** Marshaller for [[Execute]].
  *
  * Wire format: 'E' | Int32(length) | cstring(portal) | Int32(maxRows)
  *
  * maxRows = 0 means return all rows. Length includes the 4-byte length field itself.
  *
  * Reference: PostgreSQL §55.7 "Execute"
  */
object ExecuteMarshaller extends Marshaller[Execute]:
    def write(msg: Execute, buf: PostgresBufferWriter): Unit =
        buf.writeByte('E'.toByte)
        val lengthOffset = buf.size
        buf.writeInt32(0) // placeholder
        buf.writeString(msg.portalName)
        buf.writeInt32(msg.maxRows)
        buf.patchInt32(lengthOffset, buf.size - lengthOffset)
    end write
end ExecuteMarshaller
