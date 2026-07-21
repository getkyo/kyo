package kyo.internal.postgres.marshaller

import kyo.Span
import kyo.internal.postgres.CopyData
import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter

/** Marshaller for [[CopyData]] (client → server direction).
  *
  * Wire format: 'd' | Int32(len) | data
  *
  * Length includes the 4-byte length field itself. Payload is the raw COPY data bytes.
  *
  * Reference: PostgreSQL §55.7 "CopyData"
  */
object CopyDataMarshaller extends Marshaller[CopyData]:
    def write(msg: CopyData, buf: PostgresBufferWriter): Unit =
        buf.writeByte('d'.toByte)
        // len = 4 (length field) + data.size
        buf.writeInt32(4 + msg.data.size)
        buf.writeBytes(msg.data)
    end write
end CopyDataMarshaller
