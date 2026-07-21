package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.NoticeResponse
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[NoticeResponse]].
  *
  * Wire: 'N' | Int32(len) | [Byte1(tag) cstring(value)]* | 0x00
  *
  * Identical wire shape to [[ErrorResponse]] but non-fatal. The reader covers the message body only (type byte and length already
  * consumed).
  *
  * Reference: PostgreSQL §55.7 "NoticeResponse"
  */
object NoticeResponseUnmarshaller extends Unmarshaller[NoticeResponse]:
    def read(buf: PostgresBufferReader)(using Frame): NoticeResponse < Abort[SqlException.Decode] =
        buf.readByte().flatMap { firstTag =>
            readFields(buf, firstTag, Chunk.empty).map { fields =>
                NoticeResponse(fields)
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

end NoticeResponseUnmarshaller
