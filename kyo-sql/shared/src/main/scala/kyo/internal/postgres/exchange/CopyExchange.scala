package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.*

/** Implements the PostgreSQL COPY sub-protocol for high-throughput bulk data transfer.
  *
  * ==COPY FROM STDIN (copyIn)==
  *
  * Wire sequence:
  *   1. Client sends [[Query]] with `COPY ... FROM STDIN`.
  *   2. Server responds with [[CopyInResponse]] (format and column metadata).
  *   3. Client sends 0..N [[CopyData]] packets, each carrying a chunk of the user-supplied data stream.
  *   4. Client sends [[CopyDone]] to signal end of data.
  *   5. Server responds with [[CommandComplete]] (tag = "COPY N") then [[ReadyForQuery]].
  *
  * On error or cancellation: the client sends [[CopyFail]] instead of [[CopyDone]], causing the server to abort with an ErrorResponse
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
  * Early cancellation: when the consumer closes the stream before [[CopyDone]], we send [[CopyFail]] and drain to [[ReadyForQuery]].
  *
  * ==Race-condition protection==
  *
  * Both directions use the [[PostgresChannel.beginCleanup]] / [[PostgresChannel.endCleanup]] / [[PostgresChannel.markCorrupted]] pattern
  * (mirrored from [[kyo.internal.mysql.exchange.LocalInfileExchange]]) plus [[Async.mask]] with a bounded 5-second budget so that the
  * cleanup cannot be interrupted and callers see either a clean connection or "unusable" — never stale protocol bytes.
  */
