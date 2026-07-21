package kyo.internal.mysql

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.marshaller.Marshallers
import kyo.internal.mysql.unmarshaller.GenericResponseUnmarshaller
import kyo.internal.mysql.unmarshaller.Unmarshallers
import kyo.net.Connection

/** Wraps a [[kyo.net.Connection]] with typed send/receive for the MySQL wire protocol.
  *
  * Responsibilities:
  *   - Serialises frontend messages to framed MySQL packets and writes them.
  *   - Reads complete logical payloads from the connection (reassembling split packets) and decodes backend messages.
  *   - Tracks the per-command sequence ID: resets to 0 at the start of each new command via [[resetSeq]], increments per write.
  *
  * State (seq counter, accumulation buffer) is mutable and NOT thread-safe. Each [[MysqlChannel]] must be accessed from at most one fiber
  * at a time. The connection pool guarantees serial access.
  *
  * ==Single-fiber invariant for seqId==
  *
  * [[seqId]] is a plain `var` intentionally — it is single-fiber mutable state whose correctness depends on the connection pool's guarantee
  * that each [[MysqlChannel]] is accessed from at most one fiber at a time. [[resetSeq]], [[setSeq]], [[advanceSeq]], and the inline
  * mutations in [[send]]/[[readPayload]] are therefore safe without synchronisation. Do NOT add atomics or locking here; the invariant is
  * maintained by the pool layer, not by the channel.
  */
