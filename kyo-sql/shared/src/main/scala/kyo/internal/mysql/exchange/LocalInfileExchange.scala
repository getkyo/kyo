package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlConnectionClosedException
import kyo.SqlConnectionProtocolDecodeException
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlConnectionWritePanicException
import kyo.SqlDecodeException
import kyo.SqlException
import kyo.SqlServerException
import kyo.internal.mysql.*

/** Handles a MySQL `LOCAL_INFILE_REQUEST` packet by streaming user-supplied bytes to the server.
  *
  * The MySQL LOCAL INFILE protocol sequence (after a COM_QUERY for LOAD DATA LOCAL INFILE):
  *   1. Server sends a LOCAL_INFILE_REQUEST packet: 0xFB followed by the filename the server would like (we ignore it, the caller supplies
  *      the data).
  *   2. Client sends the file contents split into `LOCAL_INFILE_DATA` packets (plain data payloads, each up to [[MysqlPacket.MaxPayload]]
  *      bytes).
  *   3. Client sends a terminating empty `LOCAL_INFILE_DATA` packet (zero-length payload).
  *   4. Server responds with an `OK_PACKET` carrying the affected-row count.
  *
  * Each `LOCAL_INFILE_DATA` packet is a raw MySQL packet (4-byte header + payload) with no command byte prefix. The sequence ID must
  * continue from where the initial COM_QUERY exchange left off, we do NOT call `channel.resetSeq()` here.
  *
  * Reference: MySQL Internals Manual, LOAD DATA LOCAL INFILE; CLIENT_LOCAL_FILES capability flag.
  */
