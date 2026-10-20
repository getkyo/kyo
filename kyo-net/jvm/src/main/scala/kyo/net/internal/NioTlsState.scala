package kyo.net.internal

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kyo.*
import kyo.net.NetConnectionClosedException
import kyo.net.internal.transport.OutboundLedger
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.util.GrowableByteBuffer
import scala.annotation.tailrec

/** The TLS session of one JVM NIO connection: a JDK `SSLEngine` and the buffers JSSE needs to drive it non-blocking.
  *
  * Ciphertext accumulates in `netInBuf` and unwraps into `appInBuf`; `wrap` reads the caller's plaintext and writes ciphertext into
  * `netOutBuf`. `decryptAcc` collects one unwrap pass's plaintext, `pendingCiphertext` marks `netOutBuf` as holding a record a previous
  * write or flush could not finish, and `answerDue` marks a handshake message a read consumed that the engine must answer (a TLS 1.3
  * KeyUpdate that requested one back). Every wire byte put in `netOutBuf` and every byte the socket took is counted in the connection's
  * [[OutboundLedger]].
  *
  * Every one of those is private, and every operation that touches them requires [[NioGate.Held]]: the engine and its buffers are not
  * thread-safe, and the selector carrier (reads), caller carriers (reads' pre-checks, writes, close) and the handshake all reach the same
  * connection. Two holders at once interleave unwraps over one `netInBuf`, which the engine reports as a bad record MAC. Operations take
  * data in and return data out, so no promise completes and no callback runs inside one; callers complete reads after the gate is
  * released.
  *
  * Plaintext a read operation decrypts is appended to the carry while the gate is held, so the carry holds plaintext in decrypt order
  * whichever holder decrypted it, and every read takes it from the front ([[takePlaintext]]). The carry and the close-reason flags are
  * readable without the gate.
  */
