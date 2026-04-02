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
    underlying: RawStream
) extends RawStream:

    import TlsBindings.*

    // TODO do we need to make this configurable? double check this is a reasonable default
    private val BufSize = 16384

    /** Perform TLS handshake. Non-blocking, fiber-cooperative.
      *
      * Loop: call SSL_do_handshake one step at a time. want_read (0): flush any output, then read from TCP and feed into rbio. want_write
      * (-1): flush output only. done (1): flush remaining output, we're done. error (-2): fail with HttpConnectException.
      */
    def handshake()(using Frame): Unit < (Async & Abort[HttpException]) =
        Loop.foreach {
            val result = tlsHandshake(sslPtr)
            if result == 1 then
                // Done — flush any final handshake bytes (e.g. Finished message)
                flushOutput().andThen(Loop.done(()))
            else if result == 0 then // want_read
                flushOutput().andThen {
                    feedInput().andThen(Loop.continue)
                }
            else if result == -1 then // want_write
                flushOutput().andThen(Loop.continue)
            else       // error
                Zone { // TODO Is the zone because of the string? let's make all Zone bodies as small as possible
                    Abort.fail(HttpConnectException(
                        "",
                        0,
                        new Exception(s"TLS handshake failed: ${fromCString(tlsErrorString())}")
                    ))
                }
            end if
        }

    /** Read encrypted bytes from TCP and feed into OpenSSL's read BIO. */
    private def feedInput()(using Frame): Unit < Async =
        val buf = new Array[Byte](BufSize) // TODO can we avoid allocating buffers over and over?
        underlying.read(buf).map { n =>
            if n > 0 then
                Zone {
                    // TODO Why do we need to create a new buffer? Is there a faster way to copy?
                    val ptr = alloc[Byte](n)
                    var i   = 0
                    while i < n do
                        ptr(i) = buf(i)
                        i += 1
                    end while
                    discard(tlsFeedInput(sslPtr, ptr, n))
                }
            else Kyo.unit
        }
    end feedInput

    /** Drain encrypted bytes from OpenSSL's write BIO and write to TCP. */
    private def flushOutput()(using Frame): Unit < Async =
        val arr = new Array[Byte](BufSize)
        val n = Zone {
            val ptr    = alloc[Byte](BufSize)
            val result = tlsGetOutput(sslPtr, ptr, BufSize)
            if result > 0 then
                var i = 0
                while i < result do
                    arr(i) = ptr(i)
                    i += 1
                end while
            end if
            result
        }
        if n > 0 then
            underlying.write(Span.fromUnsafe(arr.slice(0, n))).andThen(flushOutput())
        else Kyo.unit
    end flushOutput

    // ── RawStream implementation ──────────────────

    def read(buf: Array[Byte])(using Frame): Int < Async =
        // Try to read buffered plaintext from OpenSSL first
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
        if n > 0 then n
        else if n == 0 then
            // Need more encrypted data from TCP
            feedInput().andThen {
                flushOutput().andThen(read(buf))
            }
        else -1 // closed or error
        end if
    end read

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else
            val n = Zone {
                val arr = data.toArrayUnsafe
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
                    if n < data.size then write(data.slice(n, data.size))
                    else Kyo.unit
                }
            else if n == 0 then
                flushOutput().andThen(write(data))
            else
                Zone {
                    Abort.panic(HttpProtocolException(s"TLS write failed: ${fromCString(tlsErrorString())}"))
                }
            end if

end NativeTlsStream
