package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlConnectionClosedException
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlConnectionWritePanicException
import kyo.SqlException
import kyo.internal.postgres.*

/** Implements the PostgreSQL COPY sub-protocol for high-throughput bulk data transfer.
  *
  * ==COPY FROM STDIN (copyIn)==
  *
  * Wire sequence:
  *   1. Client sends [[Query]] with `COPY ... FROM STDIN`.
  *   2. Server responds with [[CopyInResponse]] (format and column metadata).
  *   3. Client sends 0..N [[CopyData]] packets, each carrying a chunk of the user-supplied data stream, checking between packets for a
  *      rejection the server has already sent.
  *   4. Client sends [[CopyDone]] to signal end of data.
  *   5. Server responds with [[CommandComplete]] (tag = "COPY N") then [[ReadyForQuery]].
  *
  * On error or cancellation: the client sends CopyFail instead of [[CopyDone]], causing the server to abort with an ErrorResponse
  * followed by ReadyForQuery.
  *
  * ==COPY TO STDOUT (copyOut)==
  *
  * Wire sequence:
  *   1. Client sends [[Query]] with `COPY ... TO STDOUT`.
  *   2. Server responds with [[CopyOutResponse]] (format and column metadata).
  *   3. Server sends 0..N [[CopyData]] packets, each carrying one or more rows.
  *   4. Server sends [[CopyDone]] to signal end of data.
  *   5. Server sends [[CommandComplete]] then [[ReadyForQuery]].
  *
  * Early cancellation: when the consumer closes the stream before [[CopyDone]], we send CopyFail and drain to [[ReadyForQuery]].
  *
  * ==Race-condition protection==
  *
  * Both directions register a [[PostgresChannel.PendingCopyCleanup]] on the channel for the transfer's duration (the latch pattern
  * mirrored from [[kyo.internal.mysql.exchange.LocalInfileExchange]], extended with an exactly-once claim) and resolve it at the protocol
  * barrier: at the completion drain's ReadyForQuery on the exchange's own paths, or through the bounded, uninterruptible ([[Async.mask]])
  * CopyFail-and-drain on every other edge. A caller reaching [[PostgresChannel.send]] or [[PostgresChannel.receive]] while the cleanup is
  * still pending either runs it (claim won) or waits for the claimant (claim lost), so it sees a clean connection or "unusable", never
  * stale protocol bytes and never an unbounded wait.
  *
  * The value-returning members of this family (copyIn here, `LOAD DATA LOCAL INFILE` on MySQL) get their resolution guaranteed a second
  * way: a tight `Scope.run` at the call site, so the finalizer dies with the call. copyOut cannot be wrapped that way, because it returns
  * a [[kyo.Stream]] whose finalizer must outlive the call by design, and `Stream.take` drops the continuation that would close an inner
  * scope. That is the invariant for any future COPY-family operation returning a Stream: resolve at the wire barrier on the happy path,
  * never only in the finalizer, or an abandoned (or even fully consumed) transfer holds the cleanup latch until the enclosing scope
  * closes, and on a pinned session the next statement, a transaction's COMMIT included, waits on that latch forever.
  */