final class MysqlChannel(
    val conn: Connection,
    val marshallers: Marshallers,
    val unmarshallers: Unmarshallers,
    private val _corrupted: AtomicBoolean,
    private val _cleanupLatch: AtomicRef[Maybe[Latch]]
):
    // Per-command sequence ID (0-255, wraps). Reset to 0 at the start of each new command.
    // Single-fiber state — see class scaladoc for the invariant.
    private var seqId: Int = 0

    // Accumulation buffer for raw TCP bytes received from the server.
    private val buf: AccumulatedBuffer = new AccumulatedBuffer

    /** Returns the current sequence ID (for testing). */
    def currentSeq: Int = seqId

    /** Resets the sequence ID to 0 — call at the start of each new command boundary. */
    def resetSeq(): Unit = seqId = 0

    /** Sets the sequence ID to a specific value — used when cloning the channel across a TLS upgrade to preserve continuity. */
    private[mysql] def setSeq(v: Int): Unit = seqId = v

    /** Advances the sequence ID by `n` — used by [[exchange.LocalInfileExchange]] when writing raw LOCAL_INFILE_DATA packets. */
    private[mysql] def advanceSeq(n: Int): Unit = seqId = (seqId + n) & 0xff

    /** Marks the channel as corrupted after a failed [[exchange.LocalInfileExchange]] cleanup attempt.
      *
      * Once corrupted, all subsequent send/receive operations on this channel fail immediately with [[SqlException.Connection]]. The
      * channel must be discarded (closed and not returned to any pool). Called by [[exchange.LocalInfileExchange]] when the error-path
      * cleanup (empty terminator + drain) itself fails — meaning the TCP stream framing is irrecoverably broken.
      */
    private[mysql] def markCorrupted()(using Frame): Unit < Sync = _corrupted.set(true)

    /** Registers a cleanup latch that blocks subsequent channel operations until cleanup finishes.
      *
      * Called by [[exchange.LocalInfileExchange]] at the very start of the upload, before any bytes are sent. This ensures that any
      * concurrent operation arriving after a cancellation sees either a clean channel or "unusable" — never stale protocol bytes.
      */
    private[mysql] def beginCleanup(latch: Latch)(using Frame): Unit < Sync = _cleanupLatch.set(Maybe.Present(latch))

    /** Clears the cleanup latch, signalling that the channel is no longer mid-cleanup.
      *
      * Must be called (together with [[Latch.release]]) by the cleanup fiber before returning. Always pair with [[beginCleanup]].
      */
    private[mysql] def endCleanup()(using Frame): Unit < Sync = _cleanupLatch.set(Maybe.Absent)

    /** Reads a raw payload without first checking the corruption flag.
      *
      * Used exclusively by [[exchange.LocalInfileExchange]] cleanup code, which runs after [[markCorrupted]] may have already been set (to
      * block concurrent callers) but still needs to drain the server's OK/ERR response so the TCP stream stays in a known state before the
      * connection is discarded.
      */
    private[mysql] def readRawPayloadSkipCheck(using Frame): Span[Byte] < (Async & Abort[SqlException]) = readPayload

    /** Fails immediately if the channel has been marked corrupted by a previous [[exchange.LocalInfileExchange]] cleanup failure.
      *
      * If a cleanup is currently in-flight (signalled by [[_cleanupLatch]]), blocks (uninterruptibly) until cleanup finishes, then
      * re-checks. This prevents the caller from racing with cleanup bytes on the wire.
      */
    private def checkCorrupted()(using Frame): Unit < (Async & Abort[SqlException]) =
        _cleanupLatch.get.flatMap {
            case Maybe.Present(latch) =>
                // A LOCAL INFILE cleanup is in-flight. Block until it releases the latch,
                // then re-check whether the channel was marked corrupted during cleanup.
                // Async.mask makes this wait uninterruptible so the caller cannot skip it.
                Async.mask { latch.await }.andThen(checkCorrupted())
            case Maybe.Absent =>
                _corrupted.get.flatMap { corrupted =>
                    if corrupted then
                        Abort.fail(SqlException.Connection(
                            "MySQL connection is unusable: a LOAD DATA LOCAL INFILE upload was interrupted and protocol recovery failed. Discard this connection.",
                            summon[Frame]
                        ))
                    else
                        (
                    )
                }
        }

    /** Serialises `msg` to a MySQL packet (using the current seqId) and writes it to the connection. */
    def send[T <: FrontendMessage](msg: T)(using m: Marshaller[T])(using Frame): Unit < (Async & Abort[SqlException]) =
        checkCorrupted().andThen {
            Sync.defer {
                val writer = new MysqlBufferWriter
                m.write(msg, writer)
                val payload = writer.toSpan
                val packets = MysqlPacket.writeOne(payload, seqId)
                // Advance seqId by the number of packets written (each packet increments seq by 1)
                seqId = (seqId + packets.size) & 0xff
                // Fast path: single packet (>99% of sends) — use it directly, no copy
                if packets.size == 1 then
                    packets(0)
                else
                    // Slow path: concatenate all packet spans into one write
                    val totalLen = packets.foldLeft(0)(_ + _.size)
                    val allBytes = new Array[Byte](totalLen)
                    var offset   = 0
                    packets.foreach { p =>
                        val arr = p.toArray
                        java.lang.System.arraycopy(arr, 0, allBytes, offset, arr.length)
                        offset += arr.length
                    }
                    Span.from(allBytes)
                end if
            }.flatMap { bytes =>
                Abort.run[Closed](conn.outbound.safe.put(bytes)).flatMap {
                    case Result.Success(_) => ()
                    case Result.Failure(_) => Abort.fail(SqlException.Connection("Connection closed while writing", summon[Frame]))
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] MysqlChannel: write panic: ${t.getMessage}")
                        Abort.fail(SqlException.Connection(s"Write panic: ${t.getMessage}", summon[Frame]))
                }
            }
        }
    end send

    /** Reads the next complete logical payload from the connection and decodes it as a [[BackendMessage]].
      *
      * @param inAuthContext
      *   pass `true` during the authentication phase so 0xFE bytes are decoded as [[AuthSwitchRequest]]
      */
    def receive(inAuthContext: Boolean)(using Frame): BackendMessage < (Async & Abort[SqlException]) =
        readPayload.flatMap { payload =>
            val reader = MysqlBufferReader(payload)
            Abort.run[SqlException.Decode](
                GenericResponseUnmarshaller.read(reader, payload.size, inAuthContext, isStmtPrepareContext = false)
            ).flatMap {
                case Result.Success(msg) => msg
                case Result.Failure(e)   => Abort.fail(SqlException.Connection(s"Message decode failed: ${e.message}", summon[Frame]))
                case Result.Panic(t) =>
                    java.lang.System.err.println(s"[kyo-sql] MysqlChannel: decode panic: ${t.getMessage}")
                    Abort.fail(SqlException.Connection(s"Decode panic: ${t.getMessage}", summon[Frame]))
            }
        }
    end receive

    /** Reads the initial [[HandshakeV10]] from the server (used only during connection setup). */
    def receiveHandshake(using Frame): HandshakeV10 < (Async & Abort[SqlException]) =
        readPayload.flatMap { payload =>
            val reader = MysqlBufferReader(payload)
            Abort.run[SqlException.Decode](unmarshallers.handshakeV10.read(reader)).flatMap {
                case Result.Success(hs) => hs
                case Result.Failure(e)  => Abort.fail(SqlException.Connection(s"Handshake decode failed: ${e.message}", summon[Frame]))
                case Result.Panic(t) =>
                    java.lang.System.err.println(s"[kyo-sql] MysqlChannel: handshake decode panic: ${t.getMessage}")
                    Abort.fail(SqlException.Connection(s"Handshake decode panic: ${t.getMessage}", summon[Frame]))
            }
        }
    end receiveHandshake

    /** Reads the next complete logical payload (reassembling split packets) — exposed for result-set parsers that need raw byte access. */
    def readRawPayload(using Frame): Span[Byte] < (Async & Abort[SqlException]) =
        checkCorrupted().andThen(readPayload)

    /** Reads the next complete logical payload (reassembling split packets) from the TCP stream.
      *
      * After successfully reading a packet, advances [[seqId]] to `receivedSeqId + 1` so the next [[send]] call uses the correct sequence
      * number. The MySQL wire protocol requires seqId to increment by 1 with each packet in a command exchange: server seqId 0 → client
      * must reply with seqId 1 → server acks with seqId 2, and so on.
      */
    private def readPayload(using Frame): Span[Byte] < (Async & Abort[SqlException]) =
        MysqlPacket.readOne(buf) match
            case Maybe.Present((payload, receivedSeqId)) =>
                // Advance seqId so the next send uses receivedSeqId + 1.
                seqId = (receivedSeqId + 1) & 0xff
                payload
            case Maybe.Absent =>
                // Need more bytes from the network
                pullChunk.andThen(readPayload)
    end readPayload

    /** Pulls one chunk of bytes from the connection's inbound channel into the accumulation buffer. */
    private def pullChunk(using Frame): Unit < (Async & Abort[SqlException]) =
        Abort.run[Closed](conn.inbound.safe.take).flatMap {
            case Result.Success(span) =>
                buf.append(span)
            case Result.Failure(_) =>
                Abort.fail(SqlException.Connection("Connection closed while reading", summon[Frame]))
            case Result.Panic(t) =>
                java.lang.System.err.println(s"[kyo-sql] MysqlChannel: read panic: ${t.getMessage}")
                Abort.fail(SqlException.Connection(s"Read panic: ${t.getMessage}", summon[Frame]))
        }
    end pullChunk

end MysqlChannel

object MysqlChannel:
    /** Creates a [[MysqlChannel]] over `conn` with default marshallers and unmarshallers. */
    def apply(conn: Connection)(using Frame): MysqlChannel < Sync =
        AtomicBoolean.init(false).flatMap { corrupted =>
            AtomicRef.init[Maybe[Latch]](Maybe.Absent).map { cleanupLatch =>
                new MysqlChannel(conn, Marshallers.default, Unmarshallers.default, corrupted, cleanupLatch)
            }
        }
end MysqlChannel
