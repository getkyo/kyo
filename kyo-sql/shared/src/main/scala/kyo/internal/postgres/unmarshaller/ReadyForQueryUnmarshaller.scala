package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.ReadyForQuery
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[ReadyForQuery]].
  *
  * Wire: 'Z' | Int32(5) | Byte1(status)
  *
  * Status byte: 'I' = idle, 'T' = in transaction, 'E' = failed transaction. The reader covers the message body only (type byte and length
  * already consumed).
  *
  * Reference: PostgreSQL §55.7 "ReadyForQuery"
  */
object ReadyForQueryUnmarshaller extends Unmarshaller[ReadyForQuery]:
    def read(buf: PostgresBufferReader)(using Frame): ReadyForQuery < Abort[SqlException.Decode] =
        buf.readByte().map { status =>
            ReadyForQuery(status)
        }
end ReadyForQueryUnmarshaller
