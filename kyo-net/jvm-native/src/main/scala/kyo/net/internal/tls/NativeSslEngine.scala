package kyo.net.internal.tls

import kyo.*
import kyo.ffi.Buffer
import kyo.internal.Diagnostics

/** The native [[TlsEngine]] on JVM and Native: a thin wrapper over a live TLS session through any [[SslLibBindings]] backend.
  *
  * `ssl` is the per-SSL state pointer the shim returned from `sslNew` (carried as a `Long`, the shim's `intptr_t` ABI). Every method forwards
  * 1:1 to a neutral `lib` call against that pointer: the engine adds no TLS logic of its own, only the buffer marshalling the surface requires.
  * The backend `lib` is a [[BoringSslBindings]] (the bundled-BoringSSL primary) or an [[OpenSslBindings]] (the system-OpenSSL fallback); both
  * hash the peer leaf DER with the same `i2d_X509` + SHA-256, so [[certSha256]] returns the same 32 bytes for the same certificate on either
  * backend and either platform (RFC 5929 tls-server-end-point), and a session negotiated by either yields the identical channel-binding token.
  *
  * Concurrency: all engine ops for a connection route through the per-driver `submitEngineOp` FIFO, which drains one op at a time on a single
  * dedicated worker carrier. This guarantees that no two callers (read pump, write pump, handshake) can invoke `SSL_read` or `SSL_write` on
  * the same `ssl` object concurrently. [[certSha256]] is read once at handshake completion on that same serialized path and cached by the
  * transport (the leaf cert is fixed for the connection), so the channel-binding query never touches a live `ssl` on the caller's carrier;
  * [[free]] is likewise enqueued on the FIFO, so the SSL teardown is serialized behind every read/write op. Each method is a brief CPU-bound
  * shim call over in-memory BIO buffers with no socket I/O (the driver does all syscalls outside these calls).
  */
final private[net] class NativeSslEngine[B <: SslLibBindings](lib: B, ssl: Long) extends TlsEngine:

    // Guards the native teardown so sslFree (SSL_free + free(st)) runs at most once. The engine has multiple teardown paths that are each
    // documented as mutually exclusive (handshake onFailed/onPanic, the server handshake-deadline teardown, the connect-failure path, and
    // PosixHandle.freeResources via the engine FIFO), but they reach free() through different carriers, so a single missed exclusion would call
    // sslFree twice on the same pointer: SSL_free + free(st) on an already-freed struct corrupts the native heap and aborts the process later at
    // an unrelated allocation (the BoringSSL OPENSSL_malloc / nanov2 corruption seen on Scala Native). The CAS makes free() exactly-once at the
    // single chokepoint, mirroring the exactly-once discipline of PosixHandle.guard, Buffer.close, and claimFdClose.
    //
    // Unsafe: the guard is created at engine construction, where there is no ambient AllowUnsafe; the single danger bridge here builds it, and
    // every compareAndSet below runs under the caller's AllowUnsafe.
    private val freed = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Every op below reads `freed` first and refuses the native call once it is set, reporting a Diagnostics violation naming the op instead
    // of dereferencing `ssl` after `sslFree` released it. Production free/use pairs are single-carrier-serialized (see the class doc), so this
    // volatile read never races a concurrent free: a caller that observes `freed = false` here is guaranteed the native session is still live
    // for the duration of its own call. A caller that reaches this AFTER free() has already run has broken that serialization invariant
    // somewhere upstream; the gate turns what would otherwise be a silent native use-after-free (a wild write into whatever reused the freed
    // allocation, see the SSL_shutdown UAF this was added for) into an attributed, in-process failure.
    private def reportUseAfterFree(op: String): Unit =
        Diagnostics.reportViolation(s"NativeSslEngine.$op called after free(): the native SSL session was already released")

    def handshakeStep()(using AllowUnsafe): Int =
        if freed.get() then
            reportUseAfterFree("handshakeStep")
            -2
        else lib.doHandshakeStep(ssl)

    def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        if freed.get() then
            reportUseAfterFree("feedCiphertext")
            -1
        else lib.feedCiphertext(ssl, buf, len)

    def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        if freed.get() then
            reportUseAfterFree("drainCiphertext")
            0
        else lib.drainCiphertext(ssl, buf, len)

    def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        if freed.get() then
            reportUseAfterFree("readPlain")
            0
        else lib.readPlain(ssl, buf, len)

    def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        if freed.get() then
            reportUseAfterFree("writePlain")
            0
        else lib.writePlain(ssl, buf, len)

    def hasBufferedPlaintext(using AllowUnsafe): Boolean =
        if freed.get() then
            reportUseAfterFree("hasBufferedPlaintext")
            false
        else lib.pending(ssl) > 0

    def readBuffered()(using AllowUnsafe): Span[Byte] =
        if freed.get() then
            reportUseAfterFree("readBuffered")
            Span.empty[Byte]
        else
            val pending = lib.pending(ssl)
            if pending <= 0 then Span.empty[Byte]
            else
                Buffer.use[Byte, Span[Byte]](pending) { out =>
                    val n = lib.readPlain(ssl, out, pending)
                    if n > 0 then Span.fromUnsafe(Buffer.copyToArray[Byte](out, 0, n))
                    else Span.empty[Byte]
                }
            end if
        end if
    end readBuffered

    def certSha256()(using AllowUnsafe): Maybe[Span[Byte]] =
        if freed.get() then
            reportUseAfterFree("certSha256")
            Absent
        else
            Buffer.use[Byte, Maybe[Span[Byte]]](32) { out =>
                val n = lib.peerCertSha256(ssl, out, 32)
                if n == 32 then Present(Span.fromUnsafe(Buffer.copyToArray[Byte](out, 0, 32))) else Absent
            }
        end if
    end certSha256

    def shutdownStep()(using AllowUnsafe): Int =
        if freed.get() then
            reportUseAfterFree("shutdownStep")
            -2
        else lib.shutdownStep(ssl)

    def free()(using AllowUnsafe): Unit =
        if freed.compareAndSet(false, true) then lib.sslFree(ssl)

end NativeSslEngine
