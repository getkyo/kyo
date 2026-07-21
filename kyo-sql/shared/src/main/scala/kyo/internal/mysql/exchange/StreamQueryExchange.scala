package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.*
import kyo.internal.mysql.unmarshaller.BinaryResultsetRowUnmarshaller

/** Streams binary-protocol rows from a prepared MySQL statement.
  *
  * Approach: per-row wire reads (no cursor). After [[ComStmtExecute]] (flags=0) the server streams all rows without requiring explicit
  * fetch commands. Rows are pulled from the wire one-at-a-time and emitted into a [[Stream]].
  *
  * Protocol sequence:
  *   1. Prepare statement (or use cached one from [[ExtendedQueryExchange.prepareStmt]]).
  *   2. Send [[ComStmtExecute]] (flags=0 — no cursor).
  *   3. Read column-count packet + column definitions.
  *   4. For each [[BinaryResultsetRow]] packet: decode and emit.
  *   5. On OK/EOF terminator: close stream.
  *   6. On [[Scope]] exit: drain any remaining result packets, then send [[ComStmtClose]] to free the server-side statement.
  *
  * Drain-before-close: when the stream is terminated early (e.g. via `.take(n)`), unread binary row packets may remain on the wire. The
  * Scope cleanup drains those packets (discarding their content) until it sees the result-set terminator before sending COM_STMT_CLOSE.
  * This ensures the connection is in a clean state for the next command. If the stream was fully consumed (terminator already seen), the
  * drain is a no-op.
  *
  * Note: [[ComStmtClose]] is sent on scope exit even if the stream was not fully consumed (early termination via `.take(n)` etc.). This
  * ensures the server-side prepared statement is cleaned up. The cached [[MysqlPreparedStmt]] entry remains in the cache; server-side
  * cleanup for cache-evicted entries is handled by [[MysqlConnection.drainPendingCloses]] at the next request boundary.
  *
  * Reference: MySQL Internals — Prepared Statements, Binary Protocol
  */
