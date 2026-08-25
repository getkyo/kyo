package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.postgres.NotificationResponse
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[NotificationResponse]].
  *
  * Wire: 'A' | Int32(len) | Int32(pid) | cstring(channel) | cstring(payload)
  *
  * The reader covers the message body only (type byte and length already consumed).
  *
  * Reference: PostgreSQL §55.7 "NotificationResponse"
  */
object NotificationResponseUnmarshaller extends Unmarshaller[NotificationResponse]:
    def read(buf: PostgresBufferReader)(using Frame): NotificationResponse < Abort[SqlDecodeException] =
        buf.readInt32().map { pid =>
            val channel = buf.readString()
            val payload = buf.readString()
            NotificationResponse(pid, channel, payload)
        }
    end read
end NotificationResponseUnmarshaller
