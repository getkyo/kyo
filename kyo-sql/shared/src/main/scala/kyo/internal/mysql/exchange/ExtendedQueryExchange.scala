package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlConnectionProtocolDecodeException
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlConnectionUnexpectedSentinelException
import kyo.SqlDecodeException
import kyo.SqlException
import kyo.internal.Hash
import kyo.internal.mysql.*
import kyo.internal.mysql.unmarshaller.BinaryResultsetRowUnmarshaller
import kyo.internal.mysql.unmarshaller.ColumnDefinition41Unmarshaller

/** Implements the MySQL extended (prepared-statement) protocol cycle.
  *
  * Sequence per query:
  *   1. Look up `sql` in the per-connection prepared-statement cache ([[MysqlPreparedStmt]]).
  *   2. On cache miss:
  *      - Send [[ComStmtPrepare]].
  *      - Read [[StmtPrepareOk]] → `stmtId`, `numParams`, `numColumns`.
  *      - Read `numParams` [[ColumnDefinition41]] param descriptors (+ optional EOF when not CLIENT_DEPRECATE_EOF).
  *      - Read `numColumns` [[ColumnDefinition41]] result descriptors (+ optional EOF).
  *      - Store the resulting [[MysqlPreparedStmt]] in the cache.
  *   3. With a [[MysqlPreparedStmt]] in hand:
  *      - Build the null-bitmap and binary-encoded param values.
  *      - Send [[ComStmtExecute]] (flags=0, newParamsBound=1 on first execute).
  *      - Read `numColumns` [[ColumnDefinition41]] result descriptors again (server resends).
  *      - Read [[BinaryResultsetRow]] packets until terminator (OK or EOF).
  *   4. Decode each [[BinaryResultsetRow]] using column type bytes.
  *   5. Return rows (query) or affectedRows (execute).
  *
  * Prepared-statement caching: entries are stored in the per-connection `kyo.Cache`; misses trigger a full prepare round-trip. When an
  * entry is evicted, [[MysqlConnection.drainPendingCloses]] sends `ComStmtClose` before the next request, releasing the server-side
  * statement.
  *
  * Reference: MySQL Internals, Prepared Statements Protocol
  */
