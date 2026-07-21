package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.ErrorResponse
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[ErrorResponse]].
  *
  * Wire: 'E' | Int32(len) | [Byte1(tag) cstring(value)]* | 0x00
  *
  * Field tags include 'S' severity, 'V' severity (localised), 'C' SQLSTATE, 'M' message, 'D' detail, 'H' hint, 'P' position, 'p' internal
  * position, 'q' internal query, 'W' where, 's' schema, 't' table, 'c' column, 'd' data type, 'n' constraint, 'F' file, 'L' line, 'R'
  * routine. The list is terminated by a zero byte (not the field tag 0x00 — the tag 0x00 indicates end-of-fields).
  *
  * The reader covers the message body only (type byte and length already consumed).
  *
  * Reference: PostgreSQL §55.7 "ErrorResponse"
  */
object ErrorResponseUnmarshaller extends Unmarshaller[ErrorResponse]:
    def read(buf: PostgresBufferReader)(using Frame): ErrorResponse < Abort[SqlException.Decode] =
        buf.readByte().flatMap { firstTag =>
            readFields(buf, firstTag, Chunk.empty).map { fields =>
                ErrorResponse(fields)
            }
        }
    end read

    private def readFields(buf: PostgresBufferReader, tag: Byte, acc: Chunk[(Byte, String)])(using
        Frame
    ): Chunk[(Byte, String)] < Abort[SqlException.Decode] =
        if tag == 0.toByte then acc
        else
            val value = buf.readString()
            val next  = acc.appended((tag, value))
            if buf.remaining > 0 then
                buf.readByte().flatMap { nextTag => readFields(buf, nextTag, next) }
            else
                next
            end if

end ErrorResponseUnmarshaller
