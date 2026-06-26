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
    // The read-arm OWNER cell. Each arm (the plaintext pump's, the handshake's) installs its
    // (token, promise), and the selector carrier completes ONLY the current owner's promise via a
    // single-winner CAS, so a stale pump arm whose token does not match the current owner cannot
    // complete the handshake's read promise. Re-arm ownership is the cell's token, and the upgrade is
    // the ConnectionState.Upgrading transition.
    val readArm: AtomicRef.Unsafe[Maybe[(Long, Promise.Unsafe[ReadOutcome, Abort[Closed]])]] = AtomicRef.Unsafe.init(Absent)
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

    /** Close the handle: close channel, close SSLEngine if TLS (buffer is GC'd). */
    def close(handle: NioHandle)(using AllowUnsafe): Unit =
        handle.tls.foreach { tls =>
            try tls.engine.closeOutbound()
            catch case _: Exception => ()
        }
        try handle.channel.close()
        catch case _: java.io.IOException => ()
    end close

end NioHandle