private[postgres] object CopyExchange:

    /** Maximum bytes per [[CopyData]] packet sent to the server (64 KB, a practical sweet-spot well under the 1 GB theoretical max). */
    private val MaxChunkBytes: Int = 64 * 1024

    // --- copyIn ---

    /** Executes `COPY ... FROM STDIN` and streams `data` to the server.
      *
      * Sends the SQL as a simple Query, awaits [[CopyInResponse]], then pumps [[CopyData]] packets from `data`, and finally sends
      * [[CopyDone]]. Returns the affected-row count from [[CommandComplete]].
      *
      * The pump checks for a rejection the server has already sent before each chunk, without ever waiting for one, so a COPY the server
      * refuses fails at the next chunk boundary rather than after the whole stream has been uploaded.
      *
      * On error or cancellation the cleanup path sends CopyFail and drains to [[ReadyForQuery]]. The cleanup runs uninterruptibly
      * ([[Async.mask]]) with a budget of `cleanupTimeout` to ensure the protocol is always left in a defined state.
      *
      * @param channel
      *   the exclusive Postgres channel for this operation
      * @param sql
      *   the `COPY ... FROM STDIN` SQL statement
      * @param data
      *   the byte stream to send as COPY data; each of the stream's chunks becomes one [[CopyData]] packet (split to [[MaxChunkBytes]] if
      *   needed)
      * @return
      *   the number of rows loaded by the server (from the "COPY N" command tag)
      */
    def copyIn[S](
        channel: PostgresChannel,
        sql: String,
        pid: Long,
        data: Stream[Byte, S],
        cleanupTimeout: Duration
    )(using Frame): Long < (Async & Abort[SqlException] & Scope & S) =
        pendingCleanup(channel, "COPY FROM STDIN aborted by client", "copyIn", cleanupTimeout).flatMap { pending =>
            channel.beginCleanup(pending).andThen {
                // The finalizer backstops the edges the exchange body cannot reach: an interrupt, a panic, and
                // a typed failure the caller's own data stream smuggled through `S`. The claim, not the scope's
                // exit report, says whether anything is still owed: the body resolves at the ReadyForQuery
                // barrier on both the success and server-error paths, and a finalizer that finds the claim
                // taken has nothing left to do.
                Scope.ensure(runIfUnclaimed(pending)).andThen {
                    // --- Happy path ---
                    // ALL channel operations here bypass checkCorrupted() via sendSkipCheck / receiveSkipCheck.
                    // Rationale: beginCleanup has already registered the pending cleanup on the channel.
                    // channel.send / channel.receive both call checkCorrupted(), which would claim the
                    // cleanup we still own and cut across the live transfer. We are the sole reader/writer
                    // during a COPY op, so the skip-check variants are safe.

                    // 1. Send the COPY SQL as a simple Query.
                    channel.sendSkipCheck(Query(sql))(using channel.marshallers.query).flatMap { _ =>
                        // 2. Await CopyInResponse (skipping ParameterStatus / NoticeResponse).
                        awaitCopyInResponse(channel, pid, pending).flatMap { _ =>
                            // 3. Pump CopyData packets from the user stream, one packet per stream chunk.
                            // Chunk-level rather than per-byte: a byte stream's chunks are already the
                            // transfer unit the source produced, and pulling one element at a time would
                            // cost a suspension per byte. Each chunk is preceded by a non-blocking check
                            // for a server-side rejection, so the upload stops at the next chunk boundary
                            // rather than at end of stream.
                            data.foreachChunk(chunk =>
                                checkForServerRejection(channel, pid, pending).andThen(
                                    sendCopyDataChunked(channel, Span.from(chunk))
                                )
                            ).flatMap { _ =>
                                // 4. Send CopyDone (write raw bytes, bypassing cleanup-latch check).
                                sendCopyDone(channel).flatMap { _ =>
                                    // 5. Read CommandComplete + ReadyForQuery (skip-check because the cleanup
                                    //    latch is registered for the duration of this operation), resolving the
                                    //    pending cleanup at that barrier.
                                    readCopyInCompletionSkipCheck(channel, pid, pending)
                                }
                            }
                        }
                    }
                }
            }
        }
    end copyIn

    // --- copyOut ---

    /** Executes `COPY ... TO STDOUT` and returns a lazy stream of raw data chunks.
      *
      * The returned [[Stream]] has [[Scope]] in its effect type, and the pending cleanup resolves at the earliest of two points. The
      * exchange itself resolves at the ReadyForQuery barrier once [[CopyDone]] and the completion drain have been consumed, so a fully
      * read stream leaves nothing registered: the latch dies with the transfer, never with the enclosing scope, which on a pinned session
      * closes only after the next statement the release unblocks. The [[Scope]] finalizer backstops every other exit (a typed failure
      * from either side, an interrupt, a consumer that stops pulling) by sending CopyFail and draining to [[ReadyForQuery]].
      *
      * The cleanup runs uninterruptibly ([[Async.mask]]) with a budget of `cleanupTimeout` to ensure the protocol is always left in a
      * defined state.
      *
      * @param channel
      *   the exclusive Postgres channel for this operation
      * @param sql
      *   the `COPY ... TO STDOUT` SQL statement
      * @return
      *   a [[Stream]] of the raw COPY data bytes, one chunk per [[CopyData]] message from the server; the stream ends when the server sends
      *   [[CopyDone]]
      */
    def copyOut(
        channel: PostgresChannel,
        sql: String,
        pid: Long,
        cleanupTimeout: Duration
    )(using Frame): Stream[Byte, Async & Abort[SqlException] & Scope] =
        Stream:
            pendingCleanup(channel, "COPY TO STDOUT stream closed by client", "copyOut", cleanupTimeout).flatMap { pending =>
                channel.beginCleanup(pending).andThen {
                    // The finalizer backstops what the body cannot reach: an interrupt, a panic, a typed
                    // failure the consumer raised between chunks, and a truncated consumer. Stream.take drops
                    // the continuation holding the rest of this body, so on that edge the scope's own exit
                    // report says Absent while the transfer sits abandoned mid-flood; the claim, not the exit
                    // report, is what distinguishes a completed transfer from an abandoned one.
                    Scope.ensure(runIfUnclaimed(pending)).andThen {
                        // --- Happy path ---
                        // ALL channel operations here bypass checkCorrupted() via sendSkipCheck / receiveSkipCheck.
                        // Rationale: beginCleanup has already registered the pending cleanup on the channel.
                        // channel.send / channel.receive both call checkCorrupted(), which would claim the
                        // cleanup we still own and cut across the live transfer. We are the sole reader/writer
                        // during a COPY op, so the skip-check variants are safe.

                        // 1. Send the COPY SQL as a simple Query.
                        channel.sendSkipCheck(Query(sql))(using channel.marshallers.query).flatMap { _ =>
                            // 2. Await CopyOutResponse.
                            awaitCopyOutResponse(channel, pid, pending).flatMap { _ =>
                                // 3. Stream CopyData packets until CopyDone, then drain CommandComplete + RFQ
                                //    and resolve the pending cleanup at that barrier.
                                streamCopyOutData(channel, pid, pending)
                            }
                        }
                    }
                }
            }

    // --- Private helpers ---

    /** Reads messages until [[CopyInResponse]], skipping [[ParameterStatus]] and [[NoticeResponse]].
      *
      * Uses [[PostgresChannel.receiveSkipCheck]] because the pending cleanup is registered for the duration of the COPY operation;
      * [[PostgresChannel.receive]] would claim the cleanup we still own and cut across the live transfer.
      */
    private def awaitCopyInResponse(channel: PostgresChannel, pid: Long, pending: PostgresChannel.PendingCopyCleanup)(using
        Frame
    ): CopyInResponse < (Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case r: CopyInResponse       => r
            case ParameterStatus(_, _)   => awaitCopyInResponse(channel, pid, pending)
            case NoticeResponse(_)       => awaitCopyInResponse(channel, pid, pending)
            case _: NotificationResponse => awaitCopyInResponse(channel, pid, pending)
            case ErrorResponse(fields)   =>
                // The drain just reached ReadyForQuery, so the cleanup resolves here: sending CopyFail into an
                // idle session from the finalizer would wait out the whole budget for a reply that never comes.
                drainToReadyForQuerySkipCheck(channel).andThen(resolveAtBarrier(channel, pending)).andThen(
                    Abort.fail(ServerErrors.mkServerError(
                        fields,
                        Absent,
                        0,
                        Present(pid)
                    ))
                )
            case other =>
                // Deliberately left unresolved: the wire state is unknown, so the finalizer's CopyFail and
                // drain decide between resynchronising and marking the channel corrupted.
                Abort.fail(SqlConnectionUnexpectedMessageException("COPY FROM STDIN negotiation", "CopyInResponse", other.toString))
        }

    /** Reads messages until [[CopyOutResponse]], skipping [[ParameterStatus]] and [[NoticeResponse]].
      *
      * Uses [[PostgresChannel.receiveSkipCheck]] because the pending cleanup is registered for the duration of the COPY operation;
      * [[PostgresChannel.receive]] would claim the cleanup we still own and cut across the live transfer.
      */
    private def awaitCopyOutResponse(channel: PostgresChannel, pid: Long, pending: PostgresChannel.PendingCopyCleanup)(using
        Frame
    ): CopyOutResponse < (Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case r: CopyOutResponse      => r
            case ParameterStatus(_, _)   => awaitCopyOutResponse(channel, pid, pending)
            case NoticeResponse(_)       => awaitCopyOutResponse(channel, pid, pending)
            case _: NotificationResponse => awaitCopyOutResponse(channel, pid, pending)
            case ErrorResponse(fields)   =>
                // See awaitCopyInResponse: the wire is at ReadyForQuery, so the cleanup resolves here.
                drainToReadyForQuerySkipCheck(channel).andThen(resolveAtBarrier(channel, pending)).andThen(
                    Abort.fail(ServerErrors.mkServerError(
                        fields,
                        Absent,
                        0,
                        Present(pid)
                    ))
                )
            case other =>
                // Deliberately left unresolved: the wire state is unknown, so the finalizer decides.
                Abort.fail(SqlConnectionUnexpectedMessageException("COPY TO STDOUT negotiation", "CopyOutResponse", other.toString))
        }

    /** Consumes whatever the server has already sent mid-upload, so a rejected COPY fails at the next chunk boundary rather than after the
      * whole stream has been pushed.
      *
      * Without this the first inbound read of a `COPY FROM STDIN` after the negotiation is the completion read, which runs only once the
      * user's stream is exhausted, so a table that rejects the very first row still costs the entire upload. Nothing on the wire stalls
      * while that happens: after an ErrorResponse in copy-in mode the backend drops the CopyData, CopyDone and CopyFail that follow, and
      * dropping them means it keeps reading, so backpressure never latches. What the blind pump costs is the upload and a late error.
      *
      * The check never waits for the server ([[PostgresChannel.receiveSkipCheckIfAvailable]]), so a transfer the server has said nothing
      * about costs one poll per chunk. On ErrorResponse it drains to ReadyForQuery and resolves at that barrier, exactly as
      * [[readCopyInCompletionSkipCheck]] does: the typed failure short-circuits the pump's fold so no CopyDone follows, and the finalizer
      * finds the claim already taken so no CopyFail goes into an idle session. The asynchronous messages are skipped and the poll repeats,
      * because an ErrorResponse can be sitting right behind one.
      */
    private def checkForServerRejection(channel: PostgresChannel, pid: Long, pending: PostgresChannel.PendingCopyCleanup)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        channel.receiveSkipCheckIfAvailable.flatMap {
            case Maybe.Absent => ()
            case Maybe.Present(msg) =>
                msg match
                    case ErrorResponse(fields) =>
                        drainToReadyForQuerySkipCheck(channel).andThen(resolveAtBarrier(channel, pending)).andThen(
                            Abort.fail(ServerErrors.mkServerError(
                                fields,
                                Absent,
                                0,
                                Present(pid)
                            ))
                        )
                    case NoticeResponse(_)       => checkForServerRejection(channel, pid, pending)
                    case ParameterStatus(_, _)   => checkForServerRejection(channel, pid, pending)
                    case _: NotificationResponse => checkForServerRejection(channel, pid, pending)
                    case other                   =>
                        // Deliberately left unresolved: the wire state is unknown, so the finalizer's CopyFail and
                        // drain decide between resynchronising and marking the channel corrupted.
                        Abort.fail(SqlConnectionUnexpectedMessageException("COPY FROM STDIN upload", "ErrorResponse", other.toString))
        }

    /** Sends a [[Span[Byte]]] to the server as a CopyData packet, splitting into [[MaxChunkBytes]]-sized chunks.
      *
      * Writes raw bytes directly to the connection (bypassing checkCorrupted) because the pending cleanup is registered on this channel
      * for the entire duration of the COPY operation. Going through [[PostgresChannel.send]] would claim the cleanup we still own and cut
      * across the live transfer.
      */
    private def sendCopyDataChunked(channel: PostgresChannel, data: Span[Byte])(using Frame): Unit < (Async & Abort[SqlException]) =
        if data.isEmpty then ()
        else if data.size <= MaxChunkBytes then
            writeCopyDataRaw(channel, data)
        else
            // Split and recurse.
            writeCopyDataRaw(channel, data.slice(0, MaxChunkBytes)).flatMap { _ =>
                sendCopyDataChunked(channel, data.slice(MaxChunkBytes, data.size))
            }
        end if
    end sendCopyDataChunked

    /** Writes one CopyData wire frame directly to the underlying connection. */
    private def writeCopyDataRaw(channel: PostgresChannel, data: Span[Byte])(using Frame): Unit < (Async & Abort[SqlException]) =
        val buf = new PostgresBufferWriter
        buf.writeByte('d'.toByte)
        buf.writeInt32(4 + data.size) // len = 4-byte length field + payload
        buf.writeBytes(data)
        Abort.run[Closed](channel.conn.outbound.safe.put(buf.toSpan)).flatMap {
            case Result.Success(_) => ()
            case Result.Failure(_) => Abort.fail(SqlConnectionClosedException("writing (CopyData)"))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] CopyExchange: CopyData write panic: ${t.getMessage}").andThen(
                    Abort.fail(SqlConnectionWritePanicException(t))
                )
        }
    end writeCopyDataRaw

    /** Sends [[CopyDone]] bypassing the cleanup-latch check (used on the success path). */
    private def sendCopyDone(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        val buf = new PostgresBufferWriter
        buf.writeByte('c'.toByte)
        buf.writeInt32(4)
        Abort.run[Closed](channel.conn.outbound.safe.put(buf.toSpan)).flatMap {
            case Result.Success(_) => ()
            case Result.Failure(_) => Abort.fail(SqlConnectionClosedException("writing (CopyDone)"))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] CopyExchange: CopyDone write panic: ${t.getMessage}").andThen(
                    Abort.fail(SqlConnectionWritePanicException(t))
                )
        }
    end sendCopyDone

    /** Sends CopyFail bypassing the cleanup-latch check (used in cleanup paths). */
    private def sendCopyFail(channel: PostgresChannel, reason: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        val buf = new PostgresBufferWriter
        buf.framed('f'.toByte) {
            buf.writeString(reason) // NUL-terminated
        }
        Abort.run[Closed](channel.conn.outbound.safe.put(buf.toSpan)).flatMap {
            case Result.Success(_) => ()
            case Result.Failure(_) => Abort.fail(SqlConnectionClosedException("writing (CopyFail)"))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] CopyExchange: CopyFail write panic: ${t.getMessage}").andThen(
                    Abort.fail(SqlConnectionWritePanicException(t))
                )
        }
    end sendCopyFail

    /** Reads CommandComplete + ReadyForQuery after CopyDone, bypassing the cleanup-latch check, and resolves the pending cleanup at that
      * barrier.
      *
      * The pending cleanup gates the channel for the duration of the COPY operation. On the success path, we still need to drain the
      * server's CommandComplete + ReadyForQuery before resolving it. We use [[PostgresChannel.receiveSkipCheck]] to bypass the gate and
      * read directly from the TCP stream (safe because we are the sole reader during this operation).
      *
      * @return
      *   the affected-row count parsed from the CommandComplete tag (e.g. "COPY 1000" → 1000L)
      */
    private def readCopyInCompletionSkipCheck(channel: PostgresChannel, pid: Long, pending: PostgresChannel.PendingCopyCleanup)(using
        Frame
    ): Long < (Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case CommandComplete(tag) =>
                // Parse the row count from "COPY N"; drain ReadyForQuery, then resolve at the barrier.
                drainToReadyForQuerySkipCheck(channel).andThen(resolveAtBarrier(channel, pending)).andThen(parseCopyTag(tag))

            case NoticeResponse(_) =>
                readCopyInCompletionSkipCheck(channel, pid, pending)

            case ParameterStatus(_, _) =>
                readCopyInCompletionSkipCheck(channel, pid, pending)

            case _: NotificationResponse =>
                readCopyInCompletionSkipCheck(channel, pid, pending)

            case ErrorResponse(fields) =>
                // See awaitCopyInResponse: the wire is at ReadyForQuery, so the cleanup resolves here.
                drainToReadyForQuerySkipCheck(channel).andThen(resolveAtBarrier(channel, pending)).andThen(
                    Abort.fail(ServerErrors.mkServerError(
                        fields,
                        Absent,
                        0,
                        Present(pid)
                    ))
                )

            case _: ReadyForQuery =>
                // Shouldn't happen before CommandComplete, but handle gracefully: the wire is at a barrier.
                resolveAtBarrier(channel, pending).andThen(0L)

            case other =>
                // Deliberately left unresolved: the wire state is unknown, so the finalizer decides.
                Abort.fail(SqlConnectionUnexpectedMessageException("COPY completion", "CommandComplete", other.toString))
        }

    /** Drains to ReadyForQuery (inclusive) using receiveSkipCheck. */
    private def drainToReadyForQuerySkipCheck(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case _: ReadyForQuery => ()
            case _                => drainToReadyForQuerySkipCheck(channel)
        }

    /** Streams [[CopyData]] payloads from the server until [[CopyDone]], then drains [[CommandComplete]] + [[ReadyForQuery]] and resolves
      * the pending cleanup at that barrier.
      *
      * Used on the copyOut happy path.
      */
    private def streamCopyOutData(
        channel: PostgresChannel,
        pid: Long,
        pending: PostgresChannel.PendingCopyCleanup
    )(using Frame): Unit < (Emit[Chunk[Byte]] & Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case CopyData(data) =>
                // One CopyData payload becomes one stream chunk, so a consumer reading chunk-wise sees the
                // server's own framing. `toArrayUnsafe` hands over the payload without a second copy; the
                // Chunk built from it owns its own array, so nothing aliases the wire buffer.
                Emit.valueWith(Chunk.from(data.toArrayUnsafe))(streamCopyOutData(channel, pid, pending))

            case CopyDone =>
                // Server finished sending data. Drain CommandComplete + ReadyForQuery and resolve there.
                drainCopyOutCompletionSkipCheck(channel, pid, pending)

            case NoticeResponse(_) =>
                streamCopyOutData(channel, pid, pending)

            case ParameterStatus(_, _) =>
                streamCopyOutData(channel, pid, pending)

            case _: NotificationResponse =>
                streamCopyOutData(channel, pid, pending)

            case ErrorResponse(fields) =>
                // See awaitCopyInResponse: the wire is at ReadyForQuery, so the cleanup resolves here.
                drainToReadyForQuerySkipCheck(channel).andThen(resolveAtBarrier(channel, pending)).andThen(
                    Abort.fail(ServerErrors.mkServerError(
                        fields,
                        Absent,
                        0,
                        Present(pid)
                    ))
                )

            case other =>
                // Deliberately left unresolved: the wire state is unknown, so the finalizer decides.
                Abort.fail(SqlConnectionUnexpectedMessageException("COPY TO STDOUT stream", "CopyData / CopyDone", other.toString))
        }

    /** Drains [[CommandComplete]] and [[ReadyForQuery]] after [[CopyDone]] on the copyOut path, resolving the pending cleanup once
      * [[ReadyForQuery]] arrives.
      *
      * [[NotificationResponse]] is skipped like every other asynchronous message: a listening session's server defers notification
      * delivery to end-of-statement, which lands it exactly in this window, and failing the transfer for it would fail a COPY that
      * succeeded.
      */
    private def drainCopyOutCompletionSkipCheck(channel: PostgresChannel, pid: Long, pending: PostgresChannel.PendingCopyCleanup)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case _: ReadyForQuery =>
                // The transfer is complete and the wire is idle: the latch dies with the operation rather than
                // with the enclosing scope. On a pinned session that scope is the transaction's lease, whose
                // close waits on the COMMIT this release is what allows.
                resolveAtBarrier(channel, pending)
            case CommandComplete(_)      => drainCopyOutCompletionSkipCheck(channel, pid, pending)
            case NoticeResponse(_)       => drainCopyOutCompletionSkipCheck(channel, pid, pending)
            case ParameterStatus(_, _)   => drainCopyOutCompletionSkipCheck(channel, pid, pending)
            case _: NotificationResponse => drainCopyOutCompletionSkipCheck(channel, pid, pending)
            case ErrorResponse(fields)   =>
                // See awaitCopyInResponse: the wire is at ReadyForQuery, so the cleanup resolves here.
                drainToReadyForQuerySkipCheck(channel).andThen(resolveAtBarrier(channel, pending)).andThen(
                    Abort.fail(ServerErrors.mkServerError(
                        fields,
                        Absent,
                        0,
                        Present(pid)
                    ))
                )
            case other =>
                // Deliberately left unresolved: the wire state is unknown, so the finalizer decides.
                Abort.fail(SqlConnectionUnexpectedMessageException("after CopyDone", "CommandComplete / ReadyForQuery", other.toString))
        }

    // --- Cleanup lifecycle ---

    /** Builds the cleanup record one COPY registers on its channel: the latch followers wait on, the exactly-once claim, and the masked,
      * budgeted abort cleanup a claimant runs. The exactly-once claim is what lets three parties share one resolution safely: the
      * exchange at its barrier, the Scope finalizer, and the next writer arriving at the channel.
      */
    private def pendingCleanup(
        channel: PostgresChannel,
        reason: String,
        label: String,
        cleanupTimeout: Duration
    )(using Frame): PostgresChannel.PendingCopyCleanup < Sync =
        Latch.init(1).flatMap { latch =>
            AtomicBoolean.init(false).map { resolved =>
                PostgresChannel.PendingCopyCleanup(latch, resolved, abortCleanup(channel, latch, reason, label, cleanupTimeout))
            }
        }

    /** Runs the abort cleanup if this caller wins the claim.
      *
      * A lost claim means the exchange resolved at its barrier, or another party is already cleaning, and there is nothing left to do
      * here. Keying on the claim rather than on the scope's exit report is load-bearing twice over: a truncated stream exits its scope
      * with no error while the transfer sits abandoned mid-flood, and a finalizer that outlives a completed transfer (a pinned session's
      * lease closing long after the COPY) must not fire CopyFail into a session that has since moved on.
      */
    private def runIfUnclaimed(pending: PostgresChannel.PendingCopyCleanup)(using Frame): Unit < Async =
        pending.claim.flatMap {
            case true  => pending.abort
            case false => ()
        }

    /** Resolves the pending cleanup at a [[ReadyForQuery]] barrier: the wire is idle, so nothing is owed and the latch dies with the
      * operation rather than with the enclosing scope.
      */
    private def resolveAtBarrier(channel: PostgresChannel, pending: PostgresChannel.PendingCopyCleanup)(using Frame): Unit < Sync =
        pending.claim.flatMap {
            case true  => channel.endCleanup().andThen(pending.latch.release)
            case false => ()
        }

    /** The abort-path cleanup: CopyFail, drain to [[ReadyForQuery]], resolve the latch, all uninterruptible and bounded by
      * `cleanupTimeout`. Stored on the channel so whichever party claims the pending cleanup (the Scope finalizer, or the next writer
      * arriving at [[PostgresChannel.send]] or [[PostgresChannel.receive]]) runs the same code.
      */
    private def abortCleanup(
        channel: PostgresChannel,
        latch: Latch,
        reason: String,
        label: String,
        cleanupTimeout: Duration
    )(using Frame): Unit < Async =
        Async.mask {
            Abort.run[Timeout](
                Async.timeout(cleanupTimeout) {
                    Abort.run[SqlException](
                        sendCopyFail(channel, reason).flatMap { _ =>
                            drainToReadyForQuerySkipCheck(channel)
                        }
                    ).flatMap {
                        case Result.Success(_) =>
                            channel.endCleanup().andThen(latch.release)
                        case Result.Failure(_) =>
                            corruptAndRelease(channel, latch)
                        case Result.Panic(t) =>
                            Log.error(s"[kyo-sql] CopyExchange.$label: cleanup panic: ${t.getMessage}").andThen(
                                corruptAndRelease(channel, latch)
                            )
                    }
                }
            ).flatMap {
                case Result.Success(_) => ()
                case Result.Failure(_) =>
                    // Cleanup budget expired mid-drain.
                    corruptAndRelease(channel, latch)
                case Result.Panic(t) =>
                    Log.error(s"[kyo-sql] CopyExchange.$label: cleanup timeout-wrapper panic: ${t.getMessage}").andThen(
                        corruptAndRelease(channel, latch)
                    )
            }
        }

    /** Marks the channel corrupted, closes the socket, and releases the latch.
      *
      * The close is what keeps a corrupted connection out of the pool: nothing ever un-corrupts a channel, so its only future was failing
      * its next borrower's first statement, and a closed socket is evicted by the pool's own liveness check instead.
      */
    private def corruptAndRelease(channel: PostgresChannel, latch: Latch)(using Frame): Unit < Sync =
        channel.markCorrupted().andThen {
            // Unsafe: closing the raw connection without suspending; the channel is already unusable and this
            // runs inside the masked cleanup, where the only remaining job is to not leak the descriptor into
            // the pool.
            Sync.Unsafe.defer(channel.conn.close())
        }.andThen(channel.endCleanup()).andThen(latch.release)

    /** Parses the affected-row count from a COPY command tag (e.g. "COPY 1000" → 1000L). */
    private def parseCopyTag(tag: String): Long =
        val parts = tag.split(' ')
        if parts.length >= 2 then parts.last.toLongOption.getOrElse(0L)
        else 0L
    end parseCopyTag

end CopyExchange
