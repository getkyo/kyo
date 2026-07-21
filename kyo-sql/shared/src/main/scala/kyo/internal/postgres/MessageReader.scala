package kyo.internal.postgres

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.unmarshaller.Unmarshallers
import kyo.net.Connection

/** Connection-scoped buffer accumulator that reconstructs complete Postgres backend messages from raw byte chunks.
  *
  * The Postgres v3 wire format (post-startup) is: Byte1(type) | Int32(length including itself) | payload
  *
  * A single chunk delivered from the [[Connection.inbound]] channel may contain zero, one, or many complete messages, and a single message
  * may span multiple chunks. [[MessageReader]] reassembles them before handing the payload to the appropriate [[Unmarshaller]].
  *
  * State is mutable (pending buffer) and must not be shared across concurrent accesses, each [[PostgresConnection]] owns exactly one
  * [[MessageReader]].
  */
final class MessageReader:

    // Not thread-safe by design: each MessageReader is owned by a single PostgresConnection fiber.
    private var pending: Chunk[Byte] = Chunk.empty

    /** Reads one complete [[BackendMessage]] from the connection, pulling more chunks from the inbound channel as needed.
      *
      * Blocks (suspends the fiber) only when additional bytes are required to complete the current message header or payload.
      */
    def readOne(conn: Connection, unmarshallers: Unmarshallers)(using Frame): BackendMessage < (Async & Abort[SqlException]) =
        pullUntilComplete(conn, unmarshallers)

    // 5 bytes = 1 type byte + 4 length bytes
    private val headerSize = 5

    private def pullUntilComplete(conn: Connection, unmarshallers: Unmarshallers)(using
        Frame
    ): BackendMessage < (Async & Abort[SqlException]) =
        if pending.size >= headerSize then
            // We have enough bytes to read the header.
            val msgType   = pending(0)
            val len       = readInt32At(pending, 1) // length includes itself (4 bytes) but not the type byte
            val totalSize = 1 + len                 // type byte + (length field + payload)
            if pending.size >= totalSize then
                // We have a complete message.
                val bodyLen = len - 4 // payload length excluding the 4-byte length field
                val body    = pending.slice(headerSize, headerSize + bodyLen)
                pending = pending.slice(totalSize, pending.size)
                decodeMessage(msgType, body, unmarshallers)
            else
                // Need more bytes.
                pullMoreAndRetry(conn, unmarshallers)
            end if
        else
            // Need more bytes to even read the header.
            pullMoreAndRetry(conn, unmarshallers)

    private[postgres] def onTakePanic(t: Throwable)(using Frame): SqlException.Connection < Sync =
        Log.error(s"[kyo-sql] MessageReader: unexpected panic: ${t.getMessage}").andThen(
            SqlException.Connection(s"Unexpected error reading message: ${t.getMessage}", summon[Frame])
        )

    private def pullMoreAndRetry(conn: Connection, unmarshallers: Unmarshallers)(using
        Frame
    ): BackendMessage < (Async & Abort[SqlException]) =
        Abort.run[Closed](conn.inbound.safe.take).flatMap {
            case Result.Success(span) =>
                // Span has no IterableOnce/Iterable surface (opts out of Scala collections);
                // Chunk has no Span overload. .toArray is the only safe Span->Chunk bridge.
                pending = pending.concat(Chunk.from(span.toArray))
                pullUntilComplete(conn, unmarshallers)
            case Result.Failure(_) =>
                Abort.fail(SqlException.Connection("Connection closed while reading message", summon[Frame]))
            case Result.Panic(t) =>
                onTakePanic(t).flatMap(Abort.fail(_))
        }

    private def readInt32At(chunk: Chunk[Byte], offset: Int): Int =
        val b0 = chunk(offset) & 0xff
        val b1 = chunk(offset + 1) & 0xff
        val b2 = chunk(offset + 2) & 0xff
        val b3 = chunk(offset + 3) & 0xff
        (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
    end readInt32At

    private def decodeMessage(msgType: Byte, body: Chunk[Byte], unmarshallers: Unmarshallers)(using
        Frame
    ): BackendMessage < (Async & Abort[SqlException]) =
        val buf = new PostgresBufferReader(Span.from(body.toArray))
        val result: BackendMessage < Abort[SqlException.Decode] = msgType match
            case 'R' => unmarshallers.authentication.read(buf)
            case 'S' => unmarshallers.parameterStatus.read(buf)
            case 'K' => unmarshallers.backendKeyData.read(buf)
            case 'Z' => unmarshallers.readyForQuery.read(buf)
            case 'T' => unmarshallers.rowDescription.read(buf)
            case 'D' => unmarshallers.dataRow.read(buf)
            case 'C' => unmarshallers.commandComplete.read(buf)
            case 'E' => unmarshallers.errorResponse.read(buf)
            case 'N' => unmarshallers.noticeResponse.read(buf)
            case 'A' => unmarshallers.notificationResponse.read(buf)
            case '1' => unmarshallers.parseComplete.read(buf)
            case '2' => unmarshallers.bindComplete.read(buf)
            case '3' => unmarshallers.closeComplete.read(buf)
            case 't' => unmarshallers.parameterDescription.read(buf)
            case 'n' => unmarshallers.noData.read(buf)
            case 's' => unmarshallers.portalSuspended.read(buf)
            case 'I' => EmptyQueryResponse
            // COPY protocol messages
            case 'G' => unmarshallers.copyInResponse.read(buf)
            case 'H' => unmarshallers.copyOutResponse.read(buf)
            case 'd' => unmarshallers.copyData.read(buf)
            case 'c' => unmarshallers.copyDone.read(buf)
            case unknown =>
                Abort.fail(SqlException.Decode(
                    s"Unknown backend message type: '${unknown.toChar}' (0x${(unknown & 0xff).toHexString})",
                    Maybe.Absent,
                    summon[Frame]
                ))
        Abort.run[SqlException.Decode](result).flatMap {
            case Result.Success(msg) => msg
            case Result.Failure(e)   => Abort.fail(SqlException.Connection(s"Message decode failed: ${e.message}", summon[Frame]))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] MessageReader: decode panic: ${t.getMessage}").andThen(
                    Abort.fail(SqlException.Connection(s"Decode panic: ${t.getMessage}", summon[Frame]))
                )
        }
    end decodeMessage

end MessageReader
