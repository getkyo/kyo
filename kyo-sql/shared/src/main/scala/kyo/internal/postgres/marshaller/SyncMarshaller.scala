package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.Sync

/** Marshaller for [[Sync]].
  *
  * Wire format: 'S' | Int32(4)
  *
  * No payload. Length is always 4 (the length field itself).
  *
  * Reference: PostgreSQL §55.7 "Sync"
  */
object SyncMarshaller extends Marshaller[Sync.type]:
    def write(msg: Sync.type, buf: PostgresBufferWriter): Unit =
        buf.writeByte('S'.toByte)
        buf.writeInt32(4)
end SyncMarshaller
