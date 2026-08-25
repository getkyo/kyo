package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlException
import kyo.SqlRequestMysqlLocalInfileRequiresLoadApiException
import kyo.internal.mysql.*

/** Sends a COM_QUERY text-protocol request and collects the complete result.
  *
  * The MySQL text protocol response to COM_QUERY is:
  *   - First byte 0x00: [[OkPacket]] (no result set; returns affectedRows + lastInsertId)
  *   - First byte 0xFE + len >= 7: [[OkPacket]] (CLIENT_DEPRECATE_EOF form, no result set)
  *   - First byte 0xFF: [[ErrPacket]] (server error)
  *   - First byte 0xFB: LOCAL INFILE request, routed to [[LocalInfileExchange]] via [[runLocalInfile]]
  *   - Otherwise: lenenc-int column count, then result set (N column defs + rows + terminator)
  *
  * When CLIENT_DEPRECATE_EOF is negotiated (the kyo-sql default), the server may send an OK packet with first byte 0xFE when the payload
  * length is >= 7. Without this branch, 0xFE falls through to the lenenc-int column-count path, where `readLenencInt` interprets 0xFE as a
  * 9-byte uint64 prefix and reads past the end of the (7-byte) packet, which `MysqlBufferReader` reports as
  * `SqlDecodeInsufficientBytesException` rather than as the OK packet the server sent.
  *
  * The sequence ID is reset to 0 before sending, as each COM_QUERY is a new command boundary.
  *
  * Reference: MySQL Internals, Text Protocol / COM_QUERY, Generic Response Packets
  */
private[mysql] object SimpleQueryExchange:

    /** Executes a text-protocol query and returns all rows.
      *
      * @return
      *   `(rows, affectedRows)`, rows is empty if the query produced no result set; affectedRows is 0 for SELECT statements
      */
    def run(
        channel: MysqlChannel,
        sql: String,
        deprecateEof: Boolean,
        connectionId: Maybe[Long]
    )(using Frame): (Chunk[MysqlRow], Long) < (Async & Abort[SqlException]) =
        // Reset sequence ID at the start of each command.
        channel.resetSeq()
        channel.send(ComQuery(sql))(using channel.marshallers.comQuery).flatMap { _ =>
            channel.readRawPayload.flatMap { payload =>
                val firstByte = payload(0) & 0xff
                if firstByte == 0x00 || (firstByte == 0xfe && payload.size >= 7) then
                    // OkPacket (no result set).
                    // 0x00: standard OK; 0xFE with len>=7: CLIENT_DEPRECATE_EOF OK marker.
                    ProtocolDecode.decode("OK", payload.slice(1, payload.size), channel.unmarshallers.okPacket).map { ok =>
                        (Chunk.empty[MysqlRow], ok.affectedRows)
                    }
                else if firstByte == 0xff then
                    // ErrPacket
                    ProtocolDecode.decode("ERR", payload.slice(1, payload.size), channel.unmarshallers.errPacket).flatMap { err =>
                        Abort.fail(MysqlErrors.mkServerError(err, Present(sql), 0, connectionId))
                    }
                else if firstByte == 0xfb then
                    // LOCAL INFILE request received for a regular query, use loadLocalInfile API instead.
                    Abort.fail(SqlRequestMysqlLocalInfileRequiresLoadApiException())
                else
                    // Result set: firstByte is the first byte of a lenenc-int column count
                    val reader = MysqlBufferReader(payload)
                    ProtocolDecode.decode("column count", reader.readLenencInt()).flatMap { columnCountLong =>
                        val columnCount = columnCountLong.toInt
                        ResultSetExchange.collect(channel, columnCount, deprecateEof, Present(sql), connectionId).map { rows =>
                            (rows, 0L)
                        }
                    }
                end if
            }
        }
    end run

    /** Executes a `LOAD DATA LOCAL INFILE` query and streams `data` bytes to the server.
      *
      * Sends the COM_QUERY, expects the server's LOCAL_INFILE_REQUEST (0xFB), then delegates to [[LocalInfileExchange.run]] to upload the
      * byte stream in chunks. Returns the affected-row count from the server's OK packet.
      *
      * The [[Capabilities.CLIENT_LOCAL_FILES]] flag is included in [[Capabilities.Default]] and is negotiated during the handshake. Without
      * it, the server rejects LOAD DATA LOCAL INFILE outright rather than sending the 0xFB request.
      *
      * @param sql
      *   a `LOAD DATA LOCAL INFILE 'filename' INTO TABLE ...` statement; the filename is arbitrary, the server echoes it back in the
      *   LOCAL_INFILE_REQUEST but kyo-sql ignores it and uploads `data` unconditionally.
      * @param data
      *   the byte stream to upload; caller supplies this (in-memory, [[Path.readBytes]], etc.)
      */
    def runLocalInfile[S](
        channel: MysqlChannel,
        sql: String,
        data: Stream[Byte, S],
        connectionId: Maybe[Long]
    )(using Frame): Long < (Async & Abort[SqlException] & Scope & S) =
        // Reset sequence ID at the start of each command.
        channel.resetSeq()
        channel.send(ComQuery(sql))(using channel.marshallers.comQuery).flatMap { _ =>
            channel.readRawPayload.flatMap { payload =>
                val firstByte = payload(0) & 0xff
                if firstByte == 0xfb then
                    // LOCAL_INFILE_REQUEST: server wants us to upload file data.
                    // The remaining bytes in payload are the filename string (we ignore it).
                    // The seqId is already advanced by readRawPayload to receivedSeq+1.
                    LocalInfileExchange.run(channel, data)
                else if firstByte == 0xff then
                    // Server rejected the LOAD DATA statement (e.g., local_infile=OFF, column mismatch).
                    ProtocolDecode.decode("LOCAL INFILE ERR", payload.slice(1, payload.size), channel.unmarshallers.errPacket).flatMap {
                        err =>
                            Abort.fail(MysqlErrors.mkServerError(err, Present(sql), 0, connectionId))
                    }
                else
                    Abort.fail(SqlConnectionUnexpectedMessageException(
                        "LOAD DATA LOCAL INFILE",
                        "0xFB request or 0xFF error",
                        s"byte 0x${firstByte.toHexString}"
                    ))
                end if
            }
        }
    end runLocalInfile

end SimpleQueryExchange
