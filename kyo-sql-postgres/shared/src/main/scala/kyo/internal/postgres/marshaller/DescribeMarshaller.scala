package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Describe
import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter

/** Marshaller for [[Describe]].
  *
  * Wire format: 'D' | Int32(length) | Byte1(target) | cstring(name)
  *
  * target: 'S' for a prepared statement, 'P' for a portal. Length includes the 4-byte length field itself.
  *
  * Reference: PostgreSQL §55.7 "Describe"
  */
object DescribeMarshaller extends Marshaller[Describe]:
    def write(msg: Describe, buf: PostgresBufferWriter): Unit =
        buf.framed('D'.toByte) {
            buf.writeByte(msg.target)
            buf.writeString(msg.name)
        }
    end write
end DescribeMarshaller
