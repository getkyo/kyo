package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.BackendKeyData
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[BackendKeyData]].
  *
  * Wire: 'K' | Int32(12) | Int32(processId) | Int32(secretKey)
  *
  * The reader covers the message body only (type byte and length already consumed).
  *
  * Reference: PostgreSQL §55.7 "BackendKeyData"
  */
object BackendKeyDataUnmarshaller extends Unmarshaller[BackendKeyData]:
    def read(buf: PostgresBufferReader)(using Frame): BackendKeyData < Abort[SqlException.Decode] =
        buf.readInt32().flatMap { pid =>
            buf.readInt32().map { secret =>
                BackendKeyData(pid, secret)
            }
        }
    end read
end BackendKeyDataUnmarshaller
