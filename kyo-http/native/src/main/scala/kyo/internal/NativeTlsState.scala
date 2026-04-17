package kyo.internal

import scala.scalanative.unsafe.*

/** Mutable TLS session state for a Scala Native connection.
  *
  * Wraps raw OpenSSL `SSL*` and `SSL_CTX*` pointers together with two `malloc`-allocated buffers used for non-blocking I/O. Because OpenSSL
  * uses memory BIOs, ciphertext must be manually pumped in and out:
  *   - `tlsReadBuf` receives plaintext from `kyo_tls_read` after ciphertext was fed via `kyo_tls_feed_input`
  *   - `tlsWriteBuf` receives ciphertext from `kyo_tls_get_output` to be written to the socket
  *
  * `pendingCipher` holds any ciphertext that could not be written to the socket due to EAGAIN. The next `writeTls` call flushes it before
  * encrypting new plaintext, maintaining in-order delivery.
  */
final private[kyo] class NativeTlsState(
    val ssl: CLong,
    val ctx: CLong,
    val tlsReadBuf: Ptr[Byte],
    val tlsWriteBuf: Ptr[Byte],
    val bufSize: Int
):
    // Pending ciphertext that couldn't be flushed to socket (EAGAIN).
    // writeTls checks this first and flushes before encrypting new plaintext.
    var pendingCipher: Array[Byte] = Array.emptyByteArray
end NativeTlsState
