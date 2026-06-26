package kyo.net.internal

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLEngine
import kyo.*
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.util.HandleId

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
    // The read-arm OWNER cell. Each arm (the plaintext pump's, the handshake's) installs a fresh
    // Present(promise) object, and the selector carrier completes ONLY the current owner's promise via a
    // reference-equality CAS on the stored cell object: a stale pump arm holding an old cell reference
    // cannot complete the handshake's read promise because the current cell is a different object.
    // The upgrade is the ConnectionState.Upgrading transition.
    val readArm: AtomicRef.Unsafe[Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]]] = AtomicRef.Unsafe.init(Absent)
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
        }
        try handle.channel.close()
        catch case _: java.io.IOException => ()
    end close

end NioHandle
