package kyo.net.internal

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kyo.*
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.util.GrowableByteBuffer
import scala.annotation.tailrec

/** The TLS session of one JVM NIO connection: a JDK `SSLEngine`, the buffers JSSE needs to drive it non-blocking, and the engine's sole
  * ownership gate.
  *
  * Ciphertext accumulates in `netInBuf` and unwraps into `appInBuf`; `wrap` reads the caller's plaintext and writes ciphertext into
  * `netOutBuf`. `decryptAcc` collects one unwrap pass's plaintext, and `pendingCiphertext` marks `netOutBuf` as holding a record a previous
  * write could not flush.
  *
  * Every one of those is private, and every operation that touches them acquires `gate` first and releases it before returning. The engine
  * and its buffers are not thread-safe, and the selector carrier (reads), caller carriers (reads' pre-checks, writes, close) and the handshake
  * all reach the same connection: two holders at once interleave unwraps over one `netInBuf`, which the engine reports as a bad record MAC
  * and the connection does not survive. Operations take data in and return data out, so no promise completes and no callback runs while the
  * gate is held; callers complete reads after the operation returns.
  *
  * Read operations try the gate and report [[NioTlsState.Inbound.Busy]] rather than wait, because the selector carrier must not spin.
  * Plaintext they decrypt is appended to the carry before the gate is released, so the carry holds plaintext in decrypt order whichever
  * holder decrypted it, and every read takes it from the front ([[takePlaintext]]). Writes, close and the handshake spin on the gate: each
  * holds it for one bounded engine operation.
  *
  * The close-reason flags (`peerCleanClose`, `peerEof`) are `@volatile` and readable without the gate, for the connection's `status`.
  */
