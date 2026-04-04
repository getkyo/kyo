package kyo.internal

import kyo.*
import scala.scalanative.unsafe.*

/** TLS stream for Scala Native using OpenSSL with memory BIOs.
  *
  * Architecture (BIO_s_mem based): write(plaintext) → SSL_write(plaintext) → BIO_read(wbio) → ciphertext → underlying.write underlying.read
  * → ciphertext → BIO_write(rbio) → SSL_read → plaintext → read(buf)
  *
  * OpenSSL never touches file descriptors. All network I/O goes through the underlying RawStream (KqueueStreamTlsBridge or similar). This
  * makes the TLS layer portable across both kqueue (macOS) and epoll (Linux) without any platform-specific code here.
  *
  * sslPtr: opaque SSL* from kyo_tls_new (must have connect/accept state set before handshake). ctxPtr: opaque SSL_CTX* — kept alive here so
  * it outlives sslPtr; freed in the transport's closeNow. underlying: TCP stream for sending/receiving ciphertext bytes.
  */
private[kyo] class NativeTlsStream(
    val sslPtr: CLong,
    val ctxPtr: CLong,
    val ownsCtx: Boolean,
    underlying: RawStream
) extends RawStream:

    import TlsBindings.*

    private val BufSize = 16384

    // Pre-allocated buffers to avoid per-call allocation pressure under concurrent load
    private val feedBuf  = new Array[Byte](BufSize)
    private val flushBuf = new Array[Byte](BufSize)

    // Tracks whether handshake() has been called. Allows lazy server-side handshake on first read/write.
    private var handshakeDone: Boolean = false

    /** Perform TLS handshake. Non-blocking, fiber-cooperative.
      *
      * Loop: call SSL_do_handshake one step at a time. want_read (0): flush any output, then read from TCP and feed into rbio. want_write
      * (-1): flush output only. done (1): flush remaining output, we're done. error (-2): fail with HttpConnectException.
      */
    def handshake()(using Frame): Unit < (Async & Abort[HttpException]) =
        handshakeDone = true
        Loop.foreach {
            val result = tlsHandshake(sslPtr)
            if result == 1 then
                // Done — flush any final handshake bytes (e.g. Finished message)
                flushOutput().andThen(Loop.done(()))
            else if result == 0 then // want_read
                flushOutput().andThen {
                    feedInput().map { gotData =>
                        if !gotData then
                            Abort.fail(HttpConnectException(
                                "",
                                0,
                                new Exception("TLS handshake failed: peer closed connection")
                            ))
                        else Loop.continue
                    }
                }
            else if result == -1 then // want_write
                flushOutput().andThen(Loop.continue)
            else // error
                Zone {
                    Abort.fail(HttpConnectException(
                        "",
                        0,
                        new Exception(s"TLS handshake failed: ${fromCString(tlsErrorString())}")
                    ))
                }
            end if
        }
    end handshake

    /** Read encrypted bytes from TCP and feed into OpenSSL's read BIO. Returns true if data was fed, false if EOF (peer closed).
      */
    private def feedInput()(using Frame): Boolean < Async =
        underlying.read(feedBuf).map { n =>
            if n > 0 then
                Zone {
                    val ptr = alloc[Byte](n)
                    var i   = 0
                    while i < n do
                        ptr(i) = feedBuf(i)
                        i += 1
                    end while
                    discard(tlsFeedInput(sslPtr, ptr, n))
                }
                true
            else false
        }
    end feedInput

    /** Drain encrypted bytes from OpenSSL's write BIO and write to TCP. */
    private def flushOutput()(using Frame): Unit < Async =
        Loop.foreach {
            val n = Zone {
                val ptr    = alloc[Byte](BufSize)
                val result = tlsGetOutput(sslPtr, ptr, BufSize)
                if result > 0 then
                    var i = 0
                    while i < result do
                        flushBuf(i) = ptr(i)
                        i += 1
                    end while
                end if
                result
            }
            if n > 0 then
                underlying.write(Span.fromUnsafe(flushBuf.slice(0, n))).andThen(Loop.continue)
            else Loop.done(())
        }
    end flushOutput

    // ── RawStream implementation ──────────────────

    def read(buf: Array[Byte])(using Frame): Int < Async =
        if !handshakeDone then
            Abort.run[HttpException](handshake()).map {
                case Result.Success(_) => readImpl(buf)
                case _                 => -1 // handshake failed — signal EOF
            }
        else readImpl(buf)

    private def readImpl(buf: Array[Byte])(using Frame): Int < Async =
        Loop.foreach {
            // Try to read buffered plaintext from OpenSSL
            val n = Zone {
                val ptr    = alloc[Byte](buf.length)
                val result = tlsRead(sslPtr, ptr, buf.length)
                if result > 0 then
                    var i = 0
                    while i < result do
                        buf(i) = ptr(i)
                        i += 1
                    end while
                end if
                result
            }
            if n > 0 then Loop.done(n)
            else if n == 0 then
                // Need more encrypted data from TCP
                feedInput().map { gotData =>
                    if !gotData then Loop.done(-1) // EOF - peer closed
                    else
                        flushOutput().andThen(Loop.continue)
                }
            else Loop.done(-1) // closed or error
            end if
        }
    end readImpl

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else if !handshakeDone then
            Abort.run[HttpException](handshake()).map {
                case Result.Success(_) => writeImpl(data)
                case _                 => Kyo.unit // handshake failed — caller will see EOF on read
            }
        else writeImpl(data)

    private def writeImpl(data: Span[Byte])(using Frame): Unit < Async =
        Loop(data) { (remaining: Span[Byte]) =>
            val n = Zone {
                val arr = remaining.toArrayUnsafe
                val ptr = alloc[Byte](arr.length)
                var i   = 0
                while i < arr.length do
                    ptr(i) = arr(i)
                    i += 1
                end while
                tlsWrite(sslPtr, ptr, arr.length)
            }
            if n > 0 then
                flushOutput().andThen {
                    if n < remaining.size then Loop.continue(remaining.slice(n, remaining.size))
                    else Loop.done(())
                }
            else if n == 0 then
                flushOutput().andThen(Loop.continue(remaining))
            else
                Zone {
                    Abort.panic(HttpProtocolException(s"TLS write failed: ${fromCString(tlsErrorString())}"))
                }
            end if
        }
    end writeImpl

end NativeTlsStream
