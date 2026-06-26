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
    val id: HandleId           = HandleId.next(java.lang.System.identityHashCode(channel))
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
end NioHandle

/** Factory and lifecycle operations for `NioHandle`. */
private[kyo] object NioHandle:

    val DefaultReadBufferSize: Int = 8192

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
