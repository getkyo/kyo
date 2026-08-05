package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.ResultsetRow
import kyo.internal.mysql.Unmarshaller

/** Unmarshaller for [[ResultsetRow]], text-protocol result set rows.
  *
  * Wire: [lenenc-string(value) | 0xFB(NULL)]* one per column
  *
  * Each column value is either a length-encoded string (numeric values are ASCII-rendered) or the byte 0xFB meaning SQL NULL.
  *
  * The number of columns must be known from the preceding ColumnDefinition41 packets.
  *
  * @param numColumns
  *   the number of columns in the result set
  *
  * Reference: MySQL Internals, Text Resultset Row
  */
final class ResultsetRowUnmarshaller(numColumns: Int) extends Unmarshaller[ResultsetRow]:

    def read(buf: MysqlBufferReader)(using Frame): ResultsetRow < Abort[SqlDecodeException] =
        readColumns(buf).map { values =>
            ResultsetRow(values)
        }
    end read

    private def readColumns(buf: MysqlBufferReader)(using
        Frame
    ): Chunk[Maybe[Span[Byte]]] < Abort[SqlDecodeException] =
        val b = Chunk.newBuilder[Maybe[Span[Byte]]]
        def loop(remaining: Int): Chunk[Maybe[Span[Byte]]] < Abort[SqlDecodeException] =
            if remaining == 0 then b.result()
            else
                buf.readLenencBytes().flatMap { column =>
                    b += column
                    loop(remaining - 1)
                }
        loop(numColumns)
    end readColumns

end ResultsetRowUnmarshaller

object ResultsetRowUnmarshaller:
    def apply(numColumns: Int): ResultsetRowUnmarshaller = new ResultsetRowUnmarshaller(numColumns)
end ResultsetRowUnmarshaller
