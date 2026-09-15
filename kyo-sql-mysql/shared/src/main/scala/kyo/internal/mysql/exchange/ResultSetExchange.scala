package kyo.internal.mysql.exchange
import kyo.*
import kyo.SqlCodec
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlException
import kyo.internal.mysql.*
import kyo.internal.mysql.unmarshaller.ColumnDefinition41Unmarshaller
import kyo.internal.mysql.unmarshaller.ResultsetRowUnmarshaller

/** Shared logic for collecting a MySQL text-protocol result set from an open [[MysqlChannel]].
  *
  * Called after the column-count lenenc-int has been decoded. Reads:
  *   1. N [[ColumnDefinition41]] packets (where N = columnCount)
  *   2. If CLIENT_DEPRECATE_EOF NOT negotiated: one intermediate [[EofPacket]] (ignored)
  *   3. [[ResultsetRow]] packets until a terminator packet ends the result set:
  *      - With CLIENT_DEPRECATE_EOF: 0xFE + len >= 7 (OK-encoded terminator, always 7 bytes in practice)
  *      - Without CLIENT_DEPRECATE_EOF: 0xFE + len < 9 (legacy EOF) Both are disambiguated by `firstByte == 0xFE && payload.size < 9` since
  *        the deprecate-EOF OK terminator is exactly 7 bytes (which is < 9).
  *
  * Returns a [[Chunk[MysqlRow]]] with one entry per data row.
  *
  * Reference: MySQL Internals, Text Resultset
  */
