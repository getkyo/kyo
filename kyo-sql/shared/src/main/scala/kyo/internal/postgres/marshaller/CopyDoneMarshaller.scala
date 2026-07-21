package kyo.internal.postgres.marshaller

import kyo.internal.postgres.CopyDone
import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter

/** Marshaller for [[CopyDone]] (client → server direction).
  *
  * Wire format: 'c' | Int32(4)
  *
  * No payload. Length is always 4 (the length field itself).
  *
  * Reference: PostgreSQL §55.7 "CopyDone"
  */
object CopyDoneMarshaller extends Marshaller[CopyDone.type]:
    def write(msg: CopyDone.type, buf: PostgresBufferWriter): Unit =
        buf.writeByte('c'.toByte)
        buf.writeInt32(4)
    end write
end CopyDoneMarshaller
