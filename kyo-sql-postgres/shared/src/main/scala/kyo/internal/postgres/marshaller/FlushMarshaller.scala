package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Flush
import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter

/** Marshaller for [[Flush]].
  *
  * Wire format: 'H' | Int32(4)
  *
  * No payload. Length is always 4 (the length field itself).
  *
  * Reference: PostgreSQL §55.7 "Flush"
  */
object FlushMarshaller extends Marshaller[Flush.type]:
    def write(msg: Flush.type, buf: PostgresBufferWriter): Unit =
        buf.writeByte('H'.toByte)
        buf.writeInt32(4)
end FlushMarshaller
