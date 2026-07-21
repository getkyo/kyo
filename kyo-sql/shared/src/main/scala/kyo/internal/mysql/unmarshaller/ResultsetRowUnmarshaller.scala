package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlException
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

    def read(buf: MysqlBufferReader)(using Frame): ResultsetRow < Abort[SqlException.Decode] =
        readColumns(buf).map { values =>
            ResultsetRow(values)
        }
    end read

    private def readColumns(buf: MysqlBufferReader)(using
        Frame
    ): Chunk[Maybe[Span[Byte]]] < Abort[SqlException.Decode] =
        val b = Chunk.newBuilder[Maybe[Span[Byte]]]
        def loop(remaining: Int): Chunk[Maybe[Span[Byte]]] < Abort[SqlException.Decode] =
            if remaining == 0 then b.result()
            else
                buf.readByte().flatMap { rawByte =>
                    val firstByte = rawByte & 0xff
                    if firstByte == 0xfb then
                        b += Maybe.Absent
                        loop(remaining - 1)
                    else
                        // firstByte is the first byte of a lenenc-int encoding the string length
                        val lenEffect: Long < Abort[SqlException.Decode] = firstByte match
                            case v if v <= 250 => v.toLong
                            case 0xfc          => buf.readUInt16LE().map(_.toLong)
                            case 0xfd          => buf.readUInt24LE().map(_.toLong)
                            case 0xfe          => buf.readUInt64LE()
                            case _             => -1L // 0xff, sentinel
                        lenEffect.flatMap { len =>
                            buf.readBytes(len.toInt).flatMap { strBytes =>
                                b += Maybe.Present(strBytes)
                                loop(remaining - 1)
                            }
                        }
                    end if
                }
        loop(numColumns)
    end readColumns

end ResultsetRowUnmarshaller

object ResultsetRowUnmarshaller:
    def apply(numColumns: Int): ResultsetRowUnmarshaller = new ResultsetRowUnmarshaller(numColumns)
end ResultsetRowUnmarshaller
