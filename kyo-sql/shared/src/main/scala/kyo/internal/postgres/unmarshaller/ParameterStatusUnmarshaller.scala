package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.ParameterStatus
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[ParameterStatus]].
  *
  * Wire: 'S' | Int32(len) | cstring(name) | cstring(value)
  *
  * The reader covers the message body only (type byte and length already consumed).
  *
  * Reference: PostgreSQL §55.7 "ParameterStatus"
  */
object ParameterStatusUnmarshaller extends Unmarshaller[ParameterStatus]:
    def read(buf: PostgresBufferReader)(using Frame): ParameterStatus < Abort[SqlException.Decode] =
        val name  = buf.readString()
        val value = buf.readString()
        ParameterStatus(name, value)
    end read
end ParameterStatusUnmarshaller