private[mysql] object ResultSetExchange:

    def collect(
        channel: MysqlChannel,
        columnCount: Int,
        deprecateEof: Boolean,
        sqlText: Maybe[String],
        connectionId: Maybe[Long]
    )(using Frame): Chunk[MysqlRow] < (Async & Abort[SqlException]) =
        readColumnDefs(channel, columnCount, Chunk.empty).flatMap { columnDefs =>
            // If CLIENT_DEPRECATE_EOF is NOT negotiated, there is an intermediate EOF between column defs and rows.
            // With CLIENT_DEPRECATE_EOF (the kyo-sql default) there is no intermediate EOF.
            val readRowsEffect: Chunk[MysqlRow] < (Async & Abort[SqlException]) =
                if deprecateEof then
                    // No intermediate EOF: go straight to reading rows
                    readRows(channel, columnDefs, Chunk.empty, sqlText, connectionId)
                else
                    // Expect an intermediate EOF packet, then read rows
                    channel.receive(false).flatMap {
                        case _: EofPacket => readRows(channel, columnDefs, Chunk.empty, sqlText, connectionId)
                        case err: ErrPacket =>
                            Abort.fail(MysqlErrors.mkServerError(err, sqlText, 0, connectionId))
                        case other =>
                            Abort.fail(SqlConnectionUnexpectedMessageException(
                                "column defs",
                                "EOF",
                                other.toString
                            ))
                    }
            readRowsEffect
        }
    end collect

    private def readColumnDefs(
        channel: MysqlChannel,
        remaining: Int,
        acc: Chunk[ColumnDefinition41]
    )(using Frame): Chunk[ColumnDefinition41] < (Async & Abort[SqlException]) =
        if remaining == 0 then acc
        else
            channel.readRawPayload.flatMap { payload =>
                ProtocolDecode.decode("ColumnDefinition41", payload, ColumnDefinition41Unmarshaller).flatMap { colDef =>
                    readColumnDefs(channel, remaining - 1, acc.appended(colDef))
                }
            }
    end readColumnDefs

    private def readRows(
        channel: MysqlChannel,
        columnDefs: Chunk[ColumnDefinition41],
        acc: Chunk[MysqlRow],
        sqlText: Maybe[String],
        connectionId: Maybe[Long]
    )(using Frame): Chunk[MysqlRow] < (Async & Abort[SqlException]) =
        channel.readRawPayload.flatMap { payload =>
            val firstByte = payload(0) & 0xff
            if firstByte == 0xfe then
                // Result-set terminator, either the legacy EOF packet or the CLIENT_DEPRECATE_EOF OK packet.
                //
                // Under CLIENT_DEPRECATE_EOF the terminator is an OK packet whose length grows past the 7-byte
                // minimum whenever it carries warnings, an info string or a session-state block, so its size is
                // deliberately not tested here: inside a result set a leading 0xfe can only be the terminator.
                //
                // Decoded rather than discarded: its status flags are the only place the server says whether this
                // statement produced result sets nobody has read yet, and the same packet carries the session variables
                // it changed.
                readTerminator(channel, payload, sqlText, connectionId).andThen(
                    drainRemainingResultSets(channel, sqlText, connectionId).andThen(acc)
                )
            else if firstByte == 0xff then
                // ERR packet mid-result
                ProtocolDecode.decode(
                    "ERR",
                    payload.slice(1, payload.size),
                    kyo.internal.mysql.unmarshaller.ErrPacketUnmarshaller
                ).flatMap { err =>
                    Abort.fail(MysqlErrors.mkServerError(err, sqlText, 0, connectionId))
                }
            else
                // Row packet, decode as ResultsetRow
                val rowUnmarshaller = ResultsetRowUnmarshaller(columnDefs.size)
                ProtocolDecode.decode("Row", payload, rowUnmarshaller).flatMap { row =>
                    // Text protocol: values are ASCII-encoded per MySQL simple-query wire format.
                    val mysqlRow = new MysqlRow(row.values, columnDefs, kyo.SqlCodec.Format.Text)
                    readRows(channel, columnDefs, acc.appended(mysqlRow), sqlText, connectionId)
                }
            end if
        }
    end readRows

    /** Decodes a result-set terminator and folds its status flags and session-state block into the connection's session view.
      *
      * Both terminator forms start 0xFE and both carry status flags; the CLIENT_DEPRECATE_EOF form is an OK packet and is the only one that
      * can carry a session-state block. The legacy EOF form is read as an OK packet too, which works because the two share their leading
      * fields, and a short legacy packet simply reports no block.
      */
    private[exchange] def readTerminator(
        channel: MysqlChannel,
        payload: Span[Byte],
        sqlText: Maybe[String],
        connectionId: Maybe[Long]
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        if payload.size >= 7 then
            ProtocolDecode.decode("result-set terminator", payload.slice(1, payload.size), channel.unmarshallers.okPacket)
                .flatMap(ok => channel.observeStatus(ok.statusFlags, ok.warnings, ok.sessionStateInfo))
        else
            // A legacy EOF shorter than the OK layout carries warnings and flags only; read the flags directly so the
            // more-results bit is still seen.
            val reader = MysqlBufferReader(payload.slice(1, payload.size))
            ProtocolDecode.decode("EOF warnings", reader.readUInt16LE()).flatMap { warnings =>
                ProtocolDecode.decode("EOF status", reader.readUInt16LE())
                    // The count this packet carried, not zero: it is what the conflict-clause re-raise reads to decide
                    // whether to fetch the detail, and zeroing it here would make that path see a clean statement.
                    .flatMap(flags => channel.observeStatus(flags.toShort, warnings.toShort, Maybe.Absent))
            }
        end if
    end readTerminator

    /** Reads and discards every result set the statement produced beyond the one the caller asked for.
      *
      * A statement can answer with more than one result set, and a `CALL` of any procedure that selects does: the procedure's own result set
      * arrives first, then a trailing status packet for the CALL itself. The connection is only reusable once the wire is back at a statement
      * boundary.
      *
      * The rows are dropped rather than returned because the surface that reached here asks for one result set; leaving them on the wire
      * hands them to the next borrower.
      *
      * Worth stating as a contract rather than an implementation detail: a `CALL` of a procedure that SELECTs more than once answers the
      * FIRST result set and discards the rest silently. That is better than the desync it replaced, and it is still a loss. A caller who needs
      * every result set has no surface here for it.
      */
    private def drainRemainingResultSets(
        channel: MysqlChannel,
        sqlText: Maybe[String],
        connectionId: Maybe[Long]
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        if !channel.sessionState.moreResultsExpected then ()
        else
            channel.readRawPayload.flatMap { payload =>
                val firstByte = payload(0) & 0xff
                if firstByte == 0xff then
                    ProtocolDecode.decode("ERR", payload.slice(1, payload.size), channel.unmarshallers.errPacket).flatMap { err =>
                        Abort.fail(MysqlErrors.mkServerError(err, sqlText, 0, connectionId))
                    }
                else if firstByte == 0x00 || (firstByte == 0xfe && payload.size >= 7) then
                    // A status packet for a statement in the sequence that produced no rows of its own.
                    ProtocolDecode.decode("OK", payload.slice(1, payload.size), channel.unmarshallers.okPacket)
                        .flatMap(ok => channel.observeStatus(ok.statusFlags, ok.warnings, ok.sessionStateInfo))
                        .andThen(drainRemainingResultSets(channel, sqlText, connectionId))
                else
                    // Another result set: its column count, then its definitions and rows, read only to reach its end.
                    val reader = MysqlBufferReader(payload)
                    ProtocolDecode.decode("column count", reader.readLenencInt()).flatMap { columnCountLong =>
                        readColumnDefs(channel, columnCountLong.toInt, Chunk.empty).flatMap { columnDefs =>
                            readRows(channel, columnDefs, Chunk.empty, sqlText, connectionId).andThen(())
                        }
                    }
                end if
            }
        end if
    end drainRemainingResultSets

end ResultSetExchange
