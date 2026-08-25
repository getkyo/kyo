package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.postgres.CopyData
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[CopyData]] (server → client direction).
  *
  * Wire body (after type byte 'd' and Int32 length): raw data bytes (entire remaining body).
  *
  * Reference: PostgreSQL §55.7 "CopyData"
  */
object CopyDataUnmarshaller extends Unmarshaller[CopyData]:
    def read(buf: PostgresBufferReader)(using Frame): CopyData < Abort[SqlDecodeException] =
        CopyData(buf.readAll())
    end read
end CopyDataUnmarshaller
