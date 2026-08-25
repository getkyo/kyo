package kyo.internal.postgres.marshaller

import kyo.internal.postgres.Marshaller
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.SASLResponse

/** Marshaller for [[SASLResponse]].
  *
  * Wire format: 'p' | Int32(length) | bytes
  *
  * Length includes the 4-byte length field itself. The payload is raw SASL bytes (no extra framing).
  *
  * Reference: PostgreSQL §55.7 "SASLResponse"
  */
object SASLResponseMarshaller extends Marshaller[SASLResponse]:
    def write(msg: SASLResponse, buf: PostgresBufferWriter): Unit =
        buf.framed('p'.toByte) {
            buf.writeBytes(msg.data)
        }
    end write
end SASLResponseMarshaller
