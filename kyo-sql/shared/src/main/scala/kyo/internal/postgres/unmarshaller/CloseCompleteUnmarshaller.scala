package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.postgres.CloseComplete
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[CloseComplete]].
  *
  * Wire: '3' | Int32(4)
  *
  * No payload. The reader covers the (empty) message body.
  *
  * Reference: PostgreSQL §55.7 "CloseComplete"
  */
object CloseCompleteUnmarshaller extends Unmarshaller[CloseComplete.type]:
    def read(buf: PostgresBufferReader)(using Frame): CloseComplete.type < Abort[SqlDecodeException] =
        CloseComplete
end CloseCompleteUnmarshaller
