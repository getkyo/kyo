package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlException
import kyo.internal.mysql.*

/** Streams binary-protocol rows from a prepared MySQL statement.
  *
  * Approach: per-row wire reads (no cursor). After [[ComStmtExecute]] (flags=0) the server streams all rows without requiring explicit
  * fetch commands. Rows are pulled from the wire one-at-a-time and emitted into a [[Stream]].
  *
  * Protocol sequence:
  *   1. Prepare statement (or use cached one from [[ExtendedQueryExchange.prepareStmt]]).
  *   2. Send [[ComStmtExecute]] (flags=0, no cursor).
  *   3. Read column-count packet + column definitions.
  *   4. For each [[BinaryResultsetRow]] packet: decode and emit.
  *   5. On OK/EOF terminator: close stream.
  *   6. On [[Scope]] exit: drain any remaining result packets so the connection is left on a clean boundary.
  *
  * Drain on early exit: when the stream is terminated early (e.g. via `.take(n)`), unread binary row packets may remain on the wire. The
  * Scope cleanup discards them until it sees the result-set terminator, so the next command starts on a clean boundary. If the stream was
  * fully consumed the terminator has already been seen and the drain is skipped.
  *
  * THE CLEANUP MUST NOT SEND [[ComStmtClose]]. The statement it would close does not belong to the stream:
  * [[ExtendedQueryExchange.prepareStmt]] returns the CACHED [[MysqlPreparedStmt]] on a hit, so the id is shared with every later use of the
  * same SQL on this connection. Closing it would leave the cache advertising a handle the server had already freed, so the next execute of
  * that SQL, stream or query alike, would fail with the server's unknown-handle error. Ownership sits with the cache, so the only
  * server-side close is the one [[MysqlConnection.drainPendingCloses]] performs for an entry the cache has evicted.
  *
  * The Postgres twin is the same invariant reached through a different protocol. Its stream closes a per-execution PORTAL under a generated
  * name and leaves the cached statement alone. MySQL's binary protocol has no portal, so the prepared statement IS the only server-side
  * object and it is shared; the analogue of closing the portal is closing nothing.
  *
  * Evicting the cache entry alongside the close is not an alternative either. `MysqlConnection.mkStmtCache` wires `onRemove` to the same
  * callback as `onEvict`, so an explicit removal would enqueue the id into `pendingCloses` and send a second `COM_STMT_CLOSE` for a handle
  * this cleanup had already freed.
  *
  * Reference: MySQL Internals, Prepared Statements, Binary Protocol
  */
