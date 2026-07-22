package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlConnectionProtocolDecodeException
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlDecodeException
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
                val reader = MysqlBufferReader(payload)
                Abort.run[SqlDecodeException](ColumnDefinition41Unmarshaller.read(reader)).flatMap {
                    case Result.Success(colDef) =>
                        readColumnDefs(channel, remaining - 1, acc.appended(colDef))
                    case Result.Failure(e) =>
                        Abort.fail(SqlConnectionProtocolDecodeException("ColumnDefinition41", e))
                    case Result.Panic(t) =>
                        Abort.fail(SqlConnectionProtocolDecodeException("ColumnDefinition41", t))
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
            if firstByte == 0xfe && payload.size < 9 then
                // Old-style EOF terminator (CLIENT_DEPRECATE_EOF NOT negotiated, len < 9).
                // Also matches the CLIENT_DEPRECATE_EOF OK terminator (len >= 7, also < 9) since
                // the MySQL OK-terminator in that mode is always exactly 7 bytes.
                acc
            else if firstByte == 0xff then
                // ERR packet mid-result
                val reader = MysqlBufferReader(payload.slice(1, payload.size))
                Abort.run[SqlDecodeException](
                    kyo.internal.mysql.unmarshaller.ErrPacketUnmarshaller.read(reader)
                ).flatMap {
                    case Result.Success(err) => Abort.fail(MysqlErrors.mkServerError(err, sqlText, 0, connectionId))
                    case Result.Failure(e)   => Abort.fail(SqlConnectionProtocolDecodeException("ERR", e))
                    case Result.Panic(t) =>
                        Abort.fail(SqlConnectionProtocolDecodeException("ERR", t))
                }
            else
                // Row packet, decode as ResultsetRow
                val reader          = MysqlBufferReader(payload)
                val rowUnmarshaller = ResultsetRowUnmarshaller(columnDefs.size)
                Abort.run[SqlDecodeException](rowUnmarshaller.read(reader)).flatMap {
                    case Result.Success(row) =>
                        // Text protocol: values are ASCII-encoded per MySQL simple-query wire format.
                        val mysqlRow = new MysqlRow(row.values, columnDefs, kyo.internal.postgres.types.Format.Text)
                        readRows(channel, columnDefs, acc.appended(mysqlRow), sqlText, connectionId)
                    case Result.Failure(e) =>
                        Abort.fail(SqlConnectionProtocolDecodeException("Row", e))
                    case Result.Panic(t) =>
                        Abort.fail(SqlConnectionProtocolDecodeException("Row", t))
                }
            end if
        }
    end readRows

end ResultSetExchange
