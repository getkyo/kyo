package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlConnectionClosedException
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlConnectionWritePanicException
import kyo.SqlException
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

    /** What a corrupted channel reports as its corrupter when this exchange's cleanup fails; see [[MysqlChannel.markCorrupted]]. */
    private val OperationName: String = "LOAD DATA LOCAL INFILE"

    /** Drives the LOCAL INFILE upload for a stream of bytes.
      *
      * Each of the stream's chunks becomes one `LOCAL_INFILE_DATA` payload, split at [[MaxChunkSize]] when the chunk is larger than one
      * payload holds. Chunk-level rather than per-byte: pulling one element at a time costs a suspension per byte of the upload.
      *
      * Registers a [[Scope]] finalizer to send the mandatory empty-terminator packet whenever the computation exits with an error
      * (including errors originating from the user-supplied stream `S`). This ensures MySQL is always informed that the INFILE upload is
      * complete, leaving the connection in a defined state.
      *
      * A [[Latch]] is registered in the channel before the upload begins. Any subsequent channel operation (a follow-up SELECT, say) blocks
      * in [[MysqlChannel.checkCorrupted]] until the cleanup fiber releases the latch, so the caller sees either a clean connection or
      * `"unusable"`, never stale protocol bytes from an in-flight cleanup (which the server reports as "Got packets out of order").
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
                        // Two failure classes, split by where they can land. A cancellation-like failure (a
                        // Timeout, any Panic) can fire between a packet's write and the server's acknowledgement,
                        // so the cleanup's terminator round-trip can "succeed" against a server still mid-stream
                        // and desynchronise the sequence counter for the next caller; the channel is marked
                        // corrupted so that caller fails fast as unusable. A typed stream failure only fires at a
                        // chunk boundary with nothing in flight, so the terminator is genuine and the connection
                        // stays reusable.
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
                                            (if cancellationLike then channel.markCorrupted(OperationName) else ((): Unit < Sync)).andThen(
                                                channel.endCleanup()
                                            ).andThen(latch.release)
                                        case Result.Failure(_) =>
                                            // Cleanup failed (write error or ERR from server).
                                            channel.markCorrupted(OperationName).andThen(channel.endCleanup()).andThen(latch.release)
                                        case Result.Panic(t) =>
                                            channel.markCorrupted(OperationName).andThen(channel.endCleanup()).andThen(latch.release)
                                    }
                                }
                            ).flatMap {
                                case Result.Success(_) => ()
                                case Result.Failure(_) =>
                                    // Inner 5-second cleanup timeout fired. Mark corrupted and unblock callers.
                                    channel.markCorrupted(OperationName).andThen(channel.endCleanup()).andThen(latch.release)
                                case Result.Panic(t) =>
                                    channel.markCorrupted(OperationName).andThen(channel.endCleanup()).andThen(latch.release)
                            }
                        }
                    case Maybe.Absent =>
                        // Normal success exit: terminator was already sent on the success path below.
                        // Release the latch immediately, no corruption, no cleanup needed.
                        channel.endCleanup().andThen(latch.release)
                }.andThen {
                    // One payload per stream chunk, split when a chunk exceeds what one payload holds.
                    data.foreachChunk(chunk => sendChunked(channel, Span.from(chunk))).flatMap { _ =>
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
    end run

    // --- Upload protocol ---

    /** Sends `data` as `LOCAL_INFILE_DATA` payloads of at most [[MaxChunkSize]] bytes each.
      *
      * An empty payload is the upload's end-of-file signal, so an empty chunk sends nothing: emitting it would end the upload early and
      * leave the rest of the stream to be read as a new command.
      */
    private def sendChunked(channel: MysqlChannel, data: Span[Byte])(using Frame): Unit < (Async & Abort[SqlException]) =
        if data.isEmpty then ()
        else if data.size <= MaxChunkSize then sendRawPayload(channel, data)
        else
            sendRawPayload(channel, data.slice(0, MaxChunkSize)).flatMap { _ =>
                sendChunked(channel, data.slice(MaxChunkSize, data.size))
            }

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
                ProtocolDecode.decode("LOCAL INFILE OK", payload.slice(1, payload.size), channel.unmarshallers.okPacket).map { ok =>
                    ok.affectedRows
                }
            else if firstByte == 0xff then
                // ERR packet.
                ProtocolDecode.decode("LOCAL INFILE ERR", payload.slice(1, payload.size), channel.unmarshallers.errPacket).flatMap {
                    err =>
                        Abort.fail(MysqlErrors.mkServerError(err, Maybe.Absent, 0, Maybe.Absent))
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
                Abort.fail(SqlConnectionWritePanicException(t))
        }
    end sendRawPayload

end LocalInfileExchange
