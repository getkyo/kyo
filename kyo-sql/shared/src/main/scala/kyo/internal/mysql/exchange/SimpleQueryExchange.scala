package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.*

/** Sends a COM_QUERY text-protocol request and collects the complete result.
  *
  * The MySQL text protocol response to COM_QUERY is:
  *   - First byte 0x00: [[OkPacket]] (no result set; returns affectedRows + lastInsertId)
  *   - First byte 0xFE + len >= 7: [[OkPacket]] (CLIENT_DEPRECATE_EOF form — no result set)
  *   - First byte 0xFF: [[ErrPacket]] (server error)
  *   - First byte 0xFB: LOCAL INFILE request — routed to [[LocalInfileExchange]] via [[runLocalInfile]]
  *   - Otherwise: lenenc-int column count, then result set (N column defs + rows + terminator)
  *
  * When CLIENT_DEPRECATE_EOF is negotiated (the kyo-sql default), the server may send an OK packet with first byte 0xFE when the payload
  * length is >= 7. Without this branch, 0xFE falls through to the lenenc-int column-count path, where `readLenencInt` interprets 0xFE as a
  * 9-byte uint64 prefix and overruns the (7-byte) packet, causing ArrayIndexOutOfBoundsException.
  *
  * The sequence ID is reset to 0 before sending, as each COM_QUERY is a new command boundary.
  *
  * Reference: MySQL Internals — Text Protocol / COM_QUERY, Generic Response Packets
  */
private[mysql] object SimpleQueryExchange:

    /** Executes a text-protocol query and returns all rows.
      *
      * @return
      *   `(rows, affectedRows)` — rows is empty if the query produced no result set; affectedRows is 0 for SELECT statements
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
                    val reader = MysqlBufferReader(payload.slice(1, payload.size))
                    Abort.run[SqlException.Decode](
                        channel.unmarshallers.okPacket.read(reader)
                    ).flatMap {
                        case Result.Success(ok) =>
                            (Chunk.empty[MysqlRow], ok.affectedRows)
                        case Result.Failure(e) =>
                            Abort.fail(SqlException.Connection(s"OK packet decode failed: ${e.message}", summon[Frame]))
                        case Result.Panic(t) =>
                            java.lang.System.err.println(s"[kyo-sql] SimpleQueryExchange: OK decode panic: ${t.getMessage}")
                            Abort.fail(SqlException.Connection(s"OK decode panic: ${t.getMessage}", summon[Frame]))
                    }
                else if firstByte == 0xff then
                    // ErrPacket
                    val reader = MysqlBufferReader(payload.slice(1, payload.size))
                    Abort.run[SqlException.Decode](
                        channel.unmarshallers.errPacket.read(reader)
                    ).flatMap {
                        case Result.Success(err) =>
                            Abort.fail(MysqlErrors.mkServerError(err, Present(sql), 0, connectionId))
                        case Result.Failure(e) =>
                            Abort.fail(SqlException.Connection(s"ERR packet decode failed: ${e.message}", summon[Frame]))
                        case Result.Panic(t) =>
                            java.lang.System.err.println(s"[kyo-sql] SimpleQueryExchange: ERR decode panic: ${t.getMessage}")
                            Abort.fail(SqlException.Connection(s"ERR decode panic: ${t.getMessage}", summon[Frame]))
                    }
                else if firstByte == 0xfb then
                    // LOCAL INFILE request received for a regular query — use loadLocalInfile API instead.
                    Abort.fail(SqlException.Request(
                        "Server sent a LOCAL INFILE request. Use SqlClient.loadLocalInfile or MysqlConnection.loadLocalInfile instead of a plain query.",
                        Present(sql),
                        summon[Frame]
                    ))
                else
                    // Result set: firstByte is the first byte of a lenenc-int column count
                    val reader = MysqlBufferReader(payload)
                    Abort.run[SqlException.Decode](reader.readLenencInt()).flatMap {
                        case Result.Success(Maybe.Absent) =>
                            Abort.fail(SqlException.Connection("Unexpected 0xFF sentinel in column count", summon[Frame]))
                        case Result.Success(Maybe.Present(columnCountLong)) =>
                            val columnCount = columnCountLong.toInt
                            ResultSetExchange.collect(channel, columnCount, deprecateEof, Present(sql), connectionId).map { rows =>
                                (rows, 0L)
                            }
                        case Result.Failure(e) =>
                            Abort.fail(SqlException.Connection(s"Column count decode failed: ${e.message}", summon[Frame]))
                        case Result.Panic(t) =>
                            java.lang.System.err.println(s"[kyo-sql] SimpleQueryExchange: column count panic: ${t.getMessage}")
                            Abort.fail(SqlException.Connection(s"Column count decode panic: ${t.getMessage}", summon[Frame]))
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
      *   a `LOAD DATA LOCAL INFILE 'filename' INTO TABLE ...` statement; the filename is arbitrary — the server echoes it back in the
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
                    val reader = MysqlBufferReader(payload.slice(1, payload.size))
                    Abort.run[SqlException.Decode](
                        channel.unmarshallers.errPacket.read(reader)
                    ).flatMap {
                        case Result.Success(err) =>
                            Abort.fail(MysqlErrors.mkServerError(err, Present(sql), 0, connectionId))
                        case Result.Failure(e) =>
                            Abort.fail(SqlException.Connection(s"LOCAL INFILE ERR packet decode failed: ${e.message}", summon[Frame]))
                        case Result.Panic(t) =>
                            java.lang.System.err.println(s"[kyo-sql] SimpleQueryExchange.runLocalInfile: ERR decode panic: ${t.getMessage}")
                            Abort.fail(SqlException.Connection(s"LOCAL INFILE ERR decode panic: ${t.getMessage}", summon[Frame]))
                    }
                else
                    Abort.fail(SqlException.Connection(
                        s"LOCAL INFILE: unexpected response byte 0x${firstByte.toHexString} to LOAD DATA LOCAL INFILE command",
                        summon[Frame]
                    ))
                end if
            }
        }
    end runLocalInfile

end SimpleQueryExchange