private[mysql] object StreamQueryExchange:

    /** Returns a [[Stream]] of [[MysqlRow]] for the given parameterised `sql`.
      *
      * The stream's effect types include [[Scope]] so that cleanup (COM_STMT_CLOSE) is guaranteed on any exit path.
      *
      * `onCleanBoundary` runs when the Scope cleanup has the wire on a result-set terminator, on both of its edges: the set was fully
      * consumed, or the drain read the rest (killing the statement first when that was faster). It exists for the caller that tracks the
      * in-flight window: an interrupted stream's exit signal says nothing about the wire, so without this record the pool would have to
      * assume the worst about a session the drain just cleaned. It does NOT run when the drain itself failed, so the fail-safe answer
      * (quarantine, then destroy) is untouched for a wire that really is dirty.
      */
    def stream(
        channel: MysqlChannel,
        stmtCache: Cache[String, MysqlPreparedStmt],
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        deprecateEof: Boolean,
        connectionId: Maybe[Long],
        escalate: Unit < (Async & Abort[SqlException]),
        onCleanBoundary: Unit < Sync
    )(using Frame): Stream[MysqlRow, Async & Abort[SqlException] & Scope] =
        Stream:
            ExtendedQueryExchange.prepareStmt(channel, stmtCache, sql, deprecateEof).flatMap { stmt =>
                sendExecute(channel, stmt, params).flatMap { _ =>
                    // Read the first response packet (column count or OK/ERR).
                    channel.readRawPayload.flatMap { firstPayload =>
                        val firstByte = firstPayload(0) & 0xff
                        if firstByte == 0xff then
                            // ERR packet
                            ExtendedQueryExchange.readErrPayload(firstPayload).flatMap(err =>
                                Abort.fail(MysqlErrors.mkServerError(err, Present(sql), params.size, connectionId))
                            )
                        else if firstByte == 0x00 && firstPayload.size >= 7 then
                            // OK packet (DML, no result set). Empty stream.
                            ()
                        else
                            // Result set: firstPayload carries the lenenc-int column count.
                            val reader = MysqlBufferReader(firstPayload)
                            ProtocolDecode.decode("column count", reader.readLenencInt()).flatMap { columnCountLong =>
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
                                                        // Result set fully consumed, so the wire is already on a clean boundary. The
                                                        // statement stays in the cache for the next caller; see the note above on why
                                                        // this path must not close it.
                                                        ()
                                                    else
                                                        // Stream was terminated early, so unread row packets are still queued. Drain
                                                        // them so the next command starts on a clean boundary, escalating to a
                                                        // server-side kill if the set is large enough that draining it would be a wait.
                                                        drainResultSet(channel, escalate)
                                                    end if
                                                }.andThen(
                                                    // Both branches end on a result-set terminator, and only this cleanup knows that:
                                                    // the in-flight settle keys on the EXIT's error shape, and on an interrupt that
                                                    // shape says nothing about the wire. Recording the boundary here is what lets the
                                                    // pool pool an interrupted stream's session instead of destroying it. Skipped by
                                                    // `Abort.run` when the drain itself failed, which leaves the flag raised and the
                                                    // session quarantined, the fail-safe this record must never weaken.
                                                    onCleanBoundary
                                                )
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
            if firstByte == 0xfe then
                // Result-set terminator, end of stream. Mark done so cleanup skips the drain.
                //
                // The size is not tested: a binary row always carries a 0x00 header, so 0xfe here can only be the
                // terminator, and under CLIENT_DEPRECATE_EOF that OK packet exceeds 7 bytes whenever it carries
                // warnings, an info string or a session-state block. See `ExtendedQueryExchange.readBinaryRows`.
                terminatorSeen.set(true)
            else if firstByte == 0xff then
                // An ERR packet terminates the result set: the server sends nothing after it. Recording it as the
                // terminator keeps the Scope cleanup from draining a wire that owes nothing; otherwise, with an
                // unbounded socketTimeout, that drain would park the finalizer chain on any mid-stream server error.
                terminatorSeen.set(true).andThen(
                    ExtendedQueryExchange.readErrPayload(payload).flatMap(err =>
                        Abort.fail(MysqlErrors.mkServerError(err, sqlText, paramCount, connectionId))
                    )
                )
            else if firstByte == 0x00 then
                // Binary result row (0x00 header byte). MySQL streaming path uses the same binary prepared-statement
                // wire format as `ExtendedQueryExchange.readBinaryRows`, hence the shared decode.
                ExtendedQueryExchange.readBinaryRow(payload, columnDefs, colTypes).flatMap { mysqlRow =>
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
                }
            else
                Abort.fail(SqlConnectionUnexpectedMessageException(
                    "binary stream",
                    "OK / ERR / row",
                    s"byte 0x${firstByte.toHexString}"
                ))
            end if
        }
    end emitRows

    /** How long the cleanup drain reads before it stops asking the server nicely and kills the statement.
      *
      * DENOMINATED IN ELAPSED TIME, and the choice is the substance of this mechanism rather than a tuning detail.
      *
      * The harm being bounded is the caller's WAIT: `.take(1)` over a large result set returns to the caller and then sits in this drain
      * while the server streams rows nobody will read. Rows drained and packets read are both proxies for that wait, and both come apart
      * from it in the direction that matters. A budget of N rows is unbounded in time as soon as the rows are wide, since one `BLOB` column
      * makes a hundred rows a long wait; and on narrow rows the same N fires on result sets that would have finished in microseconds, paying
      * for a sidecar connection on the common path. Time is the property, so time is what gets measured.
      *
      * The value is a constant rather than a `SqlConfig` field. A knob would be public surface, and there is no evidence yet about what a
      * caller would set it to; 250 milliseconds is far longer than any small result set needs to drain and far shorter than any wait a
      * caller would notice. Promote it to config when someone has a reason to change it, not before.
      *
      * WHAT THIS BUDGET CANNOT DO, stated because the gap is invisible from the call site. It is checked BETWEEN packet reads, so it bounds a
      * server that is actively streaming, which is exactly the early-termination case. It cannot preempt a read already parked on a silent
      * wire: `MysqlChannel` bounds each read by `socketTimeout`, whose default is `Duration.Infinity`, so a server that stops sending
      * mid-result-set parks in `readRawPayload` and never reaches this check. The mid-stream server error that produced that shape is already
      * handled upstream, where `emitRows` records an ERR packet as the terminator so this drain is never entered for it. A silent wire with no
      * ERR remains bounded only by `socketTimeout`.
      */
    private val DrainBudget: Duration = 250.millis

    /** Drains remaining binary result row packets until the EOF/OK terminator, escalating to a server-side kill if that takes too long.
      *
      * Called by the Scope cleanup when the stream was terminated early. Discards all remaining row packets so the connection is left on a
      * clean boundary for the next command.
      *
      * THE ESCALATION IS WHAT MAKES THIS SAFE TO BOUND AT ALL, and the two obvious designs are both wrong. Draining unconditionally is the
      * defect: early termination is the COMMON case for a stream, so `.take(1)` over a large table reads the whole table off the wire.
      * Cancelling unconditionally pays for a sidecar connection (TCP, handshake, auth) on every early termination, which for `.take(1)` over
      * three rows costs enormously more than draining two rows. A bare budget, the mechanism `CopyExchange` has next door, is worse than
      * either: abandoning the drain leaves unread packets on the wire, so the connection must then be destroyed,
      * which converts an unbounded WAIT into a discarded CONNECTION without ever stopping the server.
      *
      * Escalating keeps the connection. `escalate` sends `KILL QUERY` down a sidecar, the server aborts the statement and writes an ERR packet
      * into this result set, and the loop below sees `0xff` and finishes. So the wire still ends on a real terminator and the session stays
      * reusable, which is the property a bare budget gives up. `MysqlSqlConnection.drainToIdle` says a killed statement's response cannot be
      * read past and the connection has to go; that is about the pool reclaiming a connection nobody drained, and it does not apply here
      * precisely because this path does drain to the terminator afterwards.
      *
      * The escalation fires at most once. A second `KILL QUERY` would open a second sidecar to stop a statement already stopped.
      */
    private def drainResultSet(
        channel: MysqlChannel,
        escalate: Unit < (Async & Abort[SqlException])
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        Clock.stopwatch.flatMap { elapsedSince =>
            def loop(killed: Boolean)(using Frame): Unit < (Async & Abort[SqlException]) =
                channel.readRawPayload.flatMap { payload =>
                    val firstByte = payload(0) & 0xff
                    if firstByte == 0xfe then
                        // Result-set terminator, drain complete. Size untested for the same reason as `emitRows` above: a
                        // sized test would miss an oversized OK terminator and keep draining a wire that owes nothing.
                        ()
                    else if firstByte == 0xff then
                        // ERR packet, which terminates the result set. Reached either by a server-side error or by the
                        // kill this drain just sent, and both mean the same thing here: nothing follows, so stop.
                        ()
                    else if firstByte == 0x00 then
                        // Binary result row. Discard it, then decide whether to keep asking or to stop the server.
                        if killed then loop(true)
                        else
                            elapsedSince.elapsed.flatMap { spent =>
                                if spent < DrainBudget then loop(false)
                                else escalate.andThen(loop(true))
                            }
                        end if
                    else
                        // Unexpected packet type during drain, stop and let the next command deal with it.
                        (
                    )
                    end if
                }
            loop(false)
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
        val paramTypes    = params.map(p => (p.encoder.mysqlType, p.encoder.unsigned))
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

end StreamQueryExchange
