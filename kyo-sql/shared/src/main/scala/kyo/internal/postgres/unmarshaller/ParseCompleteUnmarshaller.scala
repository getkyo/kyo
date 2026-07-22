package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.postgres.ParseComplete
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[ParseComplete]].
  *
  * Wire: '1' | Int32(4)
  *
  * No payload. The reader covers the (empty) message body.
  *
  * Reference: PostgreSQL §55.7 "ParseComplete"
  */
object ParseCompleteUnmarshaller extends Unmarshaller[ParseComplete.type]:
    def read(buf: PostgresBufferReader)(using Frame): ParseComplete.type < Abort[SqlDecodeException] =
        ParseComplete
end ParseCompleteUnmarshaller