private[mysql] object ExtendedQueryExchange:

    /** Runs an extended query and returns all result rows. */
    def query(
        channel: MysqlChannel,
        stmtCache: Cache[String, MysqlPreparedStmt],
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        deprecateEof: Boolean,
        connectionId: Maybe[Long]
    )(using Frame): Chunk[MysqlRow] < (Async & Abort[SqlException]) =
        prepareAndExecute(channel, stmtCache, sql, params, deprecateEof, connectionId)
            .map { case (rows, _, _) => rows }

    /** Runs an extended DML statement and returns the number of affected rows. */
    def execute(
        channel: MysqlChannel,
        stmtCache: Cache[String, MysqlPreparedStmt],
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        deprecateEof: Boolean,
        connectionId: Maybe[Long]
    )(using Frame): Long < (Async & Abort[SqlException]) =
        prepareAndExecute(channel, stmtCache, sql, params, deprecateEof, connectionId)
            .map { case (_, affected, _) => affected }

    /** Runs an extended INSERT statement and returns `(affectedRows, lastInsertId)` from the server's OK packet.
      *
      * `lastInsertId` is `0` when the statement did not generate an auto-increment value; the caller decides how to surface that (typically
      * as `Maybe.Absent`).
      */
    def executeInsert(
        channel: MysqlChannel,
        stmtCache: Cache[String, MysqlPreparedStmt],
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        deprecateEof: Boolean,
        connectionId: Maybe[Long]
    )(using Frame): (Long, Long) < (Async & Abort[SqlException]) =
        prepareAndExecute(channel, stmtCache, sql, params, deprecateEof, connectionId)
            .map { case (_, affected, lastInsertId) => (affected, lastInsertId) }

    // --- Internals ---

    /** Resolves a [[MysqlPreparedStmt]] (cache or wire) then executes and collects results.
      *
      * Returns `(rows, affectedRows, lastInsertId)`. For SELECT statements, `affectedRows == 0` and `lastInsertId == 0`. For DML
      * statements, `rows` is empty and the two longs come straight from the server's OK packet.
      *
      * Package-private so that [[MysqlPipelineExchange]] can reuse a single round-trip (prepare + execute) per statement without
      * duplicating the prepare logic.
      */
    private[exchange] def prepareAndExecute(
        channel: MysqlChannel,
        stmtCache: Cache[String, MysqlPreparedStmt],
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        deprecateEof: Boolean,
        connectionId: Maybe[Long]
    )(using Frame): (Chunk[MysqlRow], Long, Long) < (Async & Abort[SqlException]) =
        prepareStmt(channel, stmtCache, sql, deprecateEof).flatMap { stmt =>
            executeStmt(channel, stmt, sql, params, deprecateEof, connectionId)
        }

    /** Resolves or creates a [[MysqlPreparedStmt]], caching it by SQL text hash. */
    private[exchange] def prepareStmt(
        channel: MysqlChannel,
        stmtCache: Cache[String, MysqlPreparedStmt],
        sql: String,
        deprecateEof: Boolean
    )(using Frame): MysqlPreparedStmt < (Async & Abort[SqlException]) =
        val key = cacheKey(sql)
        stmtCache.get(key).flatMap {
            case Maybe.Present(stmt) =>
                stmt
            case Maybe.Absent =>
                doPrepare(channel, sql, deprecateEof).flatMap { stmt =>
                    stmtCache.add(key, stmt).andThen(stmt)
                }
        }
    end prepareStmt

    /** Sends COM_STMT_PREPARE and reads the full prepare response. */
    private def doPrepare(
        channel: MysqlChannel,
        sql: String,
        deprecateEof: Boolean
    )(using Frame): MysqlPreparedStmt < (Async & Abort[SqlException]) =
        channel.resetSeq()
        channel.send(ComStmtPrepare(sql))(using channel.marshallers.comStmtPrepare).flatMap { _ =>
            // Read the StmtPrepareOk response (first byte 0x00).
            channel.readRawPayload.flatMap { payload =>
                val firstByte = payload(0) & 0xff
                if firstByte == 0xff then
                    // ERR packet from prepare.
                    readErrPayload(payload).flatMap(err => Abort.fail(MysqlErrors.mkServerError(err, Present(sql), 0, Maybe.Absent)))
                else if firstByte == 0x00 then
                    val reader = MysqlBufferReader(payload.slice(1, payload.size))
                    Abort.run[SqlDecodeException](
                        channel.unmarshallers.stmtPrepareOk.read(reader)
                    ).flatMap {
                        case Result.Success(ok) =>
                            readParamAndColumnDefs(channel, ok, deprecateEof)
                        case Result.Failure(e) =>
                            Abort.fail(SqlConnectionProtocolDecodeException("StmtPrepareOk", e))
                        case Result.Panic(t) =>
                            Abort.fail(SqlConnectionProtocolDecodeException("StmtPrepareOk", t))
                    }
                else
                    Abort.fail(SqlConnectionUnexpectedMessageException(
                        "COM_STMT_PREPARE",
                        "OK",
                        s"byte 0x${firstByte.toHexString}"
                    ))
                end if
            }
        }
    end doPrepare

    /** Reads param column-defs (numParams) + optional EOF + result column-defs (numColumns) + optional EOF. */
    private def readParamAndColumnDefs(
        channel: MysqlChannel,
        ok: StmtPrepareOk,
        deprecateEof: Boolean
    )(using Frame): MysqlPreparedStmt < (Async & Abort[SqlException]) =
        val numParams  = ok.numParams.toInt & 0xffff
        val numColumns = ok.numColumns.toInt & 0xffff
        readColumnDefs(channel, numParams).flatMap { paramDefs =>
            // Skip optional intermediate EOF after param defs (only if numParams > 0 and not deprecate-EOF).
            skipEofIfNeeded(channel, numParams, deprecateEof).flatMap { _ =>
                readColumnDefs(channel, numColumns).flatMap { columnDefs =>
                    skipEofIfNeeded(channel, numColumns, deprecateEof).map { _ =>
                        MysqlPreparedStmt(
                            stmtId = ok.stmtId,
                            paramCount = numParams,
                            columnCount = numColumns,
                            paramTypes = paramDefs.map(_.columnType),
                            columnDefs = columnDefs
                        )
                    }
                }
            }
        }
    end readParamAndColumnDefs

    /** Reads exactly `count` ColumnDefinition41 packets. Exposed as package-private for [[StreamQueryExchange]]. */
    private[exchange] def readColumnDefs(
        channel: MysqlChannel,
        count: Int
    )(using Frame): Chunk[ColumnDefinition41] < (Async & Abort[SqlException]) =
        if count == 0 then Chunk.empty
        else
            def loop(remaining: Int, acc: Chunk[ColumnDefinition41]): Chunk[ColumnDefinition41] < (Async & Abort[SqlException]) =
                if remaining == 0 then acc
                else
                    channel.readRawPayload.flatMap { payload =>
                        val reader = MysqlBufferReader(payload)
                        Abort.run[SqlDecodeException](ColumnDefinition41Unmarshaller.read(reader)).flatMap {
                            case Result.Success(colDef) =>
                                loop(remaining - 1, acc.appended(colDef))
                            case Result.Failure(e) =>
                                Abort.fail(SqlConnectionProtocolDecodeException("ColumnDefinition41", e))
                            case Result.Panic(t) =>
                                Abort.fail(SqlConnectionProtocolDecodeException("ColumnDefinition41", t))
                        }
                    }
            loop(count, Chunk.empty)
        end if
    end readColumnDefs

    /** Reads (and discards) an intermediate EOF packet if one is expected.
      *
      * Exposed as package-private for [[StreamQueryExchange]].
      */
    private[exchange] def skipEofIfNeeded(
        channel: MysqlChannel,
        count: Int,
        deprecateEof: Boolean
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        // With CLIENT_DEPRECATE_EOF, no intermediate EOF is sent.
        // Without it, an EOF follows a non-empty column-def block.
        if count > 0 && !deprecateEof then
            channel.readRawPayload.flatMap { payload =>
                val firstByte = payload(0) & 0xff
                if firstByte == 0xfe && payload.size < 9 then
                    () // EOF consumed
                else
                    Abort.fail(SqlConnectionUnexpectedMessageException(
                        "column defs",
                        "EOF",
                        s"byte 0x${firstByte.toHexString}"
                    ))
                end if
            }
        else ()
    end skipEofIfNeeded

    /** Sends COM_STMT_EXECUTE and reads the binary result set.
      *
      * Returns `(rows, affectedRows, lastInsertId)`, the two longs are populated from the OK packet for DML statements and left as `0` for
      * result-set responses.
      */
    private def executeStmt(
        channel: MysqlChannel,
        stmt: MysqlPreparedStmt,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        deprecateEof: Boolean,
        connectionId: Maybe[Long]
    )(using Frame): (Chunk[MysqlRow], Long, Long) < (Async & Abort[SqlException]) =
        channel.resetSeq()
        val encodedParams = params.map(_.encoded)
        val paramTypes    = params.map(p => (p.mysqlType, p.unsigned))
        val executeMsg = ComStmtExecute(
            stmtId = stmt.stmtId,
            flags = 0, // no cursor
            params = encodedParams,
            paramTypes = paramTypes,
            newParamsBound = 1
        )
        channel.send(executeMsg)(using channel.marshallers.comStmtExecute).flatMap { _ =>
            // Read the first response packet.
            channel.readRawPayload.flatMap { firstPayload =>
                val firstByte = firstPayload(0) & 0xff
                if firstByte == 0x00 || (firstByte == 0xfe && firstPayload.size >= 7) then
                    // OkPacket (no result set, DML statement).
                    val reader = MysqlBufferReader(firstPayload.slice(1, firstPayload.size))
                    Abort.run[SqlDecodeException](channel.unmarshallers.okPacket.read(reader)).flatMap {
                        case Result.Success(ok) => (Chunk.empty[MysqlRow], ok.affectedRows, ok.lastInsertId)
                        case Result.Failure(e)  => Abort.fail(SqlConnectionProtocolDecodeException("OK", e))
                        case Result.Panic(t) =>
                            Abort.fail(SqlConnectionProtocolDecodeException("OK", t))
                    }
                else if firstByte == 0xff then
                    readErrPayload(firstPayload).flatMap(err =>
                        Abort.fail(MysqlErrors.mkServerError(err, Present(sql), params.size, connectionId))
                    )
                else
                    // Result set: firstPayload is the column-count lenenc-int.
                    val reader = MysqlBufferReader(firstPayload)
                    Abort.run[SqlDecodeException](reader.readLenencInt()).flatMap {
                        case Result.Success(Maybe.Absent) =>
                            Abort.fail(SqlConnectionUnexpectedSentinelException("column count"))
                        case Result.Success(Maybe.Present(columnCountLong)) =>
                            val columnCount = columnCountLong.toInt
                            // Read the column definitions (server resends them for execute responses).
                            readColumnDefs(channel, columnCount).flatMap { columnDefs =>
                                skipEofIfNeeded(channel, columnCount, deprecateEof).flatMap { _ =>
                                    // Now read binary result rows.
                                    val colTypes = columnDefs.map(_.columnType)
                                    readBinaryRows(
                                        channel,
                                        stmt.columnDefs,
                                        colTypes,
                                        deprecateEof,
                                        Chunk.empty,
                                        Present(sql),
                                        params.size,
                                        connectionId
                                    )
                                        .map { rows => (rows, 0L, 0L) }
                                }
                            }
                        case Result.Failure(e) =>
                            Abort.fail(SqlConnectionProtocolDecodeException("column count", e))
                        case Result.Panic(t) =>
                            Abort.fail(SqlConnectionProtocolDecodeException("column count", t))
                    }
                end if
            }
        }
    end executeStmt

    /** Reads binary result-set rows until the OK/EOF terminator. */
    private def readBinaryRows(
        channel: MysqlChannel,
        columnDefs: Chunk[ColumnDefinition41],
        colTypes: Chunk[Int],
        deprecateEof: Boolean,
        acc: Chunk[MysqlRow],
        sqlText: Maybe[String],
        paramCount: Int,
        connectionId: Maybe[Long]
    )(using Frame): Chunk[MysqlRow] < (Async & Abort[SqlException]) =
        channel.readRawPayload.flatMap { payload =>
            val firstByte = payload(0) & 0xff
            if firstByte == 0xfe && payload.size < 9 then
                // EOF / OK terminator.
                acc
            else if firstByte == 0x00 && payload.size >= 7 && deprecateEof then
                // CLIENT_DEPRECATE_EOF OK terminator (first byte 0x00 is ambiguous; check size).
                // Actually for binary rows the 0x00 header byte is the row marker.
                // The row terminator with deprecate-eof is first byte 0xFE + len>=7 (OK packet).
                // But 0x00 + len>=7 could be a binary row header. Check more carefully:
                // Binary row: 0x00 | null-bitmap | values. The 0x00 is the packet header for rows.
                // OK terminator: 0x00 | lenenc(affectedRows=0) | lenenc(lastInsertId=0) | uint16(statusFlags) | uint16(warnings)
                // Minimum 7 bytes. So 0x00 + size>=7 can be EITHER a row or an OK. We need
                // to check the CLIENT_DEPRECATE_EOF OK more carefully: it's the packet where first byte is
                // 0xFE (not 0x00). So 0x00 here is always a binary row.
                val unmarshaller = BinaryResultsetRowUnmarshaller(colTypes.size, colTypes)
                val reader       = MysqlBufferReader(payload.slice(1, payload.size)) // skip 0x00 header
                Abort.run[SqlDecodeException](unmarshaller.read(reader)).flatMap {
                    case Result.Success(row) =>
                        val mysqlRow = decodeBinaryRow(row, columnDefs)
                        readBinaryRows(
                            channel,
                            columnDefs,
                            colTypes,
                            deprecateEof,
                            acc.appended(mysqlRow),
                            sqlText,
                            paramCount,
                            connectionId
                        )
                    case Result.Failure(e) =>
                        Abort.fail(SqlConnectionProtocolDecodeException("BinaryResultsetRow", e))
                    case Result.Panic(t) =>
                        Abort.fail(SqlConnectionProtocolDecodeException("BinaryResultsetRow", t))
                }
            else if firstByte == 0xff then
                readErrPayload(payload).flatMap(err => Abort.fail(MysqlErrors.mkServerError(err, sqlText, paramCount, connectionId)))
            else if firstByte == 0x00 then
                // Binary row packet (0x00 header byte).
                val unmarshaller = BinaryResultsetRowUnmarshaller(colTypes.size, colTypes)
                val reader       = MysqlBufferReader(payload.slice(1, payload.size))
                Abort.run[SqlDecodeException](unmarshaller.read(reader)).flatMap {
                    case Result.Success(row) =>
                        val mysqlRow = decodeBinaryRow(row, columnDefs)
                        readBinaryRows(
                            channel,
                            columnDefs,
                            colTypes,
                            deprecateEof,
                            acc.appended(mysqlRow),
                            sqlText,
                            paramCount,
                            connectionId
                        )
                    case Result.Failure(e) =>
                        Abort.fail(SqlConnectionProtocolDecodeException("BinaryResultsetRow", e))
                    case Result.Panic(t) =>
                        Abort.fail(SqlConnectionProtocolDecodeException("BinaryResultsetRow", t))
                }
            else
                Abort.fail(SqlConnectionUnexpectedMessageException(
                    "binary result set",
                    "OK / ERR / row",
                    s"byte 0x${firstByte.toHexString}"
                ))
            end if
        }
    end readBinaryRows

    /** Converts a [[BinaryResultsetRow]] to a [[MysqlRow]] using the raw values (already framed). */
    private def decodeBinaryRow(
        row: BinaryResultsetRow,
        columnDefs: Chunk[ColumnDefinition41]
    ): MysqlRow =
        // BinaryResultsetRow.values already contains Maybe[Span[Byte]] per column.
        // NULL columns → Absent; non-null → Present(raw bytes) in the MySQL BINARY wire format.
        // Row-level format tag lets downstream decoders (SqlRow.decode / MysqlRowReader) choose the
        // right decoder path per column type (binary integer widths, binary date/time, etc.).
        new MysqlRow(row.values, columnDefs, kyo.internal.postgres.types.Format.Binary)
    end decodeBinaryRow

    private def readErrPayload(
        payload: Span[Byte]
    )(using Frame): ErrPacket < (Async & Abort[SqlException]) =
        val reader = MysqlBufferReader(payload.slice(1, payload.size))
        Abort.run[SqlDecodeException](
            kyo.internal.mysql.unmarshaller.ErrPacketUnmarshaller.read(reader)
        ).flatMap {
            case Result.Success(err) => err
            case Result.Failure(e)   => Abort.fail(SqlConnectionProtocolDecodeException("ERR", e))
            case Result.Panic(t) =>
                Abort.fail(SqlConnectionProtocolDecodeException("ERR", t))
        }
    end readErrPayload

    /** Cache key: first 16 hex chars of SHA-256 of the SQL text.
      *
      * Mirrors [[kyo.internal.postgres.exchange.ExtendedQueryExchange.cacheKey]] for consistency.
      */
    def cacheKey(sql: String): String =
        val digest = Hash.sha256(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        digest.take(8).map(b => f"${b & 0xff}%02x").mkString
    end cacheKey

end ExtendedQueryExchange
