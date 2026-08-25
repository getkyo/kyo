package kyo.internal.postgres.exchange

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.postgres.PostgresChannel
import kyo.internal.postgres.Query
import kyo.net.StubConnection

/** Pins how a byte stream is framed on the COPY sub-protocol, in both directions.
  *
  * Driven over a [[StubConnection]] with the server's side of the exchange pre-loaded, so each leaf asserts on the exact frames the client
  * wrote and on the exact chunks it emitted. That is what the `Byte` element type is about: a stream chunk is the transfer unit, and the
  * server's own framing is what a consumer sees coming back. The round-trip against a real server is
  * [[kyo.postgres.CopyIntegrationTest]]'s.
  */
class CopyExchangeTest extends kyo.Test:

    // --- server-side frames ---

    /** `'G'` CopyInResponse for a text-format copy of no columns. */
    private val copyInResponse: Span[Byte] =
        Span.from(Array[Byte]('G', 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00))

    /** `'H'` CopyOutResponse for a text-format copy of no columns. */
    private val copyOutResponse: Span[Byte] =
        Span.from(Array[Byte]('H', 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00))

    /** `'c'` CopyDone. */
    private val copyDone: Span[Byte] =
        Span.from(Array[Byte]('c', 0x00, 0x00, 0x00, 0x04))

    /** `'Z'` ReadyForQuery, idle. */
    private val readyForQuery: Span[Byte] =
        Span.from(Array[Byte]('Z', 0x00, 0x00, 0x00, 0x05, 'I'))

    /** `'C'` CommandComplete carrying `tag` as a NUL-terminated string. */
    private def commandComplete(tag: String): Span[Byte] =
        val body = tag.getBytes(StandardCharsets.US_ASCII)
        val len  = 4 + body.length + 1
        Span.from(
            (Array[Byte]('C') ++ intBytes(len) ++ body ++ Array[Byte](0x00))
        )
    end commandComplete

    /** `'d'` CopyData carrying `payload`. */
    private def copyData(payload: String): Span[Byte] =
        val body = payload.getBytes(StandardCharsets.US_ASCII)
        Span.from(Array[Byte]('d') ++ intBytes(4 + body.length) ++ body)

    /** `'E'` ErrorResponse carrying severity ERROR, `sqlState`, and `message`. */
    private def errorResponse(sqlState: String, message: String): Span[Byte] =
        val fields =
            Array[Byte]('S') ++ "ERROR".getBytes(StandardCharsets.US_ASCII) ++ Array[Byte](0) ++
                Array[Byte]('C') ++ sqlState.getBytes(StandardCharsets.US_ASCII) ++ Array[Byte](0) ++
                Array[Byte]('M') ++ message.getBytes(StandardCharsets.US_ASCII) ++ Array[Byte](0) ++
                Array[Byte](0)
        Span.from(Array[Byte]('E') ++ intBytes(4 + fields.length) ++ fields)
    end errorResponse

    /** `'A'` NotificationResponse from backend `pid` on `channelName` carrying `payload`. */
    private def notificationResponse(pid: Int, channelName: String, payload: String): Span[Byte] =
        val body =
            intBytes(pid) ++
                channelName.getBytes(StandardCharsets.US_ASCII) ++ Array[Byte](0) ++
                payload.getBytes(StandardCharsets.US_ASCII) ++ Array[Byte](0)
        Span.from(Array[Byte]('A') ++ intBytes(4 + body.length) ++ body)
    end notificationResponse

    private def intBytes(v: Int): Array[Byte] =
        Array[Byte]((v >>> 24).toByte, (v >>> 16).toByte, (v >>> 8).toByte, v.toByte)

    // --- harness ---

    /** Opens a channel over a stub whose inbound already holds `serverFrames`, and hands the leaf the channel and the stub. */
    private def withChannel[A, S](serverFrames: Span[Byte]*)(
        f: (PostgresChannel, kyo.net.Connection) => A < (S & Async & Abort[SqlException] & Scope)
    )(using Frame): A < (S & Async & Abort[SqlException] & Scope) =
        val conn = StubConnection()
        PostgresChannel(conn).flatMap { channel =>
            preload(conn, serverFrames*).andThen(f(channel, conn))
        }
    end withChannel

    /** Puts `frames` on the stub's inbound channel, one span per frame.
      *
      * A leaf calls this directly when a frame has to arrive DURING the exchange rather than ahead of it. `copyIn`'s pump checks for a
      * rejection between chunks, and a `CommandComplete` preloaded before the upload lands in exactly that window, where the protocol says
      * the server cannot have sent one: it owes that reply to a `CopyDone` the client has not written yet.
      */
    private def preload(conn: kyo.net.Connection, frames: Span[Byte]*)(using Frame): Unit < (Async & Abort[SqlException]) =
        Abort.run[Closed](Kyo.foreachDiscard(Chunk.from(frames))(frame => conn.inbound.safe.put(frame))).flatMap {
            case Result.Success(_) => ()
            case other             => Abort.panic(new AssertionError(s"could not preload the stub connection: $other"))
        }

    /** Drains everything the client wrote, as `(type byte, payload)` pairs, a trailing NUL terminator dropped.
      *
      * Each frame is one element on the stub's outbound channel, and every Postgres frontend frame is a type byte plus a 4-byte length, so
      * the payload starts at offset 5. A `Query` frame carries its SQL as a cstring, so its payload ends in a NUL that is framing rather
      * than content; dropping it keeps the expectations below readable.
      */
    private def written(conn: kyo.net.Connection)(using Frame): Chunk[(Char, String)] < (Async & Abort[SqlException]) =
        Abort.run[Closed](conn.outbound.safe.drain).flatMap {
            case Result.Success(frames) =>
                frames.map { frame =>
                    val bytes   = frame.toArray
                    val payload = bytes.drop(5)
                    val content = if payload.nonEmpty && payload.last == 0 then payload.dropRight(1) else payload
                    (bytes(0).toChar, new String(content, StandardCharsets.US_ASCII))
                }
            case other => Abort.panic(new AssertionError(s"could not read what the client wrote: $other"))
        }

    // --- copyIn ---

    "copyIn sends one CopyData frame per stream chunk, in order" in {
        Scope.run {
            withChannel(copyInResponse) { (channel, conn) =>
                // The completion frames arrive from inside the stream, after the last chunk, because that is the
                // earliest point a real server could have sent them: CommandComplete answers a CopyDone the pump
                // has not written yet. Preloading them would put them where the pump's rejection check reads.
                val data = Stream[Byte, Async & Abort[SqlException]](
                    Emit.valueWith(Chunk.from("ab".getBytes(StandardCharsets.US_ASCII))) {
                        Emit.valueWith(Chunk.from("cde".getBytes(StandardCharsets.US_ASCII))) {
                            preload(conn, commandComplete("COPY 3"), readyForQuery)
                        }
                    }
                )
                CopyExchange.copyIn(channel, "COPY t FROM STDIN", 7L, data, 5.seconds).flatMap { affected =>
                    written(conn).map { frames =>
                        assert(affected == 3L, s"the COPY tag's count is the result, got $affected")
                        assert(
                            frames == Chunk(('Q', "COPY t FROM STDIN"), ('d', "ab"), ('d', "cde"), ('c', "")),
                            s"expected the query, one CopyData per chunk, then CopyDone; wrote $frames"
                        )
                    }
                }
            }
        }
    }

    "copyIn of an empty stream sends no CopyData frame at all" in {
        Scope.run {
            withChannel(copyInResponse, commandComplete("COPY 0"), readyForQuery) { (channel, conn) =>
                CopyExchange.copyIn(channel, "COPY t FROM STDIN", 7L, Stream.empty[Byte], 5.seconds).flatMap { affected =>
                    written(conn).map { frames =>
                        assert(affected == 0L, s"an empty upload loads no row, got $affected")
                        assert(
                            frames == Chunk(('Q', "COPY t FROM STDIN"), ('c', "")),
                            s"expected the query and CopyDone only; wrote $frames"
                        )
                    }
                }
            }
        }
    }

    "copyIn stops uploading at the next chunk boundary once the server has rejected the transfer" in {
        Scope.run {
            // The rejection is already on the wire when the first chunk is pumped: the negotiation read consumes
            // only the 'G' span and leaves the error span queued. With no check between chunks, all three chunks
            // and a CopyDone go out first and the error surfaces only from the completion read afterwards.
            assertRejectionStopsUpload(copyInResponse, errorResponse("23505", "duplicate key"), readyForQuery)
        }
    }

    "copyIn sees a rejection the message reader has already buffered, not only one still on the channel" in {
        Scope.run {
            // ONE span carrying 'G', 'E' and 'Z' back to back, which is what a server answering inside a single
            // TCP segment looks like. The negotiation read takes the whole span and decodes the 'G' out of it, so
            // the rejection sits in MessageReader's private buffer while conn.inbound is EMPTY. A check that
            // polled the channel rather than asking the reader finds nothing here and uploads everything.
            assertRejectionStopsUpload(
                Span.from(copyInResponse.toArray ++ errorResponse("23505", "duplicate key").toArray ++ readyForQuery.toArray)
            )
        }
    }

    /** Drives a three-chunk `copyIn` against a server that has already rejected the transfer, and asserts that nothing but the query
      * reached the wire.
      *
      * Shared verbatim by the two leaves above, which differ only in how the server delivered its frames: one span each, or all three in a
      * single segment. That difference is the whole reason both exist, so their assertions have to be identical.
      */
    private def assertRejectionStopsUpload(serverFrames: Span[Byte]*)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[SqlException] & Scope) =
        withChannel(serverFrames*) { (channel, conn) =>
            val data = Stream[Byte, Any](
                Emit.valueWith(Chunk.from("ab".getBytes(StandardCharsets.US_ASCII))) {
                    Emit.valueWith(Chunk.from("cde".getBytes(StandardCharsets.US_ASCII))) {
                        Emit.valueWith(Chunk.from("fg".getBytes(StandardCharsets.US_ASCII)))(())
                    }
                }
            )
            Abort.run[SqlException](CopyExchange.copyIn(channel, "COPY t FROM STDIN", 7L, data, 5.seconds)).flatMap {
                case Result.Failure(e: SqlServerException) =>
                    // The rejection resolved at the ReadyForQuery barrier, so the channel is idle rather than
                    // corrupted and the next statement must go through without waiting on the COPY latch.
                    Abort.run[Timeout](
                        Async.timeout(2.seconds)(channel.send(Query("SELECT 1"))(using channel.marshallers.query))
                    ).flatMap {
                        case Result.Success(_) =>
                            written(conn).map { frames =>
                                assert(e.sqlState == "23505", s"the server's own error must surface; got $e")
                                assert(
                                    frames == Chunk(('Q', "COPY t FROM STDIN"), ('Q', "SELECT 1")),
                                    s"no CopyData and no CopyDone belong on the wire after the rejection; wrote $frames"
                                )
                            }
                        case other =>
                            fail(s"the channel was left blocked or corrupted after a rejected COPY: $other")
                    }
                case other =>
                    fail(s"expected the server's typed error, got: $other")
            }
        }
    end assertRejectionStopsUpload

    // --- copyOut ---

    "copyOut emits one chunk per CopyData payload, and the bytes concatenate to the whole transfer" in {
        Scope.run {
            withChannel(
                copyOutResponse,
                copyData("ab"),
                copyData("c"),
                copyDone,
                commandComplete("COPY 2"),
                readyForQuery
            ) { (channel, _) =>
                AtomicRef.init(Chunk.empty[Int]).flatMap { sizes =>
                    CopyExchange.copyOut(channel, "COPY t TO STDOUT", 7L, 5.seconds)
                        .foreachChunk(chunk => sizes.getAndUpdate(_.appended(chunk.size)))
                        .andThen(sizes.get)
                        .map { seen =>
                            assert(seen == Chunk(2, 1), s"each CopyData payload is one chunk; saw chunk sizes $seen")
                        }
                }
            }
        }
    }

    "copyOut of a transfer with no CopyData yields an empty stream" in {
        Scope.run {
            withChannel(copyOutResponse, copyDone, commandComplete("COPY 0"), readyForQuery) { (channel, _) =>
                CopyExchange.copyOut(channel, "COPY t TO STDOUT", 7L, 5.seconds).run.map { bytes =>
                    assert(bytes == Chunk.empty[Byte], s"nothing was sent, so nothing is emitted; got $bytes")
                }
            }
        }
    }

    // --- copyOut cleanup lifecycle ---

    "copyOut releases the cleanup latch when the transfer completes, not at scope exit" in {
        Scope.run {
            withChannel(copyOutResponse, copyData("ab"), copyDone, commandComplete("COPY 1"), readyForQuery) { (channel, conn) =>
                CopyExchange.copyOut(channel, "COPY t TO STDOUT", 7L, 5.seconds).run.flatMap { bytes =>
                    // The enclosing scope is still open; only the transfer has ended. A send on the same
                    // channel must proceed rather than wait on the latch: on a pinned session this send is
                    // the transaction's COMMIT, and the scope holding the finalizer closes only after it.
                    Abort.run[Timeout](
                        Async.timeout(2.seconds)(channel.send(Query("SELECT 1"))(using channel.marshallers.query))
                    ).flatMap {
                        case Result.Success(_) =>
                            written(conn).map { frames =>
                                assert(
                                    bytes == Chunk.from("ab".getBytes(StandardCharsets.US_ASCII)),
                                    s"the transfer's bytes must arrive intact; got $bytes"
                                )
                                assert(
                                    frames == Chunk(('Q', "COPY t TO STDOUT"), ('Q', "SELECT 1")),
                                    s"a completed transfer owes no CopyFail; wrote $frames"
                                )
                            }
                        case other =>
                            fail(s"the send blocked on the COPY latch after the stream completed: $other")
                    }
                }
            }
        }
    }

    "a truncated copyOut runs its abort cleanup at the enclosing scope's exit" in {
        Scope.run {
            withChannel(
                copyOutResponse,
                copyData("ab"),
                copyData("cd"),
                copyDone,
                commandComplete("COPY 2"),
                readyForQuery
            ) { (channel, conn) =>
                // take(1) stops pulling without an interrupt: Stream.take drops the continuation, so the
                // scope's own exit report says nothing went wrong while the transfer sits abandoned
                // mid-flood. The inner scope's close must still send CopyFail and drain the tail, or the
                // channel is handed onward with COPY frames queued on the wire.
                // Bounded so a desynchronised frame layout names itself. Not a hang fix: the leaf's own 120s
                // default (`kyo.test.internal.TestBase.timeout`) already ends it, four times slower and without
                // saying what the read wanted.
                Abort.run[Timeout](Async.timeout(30.seconds)(
                    Scope.run(CopyExchange.copyOut(channel, "COPY t TO STDOUT", 7L, 5.seconds).take(1).run)
                )).flatMap {
                    case Result.Failure(_: Timeout) =>
                        fail("timed out after 30s waiting for the truncated copyOut's first byte")
                    case Result.Panic(t) =>
                        fail(s"panicked waiting for the truncated copyOut's first byte: ${t.getMessage}")
                    case Result.Success(bytes) =>
                        Abort.run[Timeout](
                            Async.timeout(2.seconds)(channel.send(Query("SELECT 1"))(using channel.marshallers.query))
                        ).flatMap {
                            case Result.Success(_) =>
                                written(conn).map { frames =>
                                    assert(
                                        // ONE BYTE, not one frame. `copyOut` returns `Stream[Byte, ...]`, so `take(1)`
                                        // takes a single byte out of the first CopyData frame's "ab", not the frame.
                                        // Reading the take as frame-granular would expect `"ab"`; truncation mid-frame
                                        // is the more abrupt case, and it is the one this leaf is about.
                                        bytes == Chunk.from("a".getBytes(StandardCharsets.US_ASCII)),
                                        s"the byte consumed before truncation must arrive intact; got $bytes"
                                    )
                                    assert(
                                        frames == Chunk(
                                            ('Q', "COPY t TO STDOUT"),
                                            ('f', "COPY TO STDOUT stream closed by client"),
                                            ('Q', "SELECT 1")
                                        ),
                                        s"the scope exit must abort the abandoned transfer before the next send; wrote $frames"
                                    )
                                }
                            case other =>
                                fail(s"the send blocked after the truncated stream's scope closed: $other")
                        }
                }
            }
        }
    }

    "an abandoned copyOut does not deadlock the next writer: the send runs the cleanup and proceeds" in {
        Scope.run {
            withChannel(
                copyOutResponse,
                copyData("ab"),
                copyData("cd"),
                copyDone,
                commandComplete("COPY 2"),
                readyForQuery
            ) { (channel, conn) =>
                // The stream is truncated and NO scope has closed: the finalizer is parked in this leaf's
                // outer scope, which is exactly a pinned session's shape, where the enclosing scope is the
                // transaction's lease and the next statement arrives before it closes. The send must claim
                // the pending cleanup, drain the abandoned transfer, and proceed on its own.
                // Bounded like the leaf above. No Scope.run here, deliberately: this leaf's whole point is that
                // NO scope has closed, so discharging one would destroy the property under test.
                Abort.run[Timeout](Async.timeout(30.seconds)(
                    CopyExchange.copyOut(channel, "COPY t TO STDOUT", 7L, 5.seconds).take(1).run
                )).flatMap {
                    case Result.Failure(_: Timeout) =>
                        fail("timed out after 30s waiting for the abandoned copyOut's first byte")
                    case Result.Panic(t) =>
                        fail(s"panicked waiting for the abandoned copyOut's first byte: ${t.getMessage}")
                    case Result.Success(bytes) =>
                        Abort.run[Timeout](
                            Async.timeout(2.seconds)(channel.send(Query("SELECT 1"))(using channel.marshallers.query))
                        ).flatMap {
                            case Result.Success(_) =>
                                written(conn).map { frames =>
                                    assert(
                                        // ONE BYTE, not one frame. `copyOut` returns `Stream[Byte, ...]`, so `take(1)`
                                        // takes a single byte out of the first CopyData frame's "ab", not the frame.
                                        // The original expectation here was `"ab"`, which read the take as
                                        // frame-granular; that is the only thing that was wrong, and truncation is if
                                        // anything more abrupt this way, which is what the leaf is about.
                                        bytes == Chunk.from("a".getBytes(StandardCharsets.US_ASCII)),
                                        s"the byte consumed before truncation must arrive intact; got $bytes"
                                    )
                                    assert(
                                        frames == Chunk(
                                            ('Q', "COPY t TO STDOUT"),
                                            ('f', "COPY TO STDOUT stream closed by client"),
                                            ('Q', "SELECT 1")
                                        ),
                                        s"the writer must abort the abandoned transfer before its own frame; wrote $frames"
                                    )
                                }
                            case other =>
                                fail(s"the send deadlocked on an abandoned COPY's latch: $other")
                        }
                }
            }
        }
    }

    "a server error during COPY resolves the cleanup at the barrier and leaves the channel usable" in {
        Scope.run {
            withChannel(errorResponse("42P01", "relation missing"), readyForQuery, readyForQuery) { (channel, conn) =>
                // The error arm drains to ReadyForQuery itself, so the wire is idle when the failure
                // surfaces. Firing CopyFail from the finalizer at that point would wait the whole cleanup
                // budget for a reply that never comes and then corrupt a healthy connection.
                Abort.run[SqlException](Scope.run(CopyExchange.copyOut(channel, "COPY t TO STDOUT", 7L, 300.millis).run)).flatMap {
                    case Result.Failure(e: SqlServerException) =>
                        assert(e.sqlState == "42P01", s"the server's own error must surface; got $e")
                        Abort.run[Timeout](
                            Async.timeout(2.seconds)(channel.send(Query("SELECT 1"))(using channel.marshallers.query))
                        ).flatMap {
                            case Result.Success(_) =>
                                written(conn).map { frames =>
                                    assert(
                                        frames == Chunk(('Q', "COPY t TO STDOUT"), ('Q', "SELECT 1")),
                                        s"a failed COPY that drained its own error owes no CopyFail; wrote $frames"
                                    )
                                }
                            case other =>
                                fail(s"the channel was left blocked or corrupted after a server-side COPY error: $other")
                        }
                    case other =>
                        fail(s"expected the server's typed error, got: $other")
                }
            }
        }
    }

    "a NOTIFY arriving between CommandComplete and ReadyForQuery does not fail a completed transfer" in {
        Scope.run {
            withChannel(
                copyOutResponse,
                copyData("ab"),
                copyDone,
                commandComplete("COPY 1"),
                notificationResponse(42, "events", "payload"),
                readyForQuery
            ) { (channel, _) =>
                // A listening session's server defers notification delivery to end-of-statement, which lands
                // it exactly in the CommandComplete-to-ReadyForQuery window this drain reads.
                CopyExchange.copyOut(channel, "COPY t TO STDOUT", 7L, 5.seconds).run.map { bytes =>
                    assert(
                        bytes == Chunk.from("ab".getBytes(StandardCharsets.US_ASCII)),
                        s"the transfer succeeded and its bytes must arrive intact; got $bytes"
                    )
                }
            }
        }
    }

end CopyExchangeTest
