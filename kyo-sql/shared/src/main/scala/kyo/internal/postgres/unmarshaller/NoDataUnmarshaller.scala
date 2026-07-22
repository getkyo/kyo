package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.postgres.NoData
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[NoData]].
  *
  * Wire: 'n' | Int32(4)
  *
  * No payload. The reader covers the (empty) message body.
  *
  * Reference: PostgreSQL §55.7 "NoData"
  */
object NoDataUnmarshaller extends Unmarshaller[NoData.type]:
    def read(buf: PostgresBufferReader)(using Frame): NoData.type < Abort[SqlDecodeException] =
        NoData
end NoDataUnmarshaller
