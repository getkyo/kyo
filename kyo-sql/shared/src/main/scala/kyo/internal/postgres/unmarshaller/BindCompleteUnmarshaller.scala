package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.postgres.BindComplete
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[BindComplete]].
  *
  * Wire: '2' | Int32(4)
  *
  * No payload. The reader covers the (empty) message body.
  *
  * Reference: PostgreSQL §55.7 "BindComplete"
  */
object BindCompleteUnmarshaller extends Unmarshaller[BindComplete.type]:
    def read(buf: PostgresBufferReader)(using Frame): BindComplete.type < Abort[SqlDecodeException] =
        BindComplete
end BindCompleteUnmarshaller