private[mysql] object StreamQueryExchange:

    /** Returns a [[Stream]] of [[MysqlRow]] for the given parameterised `sql`.
      *
      * The stream's effect types include [[Scope]] so that cleanup (COM_STMT_CLOSE) is guaranteed on any exit path.
      */
    def stream(
        channel: MysqlChannel,
        stmtCache: Cache[String, MysqlPreparedStmt],
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        deprecateEof: Boolean,
        connectionId: Maybe[Long]
    )(using Frame): Stream[MysqlRow, Async & Abort[SqlException] & Scope] =
        Stream:
            ExtendedQueryExchange.prepareStmt(channel, stmtCache, sql, deprecateEof).flatMap { stmt =>
                sendExecute(channel, stmt, params).flatMap { _ =>
                    // Read the first response packet (column count or OK/ERR).
                    channel.readRawPayload.flatMap { firstPayload =>
                        val firstByte = firstPayload(0) & 0xff
                        if firstByte == 0xff then
                            // ERR packet
                            readErrAndFail(firstPayload, Present(sql), params.size, connectionId)
                        else if firstByte == 0x00 && firstPayload.size >= 7 then
                            // OK packet (DML — no result set). Empty stream.
                            ()
                        else
                            // Result set: firstPayload carries the lenenc-int column count.
                            val reader = MysqlBufferReader(firstPayload)
                            Abort.run[SqlException.Decode](reader.readLenencInt()).flatMap {
                                case Result.Success(Maybe.Absent) =>
                                    Abort.fail(SqlException.Connection("Unexpected 0xFF sentinel in column count", summon[Frame]))
                                case Result.Success(Maybe.Present(columnCountLong)) =>
                                    val columnCount = columnCountLong.toInt
                                    ExtendedQueryExchange.readColumnDefs(channel, columnCount).flatMap { columnDefs =>
                                        ExtendedQueryExchange.skipEofIfNeeded(channel, columnCount, deprecateEof).flatMap { _ =>
                                            // Track whether the result-set terminator has been consumed.
                                            // If the stream is terminated early, the cleanup must drain remaining row packets
                                            // before sending COM_STMT_CLOSE, to leave the connection in a clean state.
                                            AtomicRef.init(false).flatMap { terminatorSeen =>
                                                val colTypes = columnDefs.map(_.columnType)
                                                val cleanupEffect: Unit < (Async & Abort[SqlException]) =
                                                    terminatorSeen.get.flatMap { done =>
                                                        if done then
                                                            // Result set fully consumed — channel is clean. Just close the statement.
                                                            channel.resetSeq()
                                                            channel.send(ComStmtClose(stmt.stmtId))(using channel.marshallers.comStmtClose)
                                                        else
                                                            // Stream was terminated early — drain remaining result packets first,
                                                            // then send COM_STMT_CLOSE so the connection is ready for the next command.
                                                            drainResultSet(channel, colTypes, deprecateEof).flatMap { _ =>
                                                                channel.resetSeq()
                                                                channel.send(ComStmtClose(stmt.stmtId))(using
                                                                    channel.marshallers.comStmtClose
                                                                )
                                                            }
                                                        end if
                                                    }
                                                Scope.ensure(Abort.run(cleanupEffect).unit).andThen {
                                                    // Emit rows one by one using recursive Emit.
                                                    emitRows(
                                                        channel,
                                                        columnDefs,
                                                        colTypes,
                                                        deprecateEof,
                                                        terminatorSeen,
                                                        Present(sql),
                                                        params.size,
                                                        connectionId
                                                    )
                                                }
                                            }
                                        }
                                    }
                                case Result.Failure(e) =>
                                    Abort.fail(SqlException.Connection(s"Column count decode failed: ${e.message}", summon[Frame]))
                                case Result.Panic(t) =>
                                    java.lang.System.err.println(s"[kyo-sql] StreamQueryExchange: column count panic: ${t.getMessage}")
                                    Abort.fail(SqlException.Connection(s"Column count decode panic: ${t.getMessage}", summon[Frame]))
                            }
                        end if
                    }
                }
            }
    end stream

    // --- Row streaming ---

    /** Recursively reads binary rows and emits each one until the terminator packet. */
    private def emitRows(
        channel: MysqlChannel,
        columnDefs: Chunk[ColumnDefinition41],
        colTypes: Chunk[Int],
        deprecateEof: Boolean,
        terminatorSeen: AtomicRef[Boolean],
        sqlText: Maybe[String],
        paramCount: Int,
        connectionId: Maybe[Long]
    )(using Frame): Unit < (Emit[Chunk[MysqlRow]] & Async & Abort[SqlException]) =
        channel.readRawPayload.flatMap { payload =>
            val firstByte = payload(0) & 0xff
            if firstByte == 0xfe && payload.size < 9 then
                // EOF / OK terminator — end of stream. Mark done so cleanup skips drain.
                terminatorSeen.set(true)
            else if firstByte == 0xff then
                readErrAndFail(payload, sqlText, paramCount, connectionId)
            else if firstByte == 0x00 then
                // Binary result row (0x00 header byte).
                val unmarshaller = BinaryResultsetRowUnmarshaller(colTypes.size, colTypes)
                val reader       = MysqlBufferReader(payload.slice(1, payload.size))
                Abort.run[SqlException.Decode](unmarshaller.read(reader)).flatMap {
                    case Result.Success(row) =>
                        val mysqlRow = new MysqlRow(row.values, columnDefs)
                        Emit.valueWith(Chunk(mysqlRow))(emitRows(
                            channel,
                            columnDefs,
                            colTypes,
                            deprecateEof,
                            terminatorSeen,
                            sqlText,
                            paramCount,
                            connectionId
                        ))
                    case Result.Failure(e) =>
                        Abort.fail(SqlException.Connection(s"BinaryResultsetRow decode failed: ${e.message}", summon[Frame]))
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] StreamQueryExchange: binary row panic: ${t.getMessage}")
                        Abort.fail(SqlException.Connection(s"Binary row decode panic: ${t.getMessage}", summon[Frame]))
                }
            else
                Abort.fail(SqlException.Connection(
                    s"Unexpected first byte in binary stream: 0x${firstByte.toHexString}",
                    summon[Frame]
                ))
            end if
        }
    end emitRows

    /** Drains remaining binary result row packets until the EOF/OK terminator.
      *
      * Called by the Scope cleanup when the stream was terminated early. Discards all remaining row packets so the connection is in a clean
      * state before COM_STMT_CLOSE is sent.
      */
    private def drainResultSet(
        channel: MysqlChannel,
        colTypes: Chunk[Int],
        deprecateEof: Boolean
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.readRawPayload.flatMap { payload =>
            val firstByte = payload(0) & 0xff
            if firstByte == 0xfe && payload.size < 9 then
                // EOF / OK terminator — drain complete.
                ()
            else if firstByte == 0xff then
                // ERR packet during drain — ignore and consider drain done.
                ()
            else if firstByte == 0x00 then
                // Binary result row — discard and continue draining.
                drainResultSet(channel, colTypes, deprecateEof)
            else
                // Unexpected packet type during drain — stop and let the next command deal with it.
                (
            )
            end if
        }
    end drainResultSet

    // --- Utilities ---

    private def sendExecute(
        channel: MysqlChannel,
        stmt: MysqlPreparedStmt,
        params: Chunk[BoundMysqlParam[?]]
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.resetSeq()
        val encodedParams = params.map(_.encoded)
        val paramTypes    = params.map(p => (p.mysqlType, p.unsigned))
        channel.send(
            ComStmtExecute(
                stmtId = stmt.stmtId,
                flags = 0, // no cursor
                params = encodedParams,
                paramTypes = paramTypes,
                newParamsBound = 1
            )
        )(using channel.marshallers.comStmtExecute)
    end sendExecute

    private def readErrAndFail[A](
        payload: Span[Byte],
        sqlText: Maybe[String],
        paramCount: Int,
        connectionId: Maybe[Long]
    )(using frame: Frame): A < (Async & Abort[SqlException]) =
        val reader = MysqlBufferReader(payload.slice(1, payload.size))
        Abort.run[SqlException.Decode](
            kyo.internal.mysql.unmarshaller.ErrPacketUnmarshaller.read(reader)
        ).flatMap {
            case Result.Success(err) =>
                Abort.fail(MysqlErrors.mkServerError(err, sqlText, paramCount, connectionId))
            case Result.Failure(e) =>
                Abort.fail(SqlException.Connection(s"ERR packet decode failed: ${e.message}", summon[Frame]))
            case Result.Panic(t) =>
                java.lang.System.err.println(s"[kyo-sql] StreamQueryExchange: ERR decode panic: ${t.getMessage}")
                Abort.fail(SqlException.Connection(s"ERR decode panic: ${t.getMessage}", summon[Frame]))
        }
    end readErrAndFail

end StreamQueryExchange
