package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.postgres.PortalSuspended
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[PortalSuspended]].
  *
  * Wire: 's' | Int32(4)
  *
  * No payload. The reader covers the (empty) message body.
  *
  * Reference: PostgreSQL §55.7 "PortalSuspended"
  */
object PortalSuspendedUnmarshaller extends Unmarshaller[PortalSuspended.type]:
    def read(buf: PostgresBufferReader)(using Frame): PortalSuspended.type < Abort[SqlDecodeException] =
        PortalSuspended
end PortalSuspendedUnmarshaller
