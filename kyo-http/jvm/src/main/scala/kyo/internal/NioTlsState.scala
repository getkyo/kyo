package kyo.internal

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine

/** Mutable TLS session state for a JVM NIO connection.
  *
  * Wraps a `javax.net.ssl.SSLEngine` together with the four byte buffers that JSSE requires for non-blocking wrap/unwrap. Because NIO
  * channels are non-blocking, the engine is driven manually: ciphertext arrives in `netInBuf`, plaintext emerges in `appInBuf` after
  * `unwrap`; plaintext enters wrap via the caller's buffer and ciphertext emerges in `netOutBuf`.
  *
  * Buffer roles:
  *   - `netInBuf` — ciphertext accumulator (read from socket, fed to `unwrap`)
  *   - `netOutBuf` — ciphertext output (produced by `wrap`, written to socket)
  *   - `appInBuf` — plaintext output from `unwrap` (decrypted application data)
  *   - `appOutBuf` — not allocated separately; `wrap` reads directly from the caller's `ByteBuffer`
  *
  * Note: `pendingCiphertext` tracks whether `netOutBuf` still holds bytes that could not be flushed on a previous write attempt. The next
  * call to `writeTls` must flush that buffer before encrypting new plaintext.
  */
final private[kyo] class NioTlsState(
    val engine: SSLEngine,
    var netInBuf: ByteBuffer,
    val netOutBuf: ByteBuffer,
    val appInBuf: ByteBuffer,
    var pendingCiphertext: Boolean = false
)
