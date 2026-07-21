package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.CommandComplete
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[CommandComplete]].
  *
  * Wire: 'C' | Int32(len) | cstring(tag)
  *
  * The reader covers the message body only (type byte and length already consumed).
  *
  * Reference: PostgreSQL §55.7 "CommandComplete"
  */
object CommandCompleteUnmarshaller extends Unmarshaller[CommandComplete]:
    def read(buf: PostgresBufferReader)(using Frame): CommandComplete < Abort[SqlException.Decode] =
        CommandComplete(buf.readString())
end CommandCompleteUnmarshaller
