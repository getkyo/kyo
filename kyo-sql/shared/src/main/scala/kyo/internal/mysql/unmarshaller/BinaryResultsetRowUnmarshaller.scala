package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.BinaryResultsetRow
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.Unmarshaller

/** Unmarshaller for [[BinaryResultsetRow]], binary-protocol result set rows (from COM_STMT_EXECUTE).
  *
  * Wire: 0x00 (packet header byte) | null-bitmap[ceil((numCols+7+2)/8) bytes] | [column-values]*
  *
  * Null-bitmap layout: the bitmap has a 2-bit historical offset. Column N is null when bit (N+2) is set in the bitmap (0-indexed across
  * bytes). So column 0 → byte 0, bit 2; column 1 → byte 0, bit 3; column 6 → byte 1, bit 0; etc.
  *
  * After the bitmap, non-null column values are written in order in their binary encoding (type-specific; the type layer does decoding).
  *
  * The 0x00 header byte has already been consumed by the caller or the packet dispatch layer.
  *
  * @param numColumns
  *   number of columns in the result set (determines bitmap length and expected value count)
  * @param columnTypes
  *   column MySQL type codes (used to determine how many bytes to read per non-null value)
  *
  * Reference: MySQL Internals, Binary Resultset Row
  */
final class BinaryResultsetRowUnmarshaller(numColumns: Int, columnTypes: Chunk[Int])
    extends Unmarshaller[BinaryResultsetRow]:

    def read(buf: MysqlBufferReader)(using Frame): BinaryResultsetRow < Abort[SqlException.Decode] =
        // null-bitmap length: ceil((numColumns + 2 + 7) / 8), the +2 is the historical offset
        val bitmapLen = (numColumns + 2 + 7) / 8
        buf.readBytes(bitmapLen).flatMap { nullBitmap =>
            readColumns(buf, nullBitmap).map { values =>
                BinaryResultsetRow(nullBitmap, values)
            }
        }
    end read

    private def readColumns(
        buf: MysqlBufferReader,
        nullBitmap: Span[Byte]
    )(using Frame): Chunk[Maybe[Span[Byte]]] < Abort[SqlException.Decode] =
        val b = Chunk.newBuilder[Maybe[Span[Byte]]]
        def loop(i: Int): Chunk[Maybe[Span[Byte]]] < Abort[SqlException.Decode] =
            if i >= numColumns then b.result()
            else
                // Check null-bitmap: bit (i+2) of the bitmap
                val bitIndex  = i + 2
                val byteIndex = bitIndex / 8
                val bitPos    = bitIndex % 8
                val isNull    = (nullBitmap(byteIndex) & (1 << bitPos)) != 0
                if isNull then
                    b += Maybe.Absent
                    loop(i + 1)
                else
                    val colType = columnTypes(i)
                    readColumnValue(buf, colType).flatMap { rawBytes =>
                        b += Maybe.Present(rawBytes)
                        loop(i + 1)
                    }
                end if
        loop(0)
    end readColumns

    /** Reads a binary-encoded column value based on MySQL column type.
      *
      * Type code reference (MySQL Internals, Column Type):
      *   - 0x01 TINY: 1 byte
      *   - 0x02 SHORT, 0x0D YEAR: 2 bytes
      *   - 0x03 LONG, 0x09 INT24, 0x07 TIMESTAMP (date), 0x0A DATE, 0x0B TIME, 0x0C DATETIME: variable, but for simplicity, LONG=4,
      *     DATE/TIME=lenenc
      *   - 0x05 DOUBLE, 0x08 LONGLONG: 8 bytes
      *   - 0x04 FLOAT: 4 bytes
      *   - 0xFC BLOB, 0xFD VAR_STRING, 0xFE STRING, 0x0F VARCHAR, 0x01..0x0E others with text repr: lenenc-string
      *
      * We read fixed-size numerics directly and use lenenc-string for all variable-length types.
      */
    private def readColumnValue(buf: MysqlBufferReader, colType: Int)(using
        Frame
    ): Span[Byte] < Abort[SqlException.Decode] =
        colType match
            case 0x01        => buf.readBytes(1) // TINY
            case 0x02 | 0x0d => buf.readBytes(2) // SHORT, YEAR
            case 0x03 | 0x09 => buf.readBytes(4) // LONG, INT24
            case 0x04        => buf.readBytes(4) // FLOAT
            case 0x05 | 0x08 => buf.readBytes(8) // DOUBLE, LONGLONG
            case _           =>
                // Variable-length types: lenenc-string
                buf.readLenencInt().flatMap {
                    case Maybe.Absent =>
                        Abort.fail(SqlException.Decode(
                            "Unexpected 0xFF sentinel in binary resultset column length",
                            Maybe.Absent,
                            summon[Frame]
                        ))
                    case Maybe.Present(len) =>
                        buf.readBytes(len.toInt)
                }

end BinaryResultsetRowUnmarshaller

object BinaryResultsetRowUnmarshaller:
    def apply(numColumns: Int, columnTypes: Chunk[Int]): BinaryResultsetRowUnmarshaller =
        new BinaryResultsetRowUnmarshaller(numColumns, columnTypes)
end BinaryResultsetRowUnmarshaller
