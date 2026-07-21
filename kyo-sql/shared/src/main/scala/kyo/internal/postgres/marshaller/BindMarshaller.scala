package kyo.internal.postgres.marshaller

import kyo.*
import kyo.internal.postgres.Bind
import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter

/** Marshaller for [[Bind]].
  *
  * Wire format:
  *   'B' | Int32(length) | cstring(portal) | cstring(stmt)
  *       | Int16(numParamFmts) | Int16(fmt)*
  *       | Int16(numParams) | [Int32(len) bytes | Int32(-1)]*
  *       | Int16(numResultFmts) | Int16(fmt)*
  *
  * Length includes the 4-byte length field itself.
  * A NULL parameter value is encoded as Int32(-1) with no following bytes.
  *
  * Reference: PostgreSQL §55.7 "Bind"
  */
object BindMarshaller extends Marshaller[Bind]:
    def write(msg: Bind, buf: PostgresBufferWriter): Unit =
        buf.writeByte('B'.toByte)
        val lengthOffset = buf.size
        buf.writeInt32(0) // placeholder

        buf.writeString(msg.portalName)
        buf.writeString(msg.stmtName)

        // Parameter format codes
        buf.writeInt16(msg.paramFormats.size.toShort)
        msg.paramFormats.foreach(buf.writeInt16)

        // Parameter values
        buf.writeInt16(msg.paramValues.size.toShort)
        msg.paramValues.foreach {
            case Absent =>
                buf.writeInt32(-1) // SQL NULL
            case Present(span) =>
                val arr = span.toArray
                buf.writeInt32(arr.length)
                buf.writeBytes(arr)
        }

        // Result format codes
        buf.writeInt16(msg.resultFormats.size.toShort)
        msg.resultFormats.foreach(buf.writeInt16)

        buf.patchInt32(lengthOffset, buf.size - lengthOffset)
    end write
end BindMarshaller
