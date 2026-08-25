package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.Terminate

/** Marshaller for [[Terminate]].
  *
  * Wire format: 'X' | Int32(4)
  *
  * No payload. Length is always 4 (the length field itself).
  *
  * Reference: PostgreSQL §55.7 "Terminate"
  */
object TerminateMarshaller extends Marshaller[Terminate.type]:
    def write(msg: Terminate.type, buf: PostgresBufferWriter): Unit =
        buf.writeByte('X'.toByte)
        buf.writeInt32(4)
end TerminateMarshaller
