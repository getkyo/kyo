package kyo.internal

import kyo.*
import kyo.AtomicLong
import scala.scalanative.libc.stdlib
import scala.scalanative.unsafe.*

/** Native connection handle wrapping a file descriptor and a `malloc`-allocated read buffer.
  *
  * The `readBuffer` is allocated once at handle creation and reused for every `read(2)` syscall, avoiding per-read allocation and
  * eliminating one buffer copy (kernel writes directly into `Ptr[Byte]`, which is then copied to a right-sized `Array[Byte]`).
  *
  * Each handle carries a monotonically increasing `id` used to detect stale poller events: if a file descriptor is closed and the OS
  * recycles the same number for a new connection, the old `id` no longer matches the active fd entry and the stale event is discarded.
  *
  * TLS mode is toggled via the `@volatile var tls`:
  *   - `Absent` during TCP setup and TLS handshake — the driver reads raw ciphertext and feeds it to `NativeTransport.driveHandshake`.
  *   - `Present(state)` after handshake completes — the driver decrypts/encrypts inline for every data read/write.
  *
  * Lifecycle: `init` / `initTls` allocate buffers; `close` frees TLS pointers, read buffer, and calls `tcpClose`.
  */
final private[kyo] class NativeHandle private (
    val fd: CInt,
    val id: Long,
    val readBufferSize: Int,
    @volatile var tls: Maybe[NativeTlsState]
):
    val readBuffer: Ptr[Byte] = stdlib.malloc(readBufferSize.toLong).asInstanceOf[Ptr[Byte]]
end NativeHandle

/** Factory and lifecycle operations for `NativeHandle`. */
private[kyo] object NativeHandle:
    import PosixBindings.*

    val DefaultReadBufferSize: Int = 8192

    private val idGen: AtomicLong.Unsafe =
        import AllowUnsafe.embrace.danger
        AtomicLong.Unsafe.init(0)

    /** Create a handle with allocated read buffer (plain TCP). */
    def init(fd: CInt, bufferSize: Int)(using AllowUnsafe): NativeHandle =
        new NativeHandle(fd, idGen.getAndIncrement(), bufferSize, Absent)
    end init

    /** Create a handle with TLS state. Allocates persistent TLS read/write buffers. */
    def initTls(fd: CInt, bufferSize: Int, ssl: CLong, ctx: CLong)(using AllowUnsafe): NativeHandle =
        val tlsBufSize  = 32768 // 32KB for TLS records (max TLS record is 16KB + overhead)
        val tlsReadBuf  = stdlib.malloc(tlsBufSize.toLong).asInstanceOf[Ptr[Byte]]
        val tlsWriteBuf = stdlib.malloc(tlsBufSize.toLong).asInstanceOf[Ptr[Byte]]
        val tlsState    = NativeTlsState(ssl, ctx, tlsReadBuf, tlsWriteBuf, tlsBufSize)
        new NativeHandle(fd, idGen.getAndIncrement(), bufferSize, Present(tlsState))
    end initTls

    /** Close the handle: free buffers, free TLS state, close fd. */
    def close(handle: NativeHandle)(using AllowUnsafe): Unit =
        handle.tls.foreach { tlsState =>
            TlsBindings.tlsFree(tlsState.ssl)
            TlsBindings.tlsCtxFree(tlsState.ctx)
            stdlib.free(tlsState.tlsReadBuf.asInstanceOf[Ptr[Byte]])
            stdlib.free(tlsState.tlsWriteBuf.asInstanceOf[Ptr[Byte]])
        }
        stdlib.free(handle.readBuffer.asInstanceOf[Ptr[Byte]])
        tcpClose(handle.fd)
    end close

end NativeHandle
