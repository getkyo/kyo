package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.postgres.DataRow
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[DataRow]].
  *
  * Wire: 'D' | Int32(len) | Int16(numCols) | [Int32(colLen) bytes | Int32(-1)]*
  *
  * A column length of -1 represents SQL NULL ([[Maybe.Absent]]). The reader covers the message body only (type byte and length already
  * consumed).
  *
  * Reference: PostgreSQL §55.7 "DataRow"
  */
object DataRowUnmarshaller extends Unmarshaller[DataRow]:
    def read(buf: PostgresBufferReader)(using Frame): DataRow < Abort[SqlDecodeException] =
        buf.readInt16().flatMap { numColsShort =>
            readColumns(buf, numColsShort.toInt & 0xffff, Chunk.empty).map { values =>
                DataRow(values)
            }
        }
    end read

    private def readColumns(buf: PostgresBufferReader, remaining: Int, acc: Chunk[Maybe[kyo.Span[Byte]]])(using
        Frame
    ): Chunk[Maybe[kyo.Span[Byte]]] < Abort[SqlDecodeException] =
        if remaining == 0 then acc
        else
            buf.readInt32().flatMap { colLen =>
                val valueEffect: Maybe[kyo.Span[Byte]] < Abort[SqlDecodeException] =
                    if colLen == -1 then Absent
                    else buf.readBytes(colLen).map(Present(_))
                valueEffect.flatMap { value =>
                    readColumns(buf, remaining - 1, acc.appended(value))
                }
            }

end DataRowUnmarshaller