private[mysql] object LocalInfileExchange:

    // --- Constants ---

    /** Maximum payload bytes per LOCAL_INFILE_DATA packet (16 MB - 1). */
    private val MaxChunkSize: Int = MysqlPacket.MaxPayload

    /** Drives the LOCAL INFILE upload for a stream of bytes.
      *
      * Processes the stream chunk-by-chunk (not byte-by-byte) for efficiency. Accumulates bytes up to [[MaxChunkSize]] and flushes full
      * packets immediately, carrying any remainder to the next chunk.
      *
      * Registers a [[Scope]] finalizer to send the mandatory empty-terminator packet whenever the computation exits with an error
      * (including errors originating from the user-supplied stream `S`). This ensures MySQL is always informed that the INFILE upload is
      * complete, leaving the connection in a defined state.
      *
      * Race-condition fix: a [[Latch]] is registered in the channel before the upload begins. Any subsequent channel operation (e.g. a
      * follow-up SELECT) will block in [[MysqlChannel.checkCorrupted]] until the cleanup fiber releases the latch, guaranteeing that the
      * caller sees either a clean connection (outcome a) or `"unusable"` (outcome b), never stale protocol bytes from an in-flight cleanup
      * (outcome c / "Got packets out of order").
      *
      * @param channel
      *   the active [[MysqlChannel]] (sequence ID is already advanced past the COM_QUERY send and the 0xFB byte read)
      * @param data
      *   the user-supplied byte stream; the caller decides the source (in-memory, Path.readBytes, HTTP-backed, etc.)
      * @return
      *   the affected-row count from the server's OK packet
      */
    def run[S](
        channel: MysqlChannel,
        data: Stream[Byte, S]
    )(using Frame): Long < (Async & Abort[SqlException] & Scope & S) =
        // Create the cleanup latch and register it in the channel before the upload starts.
        // Any follow-up operation on this channel will block in checkCorrupted() until the
        // latch is released by the cleanup path (or the success path) below.
        Latch.init(1).flatMap { latch =>
            channel.beginCleanup(latch).andThen {
                // Register a cleanup finalizer that fires on error exit (including timeout/interrupt).
                // On error, we attempt graceful cleanup (empty terminator + drain server response)
                // inside Async.mask so the cleanup cannot itself be interrupted.  A 5-second inner
                // timeout prevents the cleanup from hanging forever if the server stops responding.
                // The latch is always released at the end of this block so waiting callers unblock.
                Scope.ensure {
                    case Maybe.Present(error) =>
                        // Classify the failure that triggered cleanup.  Two kinds matter for
                        // protocol-state reasoning:
                        //
                        //   * "Cancellation-like": Failure(_: Timeout) (Async.timeout fired) or any
                        //     Panic (fiber interrupt, OOM, etc.).  These can fire at ARBITRARY points
                        //     in the upload, including the gap between the OS-level completion of one
                        //     packet's write and the server-side acknowledgement of full packet receipt.
                        //     The cleanup's empty-terminator + OK round-trip can syntactically succeed
                        //     while the server's MySQL command state is still mid-stream from its
                        //     perspective (e.g. it acknowledges what it interprets as the terminator but
                        //     the response is actually for an earlier in-flight packet).  In that case a
                        //     follow-up query on the same connection sees a desynchronised seq counter
                        //     and the server replies "Got packets out of order".  Defensively mark the
                        //     channel corrupted whenever cleanup fires from a cancellation-like failure,
                        //     so the next caller fails fast with "unusable" instead of seeing the
                        //     confusing protocol error.
                        //
                        //   * "Typed stream Failure" (e.g. Abort.fail(UserError) from a user-supplied
                        //     stream): these only fire when `data.fold` pulls a chunk and the upstream
                        //     yields Failure.  They are deterministic chunk-boundary events, by
                        //     construction no write is in flight and the server has fully processed
                        //     all bytes the client has committed to send.  The empty terminator is a
                        //     genuine end-of-upload signal here, and the server's OK is the genuine
                        //     response.  Allow the cleanup-success branch to keep the connection
                        //     reusable (covers the "mid-stream failure leaves the connection reusable"
                        //     test).
                        val cancellationLike = error match
                            case Result.Panic(_)            => true
                            case Result.Failure(_: Timeout) => true
                            case _                          => false
                        Async.mask {
                            Abort.run[Timeout](
                                Async.timeout(5.seconds) {
                                    Abort.run[SqlException](
                                        sendRawPayload(channel, Span.empty).flatMap { _ =>
                                            // Use readRawPayloadSkipCheck: _corrupted may already be set
                                            // by a concurrent markCorrupted() call and we still need to
                                            // drain the server's response before discarding the connection.
                                            readFinalResponseSkipCheck(channel).map(_ => ())
                                        }
                                    ).flatMap {
                                        case Result.Success(_) =>
                                            // Cleanup round-trip syntactically succeeded.  For
                                            // cancellation-like failures this success cannot be trusted
                                            // (see classification above), mark the channel corrupted
                                            // so the next caller fails fast with "unusable" instead of
                                            // observing a desynchronised protocol stream (e.g.
                                            // "Got packets out of order" on a follow-up query).
                                            (if cancellationLike then channel.markCorrupted() else ((): Unit < Sync)).andThen(
                                                channel.endCleanup()
                                            ).andThen(latch.release)
                                        case Result.Failure(_) =>
                                            // Cleanup failed (write error or ERR from server).
                                            channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                                        case Result.Panic(t) =>
                                            java.lang.System.err.println(
                                                s"[kyo-sql] LocalInfileExchange: cleanup panic: ${t.getMessage}"
                                            )
                                            channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                                    }
                                }
                            ).flatMap {
                                case Result.Success(_) => ()
                                case Result.Failure(_) =>
                                    // Inner 5-second cleanup timeout fired. Mark corrupted and unblock callers.
                                    channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                                case Result.Panic(t) =>
                                    java.lang.System.err.println(
                                        s"[kyo-sql] LocalInfileExchange: cleanup timeout-wrapper panic: ${t.getMessage}"
                                    )
                                    channel.markCorrupted().andThen(channel.endCleanup()).andThen(latch.release)
                            }
                        }
                    case Maybe.Absent =>
                        // Normal success exit: terminator was already sent on the success path below.
                        // Release the latch immediately, no corruption, no cleanup needed.
                        channel.endCleanup().andThen(latch.release)
                }.andThen {
                    // Use chunk-level fold to avoid per-byte Kyo monadic overhead.
                    // The accumulator is the carry buffer (bytes that didn't fill a full 16 MB packet yet).
                    data.fold(Chunk.empty[Byte]) { (carry, byte) =>
                        val next = carry.appended(byte)
                        if next.size >= MaxChunkSize then
                            val toSend = next.take(MaxChunkSize)
                            val rest   = next.drop(MaxChunkSize)
                            sendRawPayload(channel, Span.from(toSend.toArray)).andThen(rest)
                        else
                            next
                        end if
                    }.flatMap { remaining =>
                        // Flush remaining bytes (< MaxChunkSize) then send the mandatory empty terminator.
                        val flushRemaining: Unit < (Async & Abort[SqlException]) =
                            if remaining.nonEmpty then
                                sendRawPayload(channel, Span.from(remaining.toArray))
                            else
                                (
                            )
                        flushRemaining.flatMap { _ =>
                            // Terminator: empty LOCAL_INFILE_DATA packet signals end-of-file.
                            // Use readFinalResponseSkipCheck: the cleanup latch is registered on this channel
                            // for the duration of the upload, so readRawPayload (which calls checkCorrupted)
                            // would deadlock waiting for the latch to be released.  The skip-check variant
                            // bypasses the latch and reads directly from the TCP stream, which is safe here
                            // because we are the sole writer/reader during the upload.
                            sendRawPayload(channel, Span.empty).flatMap { _ =>
                                readFinalResponseSkipCheck(channel)
                            }
                        }
                    }
                }
            }
        }
    end run

    // --- Upload protocol ---

    /** Reads the server's final OK or ERR packet after the upload, bypassing the corruption/latch check.
      *
      * Used both on the normal success path and in the error-path cleanup:
      *   - On the success path: the cleanup latch is registered on this channel for the entire duration of the upload, so calling
      *     [[MysqlChannel.readRawPayload]] (which calls `checkCorrupted`, which awaits the latch) would deadlock. The skip-check variant
      *     reads directly from the TCP stream, safe because we are the sole user of the channel during the upload.
      *   - On the error-path cleanup: [[MysqlChannel.markCorrupted]] may have already been set to block concurrent callers, but we still
      *     need to drain the server's OK/ERR response so the TCP stream is in a known state before the connection is discarded.
      *
      * [[MysqlChannel.readRawPayloadSkipCheck]] is used to bypass the corruption guard in both cases.
      *
      * Used exclusively by the error-path cleanup code in [[run]], which may run after [[MysqlChannel.markCorrupted]] has already been set
      * (to block concurrent callers) but still needs to drain the server's OK/ERR response so the TCP stream is left in a known state
      * before the connection is discarded.
      */
    private def readFinalResponseSkipCheck(channel: MysqlChannel)(using Frame): Long < (Async & Abort[SqlException]) =
        channel.readRawPayloadSkipCheck.flatMap { payload =>
            val firstByte = payload(0) & 0xff
            if firstByte == 0x00 || (firstByte == 0xfe && payload.size >= 7) then
                // OK packet.
                val reader = MysqlBufferReader(payload.slice(1, payload.size))
                Abort.run[SqlDecodeException](
                    channel.unmarshallers.okPacket.read(reader)
                ).flatMap {
                    case Result.Success(ok) =>
                        ok.affectedRows
                    case Result.Failure(e) =>
                        Abort.fail(SqlConnectionProtocolDecodeException("LOCAL INFILE OK", e))
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] LocalInfileExchange: OK decode panic: ${t.getMessage}")
                        Abort.fail(SqlConnectionProtocolDecodeException("LOCAL INFILE OK", t))
                }
            else if firstByte == 0xff then
                // ERR packet.
                val reader = MysqlBufferReader(payload.slice(1, payload.size))
                Abort.run[SqlDecodeException](
                    channel.unmarshallers.errPacket.read(reader)
                ).flatMap {
                    case Result.Success(err) =>
                        Abort.fail(mkServerError(err))
                    case Result.Failure(e) =>
                        Abort.fail(SqlConnectionProtocolDecodeException("LOCAL INFILE ERR", e))
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] LocalInfileExchange: ERR decode panic: ${t.getMessage}")
                        Abort.fail(SqlConnectionProtocolDecodeException("LOCAL INFILE ERR", t))
                }
            else
                Abort.fail(SqlConnectionUnexpectedMessageException(
                    "LOCAL INFILE upload",
                    "OK / ERR",
                    s"byte 0x${firstByte.toHexString}"
                ))
            end if
        }
    end readFinalResponseSkipCheck

    // --- Packet writing ---

    /** Writes a raw MySQL packet with `payload` as the data (no command byte prefix).
      *
      * LOCAL_INFILE_DATA packets are framed as standard MySQL packets but carry raw file bytes, there is no command byte prefix unlike
      * COM_QUERY or other frontend messages. Advances the channel sequence ID by the number of frames written.
      */
    private[exchange] def sendRawPayload(channel: MysqlChannel, payload: Span[Byte])(using Frame): Unit < (Async & Abort[SqlException]) =
        val packets  = MysqlPacket.writeOne(payload, channel.currentSeq)
        val totalLen = packets.foldLeft(0)(_ + _.size)
        val allBytes = new Array[Byte](totalLen)
        var offset   = 0
        packets.foreach { p =>
            val arr = p.toArray
            java.lang.System.arraycopy(arr, 0, allBytes, offset, arr.length)
            offset += arr.length
        }
        // Advance seqId by the number of packets written.
        channel.advanceSeq(packets.size)
        Abort.run[Closed](channel.conn.outbound.safe.put(Span.from(allBytes))).flatMap {
            case Result.Success(_) => ()
            case Result.Failure(_) =>
                Abort.fail(SqlConnectionClosedException("writing LOCAL INFILE"))
            case Result.Panic(t) =>
                java.lang.System.err.println(s"[kyo-sql] LocalInfileExchange: write panic: ${t.getMessage}")
                Abort.fail(SqlConnectionWritePanicException(t))
        }
    end sendRawPayload

    private def mkServerError(err: ErrPacket)(using Frame): SqlServerException =
        SqlServerException(
            err.sqlState,
            "ERROR",
            err.errorMessage,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Map("code" -> err.errorCode.toString),
            Maybe.Absent,
            0,
            Maybe.Absent
        )

end LocalInfileExchange
