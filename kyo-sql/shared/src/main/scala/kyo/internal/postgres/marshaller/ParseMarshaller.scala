package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.Parse
import kyo.internal.postgres.PostgresBufferWriter

/** Marshaller for [[Parse]].
  *
  * Wire format: 'P' | Int32(length) | cstring(stmtName) | cstring(sql) | Int16(numParams) | Int32(OID)*
  *
  * Length includes the 4-byte length field itself.
  *
  * Reference: PostgreSQL §55.7 "Parse"
  */
object ParseMarshaller extends Marshaller[Parse]:
    def write(msg: Parse, buf: PostgresBufferWriter): Unit =
        buf.writeByte('P'.toByte)
        val lengthOffset = buf.size
        buf.writeInt32(0) // placeholder
        buf.writeString(msg.stmtName)
        buf.writeString(msg.sql)
        buf.writeInt16(msg.paramTypes.size.toShort)
        msg.paramTypes.foreach(buf.writeInt32)
        buf.patchInt32(lengthOffset, buf.size - lengthOffset)
    end write
end ParseMarshaller