final private[kyo] class NioTlsState private (
    engine: SSLEngine,
    private var netInBuf: ByteBuffer,
    netOutBuf: ByteBuffer,
    appInBuf: ByteBuffer,
    carried: AtomicRef.Unsafe[Chunk[Array[Byte]]]
):
    import NioTlsState.*

    private val decryptAcc: GrowableByteBuffer = new GrowableByteBuffer
    private var pendingCiphertext: Boolean     = false
    private var answerDue: Boolean             = false
    // Set when an unwrap threw: the buffered ciphertext is no longer a record boundary the engine can resume from.
    private var broken: Boolean               = false
    @volatile private var cleanClose: Boolean = false
    @volatile private var eof: Boolean        = false

    /** Whether the peer's close_notify was consumed (RFC 8446 6.1 orderly close). Terminal once set. */
    def peerCleanClose: Boolean = cleanClose

    /** Whether the inbound stream ended without a close_notify (a bare FIN or a read error, the truncation condition). */
    def peerEof: Boolean = eof

    /** Record an inbound end that was not an orderly close; a no-op once the peer's close_notify was consumed. */
    def markPeerEof(): Unit = if !cleanClose then eof = true

    /** Unwrap the grace-probe `staging` and whatever ciphertext is buffered, staged first. */
    def unwrapBuffered(staging: AtomicRef.Unsafe[Chunk[Array[Byte]]])(using AllowUnsafe, NioGate.Held): Inbound =
        if broken then Inbound.PeerFin
        else
            try
                feedStaging(staging)
                decrypt()
            catch case _: IOException => fail()

    /** [[unwrapBuffered]], then, when that decrypts nothing and the peer has not closed, one socket read into `readBuffer` and an unwrap
      * of what it brought. A read of no bytes is [[Inbound.Pending]].
      */
    def unwrapFromSocket(staging: AtomicRef.Unsafe[Chunk[Array[Byte]]], channel: SocketChannel, readBuffer: ByteBuffer)(using
        AllowUnsafe,
        NioGate.Held
    ): Inbound =
        if broken then Inbound.PeerFin
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
            catch case _: IOException => fail()

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
      * socket write with [[WriteResult.Partial]] at the first plaintext byte not yet encrypted, which is `data.size` when the last record
      * only partly fit: only a later call flushes that record's held ciphertext. A wrap that cannot move fails the write.
      */
    def write(channel: SocketChannel, data: Span[Byte], offset: Int, ledger: OutboundLedger)(using
        AllowUnsafe,
        Frame,
        NioGate.Held
    ): WriteResult =
        try
            if pendingCiphertext && !writeHeld(channel, ledger) then WriteResult.Partial(data, offset)
            else
                pendingCiphertext = false
                val src = ByteBuffer.wrap(data.toArrayUnsafe, offset, data.size - offset)
                // one TLS record per wrap call (~16KB), so a large payload takes several wrap and flush rounds
                @tailrec def wrapLoop(): WriteResult =
                    if !src.hasRemaining then WriteResult.Done
                    else
                        netOutBuf.clear()
                        val result = engine.wrap(src, netOutBuf)
                        netOutBuf.flip()
                        SslEngineStep.of(result) match
                            case SslEngineStep.Progress =>
                                ledger.accept(result.bytesConsumed())
                                ledger.encode(result.bytesConsumed(), result.bytesProduced())
                                if writeHeld(channel, ledger) then wrapLoop()
                                else
                                    // src wraps data's whole array, so its position is the absolute offset of the first unencrypted byte
                                    pendingCiphertext = true
                                    WriteResult.Partial(data, src.position())
                                end if
                            case SslEngineStep.Closed =>
                                WriteResult.Error(
                                    NetConnectionClosedException(
                                        NetConnectionClosedException.Operation.Send,
                                        "the TLS engine's outbound side is closed"
                                    )
                                )
                            case step =>
                                WriteResult.Error(NetConnectionClosedException(
                                    NetConnectionClosedException.Operation.Send,
                                    s"the TLS engine could not encrypt the write ($step)"
                                ))
                        end match
                wrapLoop()
            end if
        catch
            case e: IOException => WriteResult.Error(NetConnectionClosedException(NetConnectionClosedException.Operation.Send, e))
    end write

    /** Writes the record a previous write or flush left held in `netOutBuf`, then the engine's answer to a handshake message a read consumed
      * (a TLS 1.3 KeyUpdate that requested one back), counting both in `ledger`. True when bytes are still held, so the driver flushes again
      * once the socket is writable; a socket failure is recorded in `ledger` and holds nothing.
      */
    def flushTail(channel: SocketChannel, ledger: OutboundLedger)(using AllowUnsafe, Frame, NioGate.Held): Boolean =
        @tailrec def loop(): Boolean =
            if pendingCiphertext then
                if writeHeld(channel, ledger) then
                    pendingCiphertext = false
                    loop()
                else true
            else if answerDue then
                netOutBuf.clear()
                val result = engine.wrap(ByteBuffer.allocate(0), netOutBuf)
                netOutBuf.flip()
                answerDue = result.getHandshakeStatus eq SSLEngineResult.HandshakeStatus.NEED_WRAP
                if SslEngineStep.of(result) == SslEngineStep.Progress then
                    ledger.generate(result.bytesProduced())
                    pendingCiphertext = true
                    loop()
                else
                    answerDue = false
                    false
                end if
            else false
        try loop()
        catch
            case e: IOException =>
                discard(ledger.fail(NetConnectionClosedException(NetConnectionClosedException.Operation.Send, e)))
                false
        end try
    end flushTail

    /** Close the outbound side and send close_notify (RFC 8446 6.1), best-effort and non-blocking: with the kernel send buffer full the
      * alert is dropped and the peer sees a bare FIN. Never throws.
      */
    def closeOutbound(channel: SocketChannel)(using AllowUnsafe, NioGate.Held): Unit =
        try
            engine.closeOutbound()
            // a scratch buffer, not netOutBuf: netOutBuf may still hold the unflushed tail of a record a write left pending
            val alert = ByteBuffer.allocate(engine.getSession.getPacketBufferSize)
            discard(engine.wrap(ByteBuffer.allocate(0), alert))
            alert.flip()
            if alert.hasRemaining then discard(channel.write(alert))
        catch case _: Exception => ()

    /** Append handshake ciphertext read before the handshake started (a STARTTLS pre-read or salvage) to `netInBuf`. */
    def feedCiphertext(bytes: Array[Byte])(using AllowUnsafe, NioGate.Held): Unit =
        append(ByteBuffer.wrap(bytes))

    def handshakeStatus(using AllowUnsafe, NioGate.Held): SSLEngineResult.HandshakeStatus =
        engine.getHandshakeStatus

    /** Append `fresh` to the buffered ciphertext and run one handshake unwrap over it. [[HandshakeUnwrap.Empty]] when there is nothing
      * buffered. Application plaintext the unwrap produced is returned when `keepPlaintext`, and dropped otherwise.
      */
    def handshakeUnwrap(fresh: Maybe[Array[Byte]], keepPlaintext: Boolean)(using AllowUnsafe, NioGate.Held): HandshakeUnwrap =
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
    end handshakeUnwrap

    /** Wrap one handshake flight and write it once; true when it was written whole, false when [[flushOutbound]] must finish it. */
    def handshakeWrap(channel: SocketChannel)(using AllowUnsafe, NioGate.Held): Boolean =
        netOutBuf.clear()
        discard(engine.wrap(ByteBuffer.allocate(0), netOutBuf))
        netOutBuf.flip()
        discard(channel.write(netOutBuf))
        !netOutBuf.hasRemaining
    end handshakeWrap

    /** Write the rest of a handshake flight; true when nothing is left. */
    def flushOutbound(channel: SocketChannel)(using AllowUnsafe, NioGate.Held): Boolean =
        discard(channel.write(netOutBuf))
        !netOutBuf.hasRemaining

    def runDelegatedTasks()(using AllowUnsafe, NioGate.Held): Unit =
        @tailrec def loop(): Unit =
            val task = engine.getDelegatedTask
            if task ne null then
                task.run()
                loop()
        end loop
        loop()
    end runDelegatedTasks

    /** Disable renegotiation once the handshake is done. */
    def finishHandshake()(using AllowUnsafe, NioGate.Held): Unit =
        engine.setEnableSessionCreation(false)

    /** Unwrap the ciphertext left buffered at handshake completion, followed by `extra`, into application plaintext, one array per record.
      * A trailing partial record stays buffered for the first socket read.
      */
    def drainLeftover(extra: Array[Byte])(using AllowUnsafe, NioGate.Held): Chunk[Array[Byte]] =
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
    end drainLeftover

    /** The RFC 5929 tls-server-end-point hash of the peer's leaf certificate; Absent when the peer presented none. */
    def peerEndPointHash(using AllowUnsafe, NioGate.Held): Maybe[Span[Byte]] =
        JdkSslEngine.peerEndPointHash(engine)

    /** Whether this session drives `candidate`. Inspection only. */
    private[internal] def drives(candidate: SSLEngine): Boolean = engine eq candidate

    /** The capacities of `netInBuf`, `netOutBuf` and `appInBuf`. Inspection only. */
    private[internal] def bufferCapacities: (Int, Int, Int) = (netInBuf.capacity(), netOutBuf.capacity(), appInBuf.capacity())

    /** The plaintext byte count of the last unwrap pass. Inspection only. */
    private[internal] def lastDecryptSize: Int = decryptAcc.size

    /** Whether `netOutBuf` holds a record a write could not flush. Inspection only. */
    private[internal] def holdsCiphertext: Boolean = pendingCiphertext

    /** The decrypted plaintext bytes waiting for a read. Inspection only. */
    private[internal] def carriedSize(using AllowUnsafe): Int = carried.get().foldLeft(0)(_ + _.length)

    private def fail(): Inbound =
        broken = true
        discard(netInBuf.clear())
        markPeerEof()
        Inbound.PeerFin
    end fail

    /** Writes what `netOutBuf` holds, counting what the socket took; true when nothing is left. */
    private def writeHeld(channel: SocketChannel, ledger: OutboundLedger)(using AllowUnsafe): Boolean =
        val n = channel.write(netOutBuf)
        if n > 0 then ledger.send(n)
        !netOutBuf.hasRemaining
    end writeHeld

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
            @tailrec def unwrapLoop(): SslEngineStep =
                appInBuf.clear()
                val result = engine.unwrap(netInBuf, appInBuf)
                val step   = SslEngineStep.of(result)
                // A close_notify the engine wants to answer is left to the close; only a handshake message is answered on the read.
                if step != SslEngineStep.Closed && (result.getHandshakeStatus eq SSLEngineResult.HandshakeStatus.NEED_WRAP) then
                    answerDue = true
                if step == SslEngineStep.Progress then
                    appInBuf.flip()
                    val n = appInBuf.remaining()
                    if n > 0 then
                        decryptAcc.ensureCapacityFor(n)
                        appInBuf.get(decryptAcc.array, decryptAcc.size, n)
                        decryptAcc.advance(n)
                    end if
                    unwrapLoop()
                else step
                end if
            end unwrapLoop
            val last = unwrapLoop()
            // close_notify coalesced behind the last application record can be consumed by an unwrap that reports OK
            if last == SslEngineStep.Closed || engine.isInboundDone then cleanClose = true
            discard(netInBuf.compact())
            if decryptAcc.size > 0 then carry(decryptAcc.toByteArray)
            last match
                case SslEngineStep.NeedTask =>
                    broken = true
                    Inbound.Failed("the peer started a renegotiation, which the transport does not run")
                case SslEngineStep.NoProgress =>
                    broken = true
                    Inbound.Failed("the TLS engine made no progress on buffered ciphertext")
                case _ =>
                    if decryptAcc.size > 0 then Inbound.Decrypted
                    else if cleanClose then Inbound.CleanClose
                    else Inbound.Pending
            end match
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
            AtomicRef.Unsafe.init[Chunk[Array[Byte]]](Chunk.empty)
        )
    end init

    /** The outcome of a read-side unwrap. */
    enum Inbound derives CanEqual:
        /** Plaintext was decrypted and is waiting in the carry ([[NioTlsState.takePlaintext]]). */
        case Decrypted

        /** Nothing decrypted, and the peer's close_notify has been consumed. */
        case CleanClose

        /** The inbound stream ended without a close_notify, or failed. */
        case PeerFin

        /** Nothing decrypted yet: more ciphertext is needed. */
        case Pending

        /** The engine cannot go on with the inbound stream, for `reason`. */
        case Failed(reason: String)
    end Inbound

    /** The outcome of one handshake unwrap. */
    enum HandshakeUnwrap derives CanEqual:
        case Empty
        case Unwrapped(status: SSLEngineResult.Status, plaintext: Array[Byte])
end NioTlsState
