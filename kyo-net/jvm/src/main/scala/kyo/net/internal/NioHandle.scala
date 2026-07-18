package kyo.net.internal

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLEngine
import kyo.*
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.util.HandleId
import scala.annotation.tailrec

/** One entry in the read-arm owner table. Each [[NioHandle.armRead]] call wraps the caller's promise in a freshly allocated `ReadArmCell`
  * object. The selector carrier completes ONLY the current owner's promise by CAS-ing on the cell object reference
  * (AtomicReference.compareAndSet uses reference equality): a stale arm from an earlier `armRead` holds a different `ReadArmCell` object,
  * so its CAS fails even if the underlying promise is the same object. Fresh allocation per arm is the orphan guard: the `new` in every
  * `ReadArmCell(promise)` construction produces a distinct heap object, so two arms carrying the same promise reference are still
  * distinguishable by their cell object identity.
  */
final private[kyo] case class ReadArmCell(
    promise: Promise.Unsafe[ReadOutcome, Abort[Closed]]
)

/** JVM connection handle wrapping a non-blocking `SocketChannel` and a persistent read buffer.
  *
  * The `readBuffer` is a direct `ByteBuffer` allocated once at handle creation and reused for all socket reads, eliminating per-read
  * allocation overhead.
  *
  * TLS mode is toggled via `tls`:
  *   - `Absent` during TCP setup and TLS handshake: the driver reads raw ciphertext and feeds it to `NioTransport.driveHandshake`.
  *   - `Present(state)` after handshake completes: the driver unwraps/wraps inline for every data read/write.
  *
  * Lifecycle: `init` allocates the read buffer; `close` closes the `SSLEngine` (if present) and the channel (buffer is GC'd).
  */
final private[kyo] class NioHandle private (
    val channel: SocketChannel,
    val readBufferSize: Int,
    @volatile var tls: Maybe[NioTlsState]
):
    import AllowUnsafe.embrace.danger
    val readBuffer: ByteBuffer = ByteBuffer.allocateDirect(readBufferSize)
    // System.identityHashCode: identity-based seed for the handle id; fully qualified so kyo.System does not shadow it.
    val id: HandleId = HandleId.next(java.lang.System.identityHashCode(channel))
    // Per-connection engine ownership gate. Acquired by the selector-carrier unwrap path (dispatchReadTls)
    // and the caller-carrier wrap path (writeTls) before any SSLEngine call, so wrap and unwrap never
    // overlap for one connection. The gate is also acquired before engine.closeOutbound / final wrap in
    // the close path, so a close cannot overlap an in-progress engine op.
    // Unsafe: AtomicBoolean.Unsafe initialized at object-construction time inside the class body.
    val engineGate: AtomicBoolean.Unsafe = AtomicBoolean.Unsafe.init(false)
    // The read-arm OWNER cell. Each armRead installs a fresh ReadArmCell (a new wrapper allocation
    // per arm); the selector carrier completes ONLY the current owner's promise via a reference-equality
    // CAS on the stored cell. A stale arm holds an older ReadArmCell heap object; even when both arms
    // carry the same promise, the two wrapper objects are distinct, so the stale CAS fails.
    // Unsafe: AtomicRef.Unsafe initialized at object-construction time inside the class body.
    val readArm: AtomicRef.Unsafe[Maybe[ReadArmCell]] = AtomicRef.Unsafe.init(Absent)

    // STARTTLS handoff guard. Set true for the duration of a STARTTLS upgrade on this handle (from before the plaintext detach until the
    // handshake completes). While set, a plaintext read whose delivery the upgrade's inbound close has already failed is SALVAGED
    // (onInboundClosedDuringRead) instead of dropped: those bytes are the peer's first TLS flight, which the handshake needs. Mirrors
    // PosixHandle.upgradeActive.
    @volatile var upgrading: Boolean = false
    // Set true once the TLS handshake takes over reading (startTlsHandshake, after the plaintext detach). It splits the upgrade window into two
    // phases. PLAINTEXT phase (`upgrading && !handshakeReading`): a dispatched socket read is SALVAGED, never delivered to the plaintext pump's
    // read promise, so the retiring pump cannot complete-and-re-arm and STEAL the read from the handshake (nor offer to the closed inbound and
    // DROP the peer's first flight). HANDSHAKE phase (`handshakeReading`): the pump is gone and a dispatched read completes the handshake's read
    // normally. Cleared with `upgrading` at handshake completion.
    @volatile var handshakeReading: Boolean = false
    // Set true at STARTTLS completion (the FINISHED branch) so the upgraded connection's FIRST ReadPump read arm forces an unconditional
    // selector.wakeup(). That arm's OP_READ set is a cross-carrier interestOps read-modify-write that can be lost to the selector's own write
    // (the JDK selector is level-triggered, so a lost OP_READ means the channel is simply never reported); the normal recovery is the poll
    // carrier's reassertPendingInterest, but on the tight repeated-upgrade loop the selector quiesces between rounds and the guarded (coalesced)
    // wakeup can skip, so reassert never runs and the read strands. The unconditional wakeup guarantees one poll cycle, where reassert re-applies
    // OP_READ on the selector carrier. Consumed (cleared) by the first armRead after completion. Mirrors PosixHandle's epoll forceReadRecovery.
    @volatile var forceReadArmWakeup: Boolean = false
    // Bytes the plaintext ReadPump pulled off the socket during the upgrade window but could not deliver (the inbound channel was already closed
    // by the upgrade detach), the peer's first TLS flight (e.g. the ClientHello). Salvaged here rather than dropped, and drained exactly once by
    // the handshake (startTlsHandshake feeds it into the engine's netInBuf before the first handshake read).
    // Unsafe: AtomicRef.Unsafe initialized at object-construction time inside the class body.
    val upgradeSalvage: AtomicRef.Unsafe[Chunk[Array[Byte]]] = AtomicRef.Unsafe.init[Chunk[Array[Byte]]](Chunk.empty)

    // STARTTLS upgrade-read handoff slot (the structural confinement). During an upgrade the SELECTOR carrier is the sole reader and interest owner:
    // dispatchReadPlain reads the peer flight and hands it to the handshake through this one slot, so the handshake fiber never arms OP_READ itself
    // (which would race the selector's own interestOps read-modify-write and lose the bit, stranding the handshake). The handshake parks a Waiter
    // here; the selector fulfils it, or stages a Carryover when it read before the handshake parked. Both sides CAS the one slot so exactly one wins
    // each transition. NioIoDriver.cleanupPending swings it to Idle and fails any parked Waiter Closed. Mirrors PosixHandle.upgradeHandoff.
    // Unsafe: AtomicRef.Unsafe initialized at object-construction time inside the class body.
    val upgradeHandoff: AtomicRef.Unsafe[NioHandle.UpgradeHandoff] = AtomicRef.Unsafe.init(NioHandle.UpgradeHandoff.Idle)

    // Application plaintext the TLS handshake's unwrap decrypted while still in the upgrade window: when a peer sends application data coalesced
    // with (or right after) its final handshake flight, driveHandshake's unwrap yields that plaintext into appInBuf. It belongs to the upgraded
    // connection, not the handshake, so it is captured here instead of discarded and delivered to the upgraded connection's inbound at handshake
    // completion (completeConnect), the NIO analog of PosixTransport.deliverHandshakePlaintext. Empty for a fresh (non-upgrade) handshake.
    // Unsafe: AtomicRef.Unsafe initialized at object-construction time inside the class body.
    val upgradeAppData: AtomicRef.Unsafe[Chunk[Array[Byte]]] = AtomicRef.Unsafe.init[Chunk[Array[Byte]]](Chunk.empty)
