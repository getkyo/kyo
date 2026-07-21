package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.Query

/** Marshaller for [[Query]] (Simple Query).
  *
  * Wire format: 'Q' | Int32(length) | cstring(sql)
  *
  * Length includes the 4-byte length field itself and the NUL terminator.
  *
  * Reference: PostgreSQL §55.7 "Query"
  */
object QueryMarshaller extends Marshaller[Query]:
    def write(msg: Query, buf: PostgresBufferWriter): Unit =
        buf.writeByte('Q'.toByte)
        val lengthOffset = buf.size
        buf.writeInt32(0) // placeholder
        buf.writeString(msg.sql)
        buf.patchInt32(lengthOffset, buf.size - lengthOffset)
    end write
end QueryMarshaller
