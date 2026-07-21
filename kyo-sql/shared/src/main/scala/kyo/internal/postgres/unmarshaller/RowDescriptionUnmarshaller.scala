package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.RowDescription
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[RowDescription]].
  *
  * Wire: 'T' | Int32(len) | Int16(numFields) | [FieldDescription]*
  *
  * Each FieldDescription:
  *   cstring(name) | Int32(tableOid) | Int16(colAttr) | Int32(dataType)
  *   | Int16(dataTypeSize) | Int32(typeModifier) | Int16(formatCode)
  *
  * The reader covers the message body only (type byte and length already consumed).
  *
  * Reference: PostgreSQL §55.7 "RowDescription"
  */
object RowDescriptionUnmarshaller extends Unmarshaller[RowDescription]:
    def read(buf: PostgresBufferReader)(using Frame): RowDescription < Abort[SqlException.Decode] =
        buf.readInt16().flatMap { numFieldsShort =>
            readFields(buf, numFieldsShort.toInt & 0xffff, Chunk.empty).map { fields =>
                RowDescription(fields)
            }
        }
    end read

    private def readFields(buf: PostgresBufferReader, remaining: Int, acc: Chunk[FieldDescription])(using
        Frame
    ): Chunk[FieldDescription] < Abort[SqlException.Decode] =
        if remaining == 0 then acc
        else
            val name = buf.readString()
            buf.readInt32().flatMap { tableOid =>
                buf.readInt16().flatMap { colAttr =>
                    buf.readInt32().flatMap { dataType =>
                        buf.readInt16().flatMap { dataTypeSize =>
                            buf.readInt32().flatMap { typeMod =>
                                buf.readInt16().flatMap { fmtCode =>
                                    val field = FieldDescription(name, tableOid, colAttr, dataType, dataTypeSize, typeMod, fmtCode)
                                    readFields(buf, remaining - 1, acc.appended(field))
                                }
                            }
                        }
                    }
                }
            }

end RowDescriptionUnmarshaller