end NioHandle

/** Factory and lifecycle operations for `NioHandle`. */
private[kyo] object NioHandle:

    val DefaultReadBufferSize: Int = 8192

    /** The STARTTLS upgrade-read handoff state (see [[NioHandle.upgradeHandoff]]). The selector carrier (the producer, [[NioIoDriver]]'s
      * `dispatchReadPlain`) and the handshake carrier (the consumer, [[NioTransport]]'s `driveHandshake`) run on different carriers; this one
      * state, swung by CAS, lets exactly one side win each transition so the bytes always meet the parked waiter:
      *   - [[Idle]]: neither side has acted yet.
      *   - [[Carryover]]: the selector read a peer flight before the handshake parked; the handshake's next read consumes it.
      *   - [[Waiter]]: the handshake parked before the selector read; the selector fulfils this fiber-parking promise with the bytes.
      */
    private[kyo] enum UpgradeHandoff:
        case Idle
        case Carryover(bytes: Array[Byte])
        case Waiter(promise: Promise.Unsafe[Span[Byte], Abort[Closed]], frame: Frame)
    end UpgradeHandoff

    /** Create a plain-TCP handle with an allocated direct read buffer. */
    def init(channel: SocketChannel, bufferSize: Int)(using AllowUnsafe): NioHandle =
        new NioHandle(channel, bufferSize, Absent)
    end init

    /** Create a TLS-enabled handle. TLS buffers are sized from the `SSLEngine` session's recommended capacities. */
    def initTls(channel: SocketChannel, bufferSize: Int, engine: SSLEngine)(using AllowUnsafe): NioHandle =
        val session   = engine.getSession
        val netInBuf  = ByteBuffer.allocate(session.getPacketBufferSize)
        val netOutBuf = ByteBuffer.allocate(session.getPacketBufferSize)
        val appInBuf  = ByteBuffer.allocate(session.getApplicationBufferSize)
        val tlsState  = NioTlsState(engine, netInBuf, netOutBuf, appInBuf)
        new NioHandle(channel, bufferSize, Present(tlsState))
    end initTls

    /** Close the handle: send TLS close_notify if TLS (best-effort, non-blocking), then close channel. */
    def close(handle: NioHandle)(using AllowUnsafe): Unit =
        handle.tls.foreach { tls =>
            // Unsafe: spinAcquire spins on engineGate (AtomicBoolean.Unsafe). The spin is bounded because
            // the gate is held only for the duration of one engine wrap cycle (brief), so progress is
            // guaranteed. Acquired before closeOutbound/wrap so close_notify generation is serialized with
            // any in-progress engine op (dispatchReadTls or writeTls) that may be running concurrently.
            // Released in finally so a throw in closeOutbound or wrap does not leak the gate permanently.
            @tailrec def spinAcquire(): Unit =
                if !handle.engineGate.compareAndSet(false, true) then spinAcquire()
            spinAcquire()
            try
                tls.engine.closeOutbound()
                // Generate the close_notify alert record (RFC 8446 6.1 / RFC 5246 7.2.1). The wrap call
                // produces the alert into a scratch buffer to avoid a data race with the selector thread's
                // tls.netOutBuf. The send is best-effort and non-blocking: if the kernel send buffer is full
                // the alert is dropped and the peer observes a bare FIN (truncation-observable), but that is
                // the same outcome as not sending it at all, and the close still completes immediately.
                val empty  = ByteBuffer.allocate(0)
                val notify = ByteBuffer.allocate(tls.engine.getSession.getPacketBufferSize)
                tls.engine.wrap(empty, notify)
                notify.flip()
                if notify.hasRemaining then discard(handle.channel.write(notify))
            catch case _: Exception => ()
            finally handle.engineGate.set(false)
            end try
        }
        try handle.channel.close()
        catch case _: java.io.IOException => ()
    end close

end NioHandle