private[postgres] object CopyExchange:

    /** Maximum bytes per [[CopyData]] packet sent to the server (64 KB — a practical sweet-spot well under the 1 GB theoretical max). */
    private val MaxChunkBytes: Int = 64 * 1024

    // --- copyIn ---

    /** Executes `COPY ... FROM STDIN` and streams `data` to the server.
      *
      * Sends the SQL as a simple Query, awaits [[CopyInResponse]], then pumps [[CopyData]] packets from `data`, and finally sends
      * [[CopyDone]]. Returns the affected-row count from [[CommandComplete]].
      *
      * On error or cancellation the cleanup path sends [[CopyFail]] and drains to [[ReadyForQuery]]. The cleanup runs uninterruptibly
      * ([[Async.mask]]) with a bounded 5-second budget to ensure the protocol is always left in a defined state.
      *
      * @param channel
      *   the exclusive Postgres channel for this operation
      * @param sql
      *   the `COPY ... FROM STDIN` SQL statement
      * @param data
      *   the byte stream to send as COPY data; each [[Span[Byte]]] element becomes one [[CopyData]] packet (split to [[MaxChunkBytes]] if
      *   needed)
      * @return
      *   the number of rows loaded by the server (from the "COPY N" command tag)
      */
    def copyIn[S](
        channel: PostgresChannel,
        sql: String,
        pid: Long,
        data: Stream[Span[Byte], S],
        cleanupTimeout: Duration
    )(using Frame): Long < (Async & Abort[SqlException] & Scope & S) =
        Latch.init(1).flatMap { latch =>
            channel.beginCleanup(latch).andThen {
                // Register a cleanup finalizer that fires on error exit (including timeout/interrupt).
                // On error: send CopyFail + drain ReadyForQuery, inside Async.mask with bounded budget.
                // On success: CopyDone + drain was already handled on the happy path; just release the latch.
                Scope.ensure {
                    case Maybe.Present(_) =>
                        // Error path: cleanup runs uninterruptibly with the configured budget.
                        Async.mask {
                            Abort.run[Timeout](
                                Async.timeout(cleanupTimeout) {
                                    Abort.run[SqlException](
                                        sendCopyFail(channel, "COPY FROM STDIN aborted by client").flatMap { _ =>
                                            drainToReadyForQuerySkipCheck(channel)
                                        }
                                    ).flatMap {
                                        case Result.Success(_) =>
                                            channel.endCleanup().andThen(latch.release)
                                        case Result.Failure(_) =>
                                            channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                                        case Result.Panic(t) =>
                                            Log.error(s"[kyo-sql] CopyExchange.copyIn: cleanup panic: ${t.getMessage}").andThen(
                                                channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                                            )
                                    }
                                }
                            ).flatMap {
                                case Result.Success(_) => ()
                                case Result.Failure(_) =>
                                    // Cleanup budget expired.
                                    channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                                case Result.Panic(t) =>
                                    Log.error(s"[kyo-sql] CopyExchange.copyIn: cleanup timeout-wrapper panic: ${t.getMessage}").andThen(
                                        channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                                    )
                            }
                        }
                    case Maybe.Absent =>
                        // Normal success: CopyDone + drain already sent on the happy path.
                        channel.endCleanup().andThen(latch.release)
                }.andThen {
                    // --- Happy path ---
                    // ALL channel operations here bypass checkCorrupted() via sendSkipCheck / receiveSkipCheck.
                    // Rationale: beginCleanup(latch) has already registered the latch on the channel.
                    // channel.send / channel.receive both call checkCorrupted(), which would block waiting
                    // for the latch we hold — deadlock.  We are the sole reader/writer during a COPY op,
                    // so the skip-check variants are safe.

                    // 1. Send the COPY SQL as a simple Query.
                    channel.sendSkipCheck(Query(sql))(using channel.marshallers.query).flatMap { _ =>
                        // 2. Await CopyInResponse (skipping ParameterStatus / NoticeResponse).
                        awaitCopyInResponse(channel, pid).flatMap { _ =>
                            // 3. Pump CopyData packets from the user stream.
                            // Use `foreach` which iterates over each Span[Byte] element.
                            data.foreach(chunk => sendCopyDataChunked(channel, chunk)).flatMap { _ =>
                                // 4. Send CopyDone (write raw bytes, bypassing cleanup-latch check).
                                sendCopyDone(channel).flatMap { _ =>
                                    // 5. Read CommandComplete + ReadyForQuery (skip-check because the cleanup
                                    //    latch is registered for the duration of this operation).
                                    readCopyInCompletionSkipCheck(channel, pid)
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
      * The returned [[Stream]] has [[Scope]] in its effect type. The [[Scope]] finalizer handles graceful cancellation: if the consumer
      * closes the stream before [[CopyDone]] is received, the finalizer sends [[CopyFail]] and drains to [[ReadyForQuery]].
      *
      * The cleanup runs uninterruptibly ([[Async.mask]]) with a bounded 5-second budget to ensure the protocol is always left in a defined
      * state.
      *
      * @param channel
      *   the exclusive Postgres channel for this operation
      * @param sql
      *   the `COPY ... TO STDOUT` SQL statement
      * @return
      *   a [[Stream]] of raw [[Span[Byte]]] COPY data payloads (one per [[CopyData]] message from the server); the stream ends when the
      *   server sends [[CopyDone]]
      */
    def copyOut(
        channel: PostgresChannel,
        sql: String,
        pid: Long,
        cleanupTimeout: Duration
    )(using Frame): Stream[Span[Byte], Async & Abort[SqlException] & Scope] =
        Stream:
            Latch.init(1).flatMap { latch =>
                channel.beginCleanup(latch).andThen {
                    // Scope.ensure: fires on normal completion, Abort, or fiber interruption.
                    // On error/cancellation: send CopyFail and drain ReadyForQuery.
                    // On normal success (CopyDone received): drain CommandComplete + ReadyForQuery was already done.
                    Scope.ensure {
                        case Maybe.Present(_) =>
                            // Error / early cancellation path — uninterruptible cleanup with configured budget.
                            Async.mask {
                                Abort.run[Timeout](
                                    Async.timeout(cleanupTimeout) {
                                        Abort.run[SqlException](
                                            sendCopyFail(channel, "COPY TO STDOUT stream closed by client").flatMap { _ =>
                                                drainToReadyForQuerySkipCheck(channel)
                                            }
                                        ).flatMap {
                                            case Result.Success(_) =>
                                                channel.endCleanup().andThen(latch.release)
                                            case Result.Failure(_) =>
                                                channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                                            case Result.Panic(t) =>
                                                Log.error(s"[kyo-sql] CopyExchange.copyOut: cleanup panic: ${t.getMessage}").andThen(
                                                    channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                                                )
                                        }
                                    }
                                ).flatMap {
                                    case Result.Success(_) => ()
                                    case Result.Failure(_) =>
                                        channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                                    case Result.Panic(t) =>
                                        Log.error(
                                            s"[kyo-sql] CopyExchange.copyOut: cleanup timeout-wrapper panic: ${t.getMessage}"
                                        ).andThen(
                                            channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                                        )
                                }
                            }
                        case Maybe.Absent =>
                            // Normal success: CommandComplete + ReadyForQuery already drained on the happy path.
                            channel.endCleanup().andThen(latch.release)
                    }.andThen {
                        // --- Happy path ---
                        // ALL channel operations here bypass checkCorrupted() via sendSkipCheck / receiveSkipCheck.
                        // Rationale: beginCleanup(latch) has already registered the latch on the channel.
                        // channel.send / channel.receive both call checkCorrupted(), which would block waiting
                        // for the latch we hold — deadlock.  We are the sole reader/writer during a COPY op,
                        // so the skip-check variants are safe.

                        // 1. Send the COPY SQL as a simple Query.
                        channel.sendSkipCheck(Query(sql))(using channel.marshallers.query).flatMap { _ =>
                            // 2. Await CopyOutResponse.
                            awaitCopyOutResponse(channel, pid).flatMap { _ =>
                                // 3. Stream CopyData packets until CopyDone, then drain CommandComplete + RFQ.
                                streamCopyOutData(channel, pid)
                            }
                        }
                    }
                }
            }

    // --- Private helpers ---

    /** Reads messages until [[CopyInResponse]], skipping [[ParameterStatus]] and [[NoticeResponse]].
      *
      * Uses [[PostgresChannel.receiveSkipCheck]] because the cleanup latch is registered for the duration of the COPY operation;
      * [[PostgresChannel.receive]] would deadlock waiting for the latch we hold.
      */
    private def awaitCopyInResponse(channel: PostgresChannel, pid: Long)(using Frame): CopyInResponse < (Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case r: CopyInResponse       => r
            case ParameterStatus(_, _)   => awaitCopyInResponse(channel, pid)
            case NoticeResponse(_)       => awaitCopyInResponse(channel, pid)
            case _: NotificationResponse => awaitCopyInResponse(channel, pid)
            case ErrorResponse(fields) =>
                drainToReadyForQuerySkipCheck(channel).andThen(Abort.fail(QueryResultExchange.mkServerError(
                    fields,
                    Absent,
                    0,
                    Present(pid)
                )))
            case other =>
                Abort.fail(SqlException.Connection(s"Expected CopyInResponse, got: $other", summon[Frame]))
        }

    /** Reads messages until [[CopyOutResponse]], skipping [[ParameterStatus]] and [[NoticeResponse]].
      *
      * Uses [[PostgresChannel.receiveSkipCheck]] because the cleanup latch is registered for the duration of the COPY operation;
      * [[PostgresChannel.receive]] would deadlock waiting for the latch we hold.
      */
    private def awaitCopyOutResponse(channel: PostgresChannel, pid: Long)(using Frame): CopyOutResponse < (Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case r: CopyOutResponse      => r
            case ParameterStatus(_, _)   => awaitCopyOutResponse(channel, pid)
            case NoticeResponse(_)       => awaitCopyOutResponse(channel, pid)
            case _: NotificationResponse => awaitCopyOutResponse(channel, pid)
            case ErrorResponse(fields) =>
                drainToReadyForQuerySkipCheck(channel).andThen(Abort.fail(QueryResultExchange.mkServerError(
                    fields,
                    Absent,
                    0,
                    Present(pid)
                )))
            case other =>
                Abort.fail(SqlException.Connection(s"Expected CopyOutResponse, got: $other", summon[Frame]))
        }

    /** Sends a [[Span[Byte]]] to the server as a CopyData packet, splitting into [[MaxChunkBytes]]-sized chunks.
      *
      * Writes raw bytes directly to the connection (bypassing checkCorrupted) because the cleanup latch is registered on this channel for
      * the entire duration of the COPY operation. Going through [[PostgresChannel.send]] would deadlock waiting for the latch we hold.
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
            case Result.Failure(_) => Abort.fail(SqlException.Connection("Connection closed while sending CopyData", summon[Frame]))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] CopyExchange: CopyData write panic: ${t.getMessage}").andThen(
                    Abort.fail(SqlException.Connection(s"CopyData write panic: ${t.getMessage}", summon[Frame]))
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
            case Result.Failure(_) => Abort.fail(SqlException.Connection("Connection closed while sending CopyDone", summon[Frame]))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] CopyExchange: CopyDone write panic: ${t.getMessage}").andThen(
                    Abort.fail(SqlException.Connection(s"CopyDone write panic: ${t.getMessage}", summon[Frame]))
                )
        }
    end sendCopyDone

    /** Sends [[CopyFail]] bypassing the cleanup-latch check (used in cleanup paths). */
    private def sendCopyFail(channel: PostgresChannel, reason: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        val buf = new PostgresBufferWriter
        buf.writeByte('f'.toByte)
        val lenOffset = buf.size
        buf.writeInt32(0)       // placeholder
        buf.writeString(reason) // NUL-terminated
        buf.patchInt32(lenOffset, buf.size - lenOffset)
        Abort.run[Closed](channel.conn.outbound.safe.put(buf.toSpan)).flatMap {
            case Result.Success(_) => ()
            case Result.Failure(_) => Abort.fail(SqlException.Connection("Connection closed while sending CopyFail", summon[Frame]))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] CopyExchange: CopyFail write panic: ${t.getMessage}").andThen(
                    Abort.fail(SqlException.Connection(s"CopyFail write panic: ${t.getMessage}", summon[Frame]))
                )
        }
    end sendCopyFail

    /** Reads CommandComplete + ReadyForQuery after CopyDone, bypassing the cleanup-latch check.
      *
      * The cleanup latch is held for the duration of the COPY operation. On the success path, we still need to drain the server's
      * CommandComplete + ReadyForQuery before releasing the latch. We use [[PostgresChannel.receiveSkipCheck]] to bypass the latch and read
      * directly from the TCP stream (safe because we are the sole reader during this operation).
      *
      * @return
      *   the affected-row count parsed from the CommandComplete tag (e.g. "COPY 1000" → 1000L)
      */
    private def readCopyInCompletionSkipCheck(channel: PostgresChannel, pid: Long)(using Frame): Long < (Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case CommandComplete(tag) =>
                // Parse the row count from "COPY N"; drain ReadyForQuery after.
                drainRfqSkipCheck(channel).andThen(parseCopyTag(tag))

            case NoticeResponse(_) =>
                readCopyInCompletionSkipCheck(channel, pid)

            case ParameterStatus(_, _) =>
                readCopyInCompletionSkipCheck(channel, pid)

            case _: NotificationResponse =>
                readCopyInCompletionSkipCheck(channel, pid)

            case ErrorResponse(fields) =>
                drainToReadyForQuerySkipCheck(channel).andThen(Abort.fail(QueryResultExchange.mkServerError(
                    fields,
                    Absent,
                    0,
                    Present(pid)
                )))

            case _: ReadyForQuery =>
                // Shouldn't happen before CommandComplete, but handle gracefully.
                0L

            case other =>
                Abort.fail(SqlException.Connection(s"Expected CommandComplete after CopyDone, got: $other", summon[Frame]))
        }

    /** Drains to ReadyForQuery (inclusive) using receiveSkipCheck. */
    private def drainRfqSkipCheck(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case _: ReadyForQuery => ()
            case _                => drainRfqSkipCheck(channel)
        }

    /** Drains to ReadyForQuery (inclusive) using receiveSkipCheck. Package-visible for use in cleanup. */
    private[exchange] def drainToReadyForQuerySkipCheck(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        drainRfqSkipCheck(channel)

    /** Streams [[CopyData]] payloads from the server until [[CopyDone]], then drains [[CommandComplete]] + [[ReadyForQuery]].
      *
      * Used on the copyOut happy path.
      */
    private def streamCopyOutData(
        channel: PostgresChannel,
        pid: Long
    )(using Frame): Unit < (Emit[Chunk[Span[Byte]]] & Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case CopyData(data) =>
                // Emit this chunk, then loop for more.
                Emit.valueWith(Chunk(data))(streamCopyOutData(channel, pid))

            case CopyDone =>
                // Server finished sending data. Drain CommandComplete + ReadyForQuery.
                drainCopyOutCompletionSkipCheck(channel, pid)

            case NoticeResponse(_) =>
                streamCopyOutData(channel, pid)

            case ParameterStatus(_, _) =>
                streamCopyOutData(channel, pid)

            case _: NotificationResponse =>
                streamCopyOutData(channel, pid)

            case ErrorResponse(fields) =>
                drainToReadyForQuerySkipCheck(channel).andThen(Abort.fail(QueryResultExchange.mkServerError(
                    fields,
                    Absent,
                    0,
                    Present(pid)
                )))

            case other =>
                Abort.fail(SqlException.Connection(s"Unexpected message in COPY TO STDOUT stream: $other", summon[Frame]))
        }

    /** Drains [[CommandComplete]] and [[ReadyForQuery]] after [[CopyDone]] on the copyOut path. */
    private def drainCopyOutCompletionSkipCheck(channel: PostgresChannel, pid: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.receiveSkipCheck.flatMap {
            case _: ReadyForQuery      => ()
            case CommandComplete(_)    => drainCopyOutCompletionSkipCheck(channel, pid)
            case NoticeResponse(_)     => drainCopyOutCompletionSkipCheck(channel, pid)
            case ParameterStatus(_, _) => drainCopyOutCompletionSkipCheck(channel, pid)
            case ErrorResponse(fields) =>
                drainToReadyForQuerySkipCheck(channel).andThen(Abort.fail(QueryResultExchange.mkServerError(
                    fields,
                    Absent,
                    0,
                    Present(pid)
                )))
            case other =>
                Abort.fail(SqlException.Connection(s"Unexpected message after CopyDone: $other", summon[Frame]))
        }

    /** Parses the affected-row count from a COPY command tag (e.g. "COPY 1000" → 1000L). */
    private def parseCopyTag(tag: String): Long =
        val parts = tag.split(' ')
        if parts.length >= 2 then parts.last.toLongOption.getOrElse(0L)
        else 0L
    end parseCopyTag

end CopyExchange