final private[kyo] class NioTlsState private (
    engine: SSLEngine,
    private var netInBuf: ByteBuffer,
    netOutBuf: ByteBuffer,
    appInBuf: ByteBuffer,
    gate: AtomicBoolean.Unsafe,
    carried: AtomicRef.Unsafe[Chunk[Array[Byte]]]
):
    import NioTlsState.*

    private val decryptAcc: GrowableByteBuffer = new GrowableByteBuffer
    private var pendingCiphertext: Boolean     = false
    @volatile private var cleanClose: Boolean  = false
    @volatile private var eof: Boolean         = false

    /** Whether the peer's close_notify was consumed (RFC 8446 6.1 orderly close). Terminal once set. */
    def peerCleanClose: Boolean = cleanClose

    /** Whether the inbound stream ended without a close_notify (a bare FIN or a read error, the truncation condition). */
    def peerEof: Boolean = eof

    /** Record an inbound end that was not an orderly close; a no-op once the peer's close_notify was consumed. */
    def markPeerEof(): Unit = if !cleanClose then eof = true

    /** Unwrap the grace-probe `staging` and whatever ciphertext is buffered, staged first. `staging` is drained only once the gate is
      * held, so on [[Inbound.Busy]] it is left for the holder or a later attempt.
      */
    def unwrapBuffered(staging: AtomicRef.Unsafe[Chunk[Array[Byte]]])(using AllowUnsafe): Inbound =
        if !gate.compareAndSet(false, true) then Inbound.Busy
        else
            try
                feedStaging(staging)
                decrypt()
            catch
                case _: IOException =>
                    markPeerEof()
                    Inbound.PeerFin
            finally gate.set(false)

    /** [[unwrapBuffered]], then, when that decrypts nothing and the peer has not closed, one socket read into `readBuffer` and an unwrap
      * of what it brought. A read of no bytes is [[Inbound.Pending]].
      */
    def unwrapFromSocket(staging: AtomicRef.Unsafe[Chunk[Array[Byte]]], channel: SocketChannel, readBuffer: ByteBuffer)(using
        AllowUnsafe
    ): Inbound =
        if !gate.compareAndSet(false, true) then Inbound.Busy
        else
            try
                feedStaging(staging)
                decrypt() match
                    case Inbound.Pending =>
                        readBuffer.clear()
                        val n = channel.read(readBuffer)
                        if n < 0 then
                            markPeerEof()
                            Inbound.PeerFin
                        else if n == 0 then Inbound.Pending
                        else
                            readBuffer.flip()
                            append(readBuffer)
                            decrypt()
                        end if
                    case other => other
                end match
            catch
                case _: IOException =>
                    markPeerEof()
                    Inbound.PeerFin
            finally gate.set(false)

    /** Whether decrypted plaintext is waiting for a read. */
    def hasPlaintext(using AllowUnsafe): Boolean = !carried.get().isEmpty

    /** Take all decrypted plaintext waiting for a read, oldest first. */
    def takePlaintext()(using AllowUnsafe): Maybe[Array[Byte]] =
        val taken = carried.getAndSet(Chunk.empty)
        if taken.isEmpty then Absent else Present(NioIoDriver.concatChunks(taken))

    /** Put plaintext taken by [[takePlaintext]] back ahead of anything decrypted since, when no read could take it. */
    def returnPlaintext(bytes: Array[Byte])(using AllowUnsafe): Unit =
        @tailrec def loop(): Unit =
            val cur = carried.get()
            if !carried.compareAndSet(cur, Chunk(bytes).concat(cur)) then loop()
        loop()
    end returnPlaintext

    /** Encrypt and send `data` from `offset`, after flushing a record a previous write left in `netOutBuf`. Stops at the first short
      * socket write with [[WriteResult.Partial]] at the first plaintext byte not yet encrypted.
      */
    def write(channel: SocketChannel, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        locked {
            try
                val flushed: Maybe[WriteResult] =
                    if !pendingCiphertext then Absent
                    else
                        val n = channel.write(netOutBuf)
                        if n < 0 then Present(WriteResult.Error)
                        else if netOutBuf.hasRemaining then Present(WriteResult.Partial(data, offset))
                        else
                            pendingCiphertext = false
                            Absent
                        end if
                flushed match
                    case Present(blocked) => blocked
                    case Absent           =>
                        val src = ByteBuffer.wrap(data.toArrayUnsafe, offset, data.size - offset)
                        // one TLS record per wrap call (~16KB), so a large payload takes several wrap and flush rounds
                        @tailrec def wrapLoop(): WriteResult =
                            if !src.hasRemaining then WriteResult.Done
                            else
                                netOutBuf.clear()
                                val result = engine.wrap(src, netOutBuf)
                                netOutBuf.flip()
                                if result.getStatus eq SSLEngineResult.Status.BUFFER_OVERFLOW then WriteResult.Error
                                else
                                    val n = channel.write(netOutBuf)
                                    if n < 0 then WriteResult.Error
                                    else if netOutBuf.hasRemaining then
                                        // src wraps data's whole array, so its position is the absolute offset of the first unencrypted byte
                                        pendingCiphertext = true
                                        WriteResult.Partial(data, src.position())
                                    else wrapLoop()
                                    end if
                                end if
                        wrapLoop()
                end match
            catch
                case _: IOException => WriteResult.Error
        }

    /** Close the outbound side and send close_notify (RFC 8446 6.1), best-effort and non-blocking: with the kernel send buffer full the
      * alert is dropped and the peer sees a bare FIN. Never throws.
      */
    def closeOutbound(channel: SocketChannel)(using AllowUnsafe): Unit =
        locked {
            try
                engine.closeOutbound()
                // a scratch buffer, not netOutBuf: netOutBuf may still hold the unflushed tail of a record a write left pending
                val alert = ByteBuffer.allocate(engine.getSession.getPacketBufferSize)
                discard(engine.wrap(ByteBuffer.allocate(0), alert))
                alert.flip()
                if alert.hasRemaining then discard(channel.write(alert))
            catch case _: Exception => ()
        }

    /** Append handshake ciphertext read before the handshake started (a STARTTLS pre-read or salvage) to `netInBuf`. */
    def feedCiphertext(bytes: Array[Byte])(using AllowUnsafe): Unit =
        locked(append(ByteBuffer.wrap(bytes)))

    def handshakeStatus(using AllowUnsafe): SSLEngineResult.HandshakeStatus =
        locked(engine.getHandshakeStatus)

    /** Append `fresh` to the buffered ciphertext and run one handshake unwrap over it. [[HandshakeUnwrap.Empty]] when there is nothing
      * buffered. Application plaintext the unwrap produced is returned when `keepPlaintext`, and dropped otherwise.
      */
    def handshakeUnwrap(fresh: Maybe[Array[Byte]], keepPlaintext: Boolean)(using AllowUnsafe): HandshakeUnwrap =
        locked {
            fresh.foreach(bytes => append(ByteBuffer.wrap(bytes)))
            netInBuf.flip()
            if !netInBuf.hasRemaining then
                discard(netInBuf.compact())
                HandshakeUnwrap.Empty
            else
                appInBuf.clear()
                val result = engine.unwrap(netInBuf, appInBuf)
                discard(netInBuf.compact())
                HandshakeUnwrap.Unwrapped(result.getStatus, if keepPlaintext then producedPlaintext(result) else Array.emptyByteArray)
            end if
        }

    /** Wrap one handshake flight and write it once; true when it was written whole, false when [[flushOutbound]] must finish it. */
    def handshakeWrap(channel: SocketChannel)(using AllowUnsafe): Boolean =
        locked {
            netOutBuf.clear()
            discard(engine.wrap(ByteBuffer.allocate(0), netOutBuf))
            netOutBuf.flip()
            discard(channel.write(netOutBuf))
            !netOutBuf.hasRemaining
        }

    /** Write the rest of a handshake flight; true when nothing is left. */
    def flushOutbound(channel: SocketChannel)(using AllowUnsafe): Boolean =
        locked {
            discard(channel.write(netOutBuf))
            !netOutBuf.hasRemaining
        }

    def runDelegatedTasks()(using AllowUnsafe): Unit =
        locked {
            @tailrec def loop(): Unit =
                val task = engine.getDelegatedTask
                if task ne null then
                    task.run()
                    loop()
            end loop
            loop()
        }

    /** Disable renegotiation once the handshake is done. */
    def finishHandshake()(using AllowUnsafe): Unit =
        locked(engine.setEnableSessionCreation(false))

    /** Unwrap the ciphertext left buffered at handshake completion, followed by `extra`, into application plaintext, one array per record.
      * A trailing partial record stays buffered for the first socket read.
      */
    def drainLeftover(extra: Array[Byte])(using AllowUnsafe): Chunk[Array[Byte]] =
        locked {
            netInBuf.flip()
            val leftover = netInBuf.remaining()
            if leftover == 0 && extra.length == 0 then
                discard(netInBuf.clear())
                Chunk.empty
            else
                // one exact-sized buffer, so a record split between the leftover and `extra` reassembles
                val combined = ByteBuffer.allocate(leftover + extra.length)
                discard(combined.put(netInBuf))
                discard(combined.put(extra))
                combined.flip()
                discard(netInBuf.clear())
                @tailrec def loop(acc: Chunk[Array[Byte]]): Chunk[Array[Byte]] =
                    if !combined.hasRemaining then acc
                    else
                        appInBuf.clear()
                        val result = engine.unwrap(combined, appInBuf)
                        val next   = if result.bytesProduced() > 0 then acc.append(producedPlaintext(result)) else acc
                        if (result.getStatus eq SSLEngineResult.Status.OK) && result.bytesConsumed() > 0 then loop(next) else next
                val plaintext = loop(Chunk.empty)
                if combined.hasRemaining then discard(netInBuf.put(combined))
                plaintext
            end if
        }

    /** The DER encoding of the peer's leaf certificate; Absent when the peer presented none or the session is not established. */
    def peerLeafCertificate(using AllowUnsafe): Maybe[Array[Byte]] =
        locked {
            try
                val certs = engine.getSession.getPeerCertificates
                if certs == null || certs.isEmpty then Absent else Present(certs(0).getEncoded)
            catch case _: Exception => Absent
        }

    /** Whether some operation holds the gate now. Inspection only. */
    private[internal] def engineHeld(using AllowUnsafe): Boolean = gate.get()

    /** Whether this session drives `candidate`. Inspection only. */
    private[internal] def drives(candidate: SSLEngine): Boolean = engine eq candidate

    /** The capacities of `netInBuf`, `netOutBuf` and `appInBuf`. Inspection only. */
    private[internal] def bufferCapacities: (Int, Int, Int) = (netInBuf.capacity(), netOutBuf.capacity(), appInBuf.capacity())

    /** The plaintext byte count of the last unwrap pass. Inspection only. */
    private[internal] def lastDecryptSize: Int = decryptAcc.size

    private inline def locked[A](inline op: A)(using AllowUnsafe): A =
        acquire()
        try op
        finally gate.set(false)
    end locked

    // Spinning is bounded: no holder runs anything but one engine operation, and none waits on anything while holding the gate.
    @tailrec private def acquire()(using AllowUnsafe): Unit =
        if !gate.compareAndSet(false, true) then acquire()

    private def feedStaging(staging: AtomicRef.Unsafe[Chunk[Array[Byte]]])(using AllowUnsafe): Unit =
        val taken = staging.getAndSet(Chunk.empty)
        taken.foreach(bytes => append(ByteBuffer.wrap(bytes)))

    // netInBuf is reassigned when it must grow: staged and coalesced ciphertext can exceed one packet.
    private def append(src: ByteBuffer): Unit =
        val needed = netInBuf.position() + src.remaining()
        if needed > netInBuf.capacity() then
            val grown = ByteBuffer.allocate(needed)
            netInBuf.flip()
            grown.put(netInBuf)
            netInBuf = grown
        end if
        discard(netInBuf.put(src))
    end append

    private def decrypt()(using AllowUnsafe): Inbound =
        netInBuf.flip()
        if !netInBuf.hasRemaining then
            discard(netInBuf.compact())
            if cleanClose then Inbound.CleanClose else Inbound.Pending
        else
            decryptAcc.reset()
            @tailrec def unwrapLoop(): Unit =
                appInBuf.clear()
                val status = engine.unwrap(netInBuf, appInBuf).getStatus
                if status eq SSLEngineResult.Status.OK then
                    appInBuf.flip()
                    val n = appInBuf.remaining()
                    if n > 0 then
                        decryptAcc.ensureCapacityFor(n)
                        appInBuf.get(decryptAcc.array, decryptAcc.size, n)
                        decryptAcc.advance(n)
                    end if
                    unwrapLoop()
                else if status eq SSLEngineResult.Status.CLOSED then cleanClose = true
                end if
            end unwrapLoop
            unwrapLoop()
            // close_notify coalesced behind the last application record can be consumed by an unwrap that reports OK
            if engine.isInboundDone then cleanClose = true
            discard(netInBuf.compact())
            if decryptAcc.size > 0 then
                carry(decryptAcc.toByteArray)
                Inbound.Decrypted
            else if cleanClose then Inbound.CleanClose
            else Inbound.Pending
            end if
        end if
    end decrypt

    // Called with the gate held, so appends land in decrypt order; the CAS only guards a concurrent takePlaintext.
    private def carry(plaintext: Array[Byte])(using AllowUnsafe): Unit =
        @tailrec def loop(): Unit =
            val cur = carried.get()
            if !carried.compareAndSet(cur, cur.append(plaintext)) then loop()
        loop()
    end carry

    private def producedPlaintext(result: SSLEngineResult): Array[Byte] =
        val n = result.bytesProduced()
        if n == 0 then Array.emptyByteArray
        else
            appInBuf.flip()
            val out = new Array[Byte](n)
            appInBuf.get(out)
            out
        end if
    end producedPlaintext

end NioTlsState

private[kyo] object NioTlsState:

    /** A session over `engine` with its buffers sized from the engine's session. */
    def init(engine: SSLEngine)(using AllowUnsafe): NioTlsState =
        val session = engine.getSession
        new NioTlsState(
            engine,
            ByteBuffer.allocate(session.getPacketBufferSize),
            ByteBuffer.allocate(session.getPacketBufferSize),
            ByteBuffer.allocate(session.getApplicationBufferSize),
            AtomicBoolean.Unsafe.init(false),
            AtomicRef.Unsafe.init[Chunk[Array[Byte]]](Chunk.empty)
        )
    end init

    /** The outcome of a read-side unwrap. */
    enum Inbound derives CanEqual:
        /** Another operation holds the engine; nothing was touched. */
        case Busy

        /** Plaintext was decrypted and is waiting in the carry ([[NioTlsState.takePlaintext]]). */
        case Decrypted

        /** Nothing decrypted, and the peer's close_notify has been consumed. */
        case CleanClose

        /** The inbound stream ended without a close_notify, or failed. */
        case PeerFin

        /** Nothing decrypted yet: more ciphertext is needed. */
        case Pending
    end Inbound

    /** The outcome of one handshake unwrap. */
    enum HandshakeUnwrap derives CanEqual:
        case Empty
        case Unwrapped(status: SSLEngineResult.Status, plaintext: Array[Byte])
end NioTlsState
