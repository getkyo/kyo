package kyo.net.internal

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import kyo.net.internal.util.GrowableByteBuffer

/** Mutable TLS session state for a JVM NIO connection.
  *
  * Wraps a `javax.net.ssl.SSLEngine` together with the four byte buffers that JSSE requires for non-blocking wrap/unwrap. Because NIO
  * channels are non-blocking, the engine is driven manually: ciphertext arrives in `netInBuf`, plaintext emerges in `appInBuf` after
  * `unwrap`; plaintext enters wrap via the caller's buffer and ciphertext emerges in `netOutBuf`.
  *
  * Buffer roles:
  *   - `netInBuf`: ciphertext accumulator (read from socket, fed to `unwrap`)
  *   - `netOutBuf`: ciphertext output (produced by `wrap`, written to socket)
  *   - `appInBuf`: plaintext output from `unwrap` (decrypted application data)
  *   - `appOutBuf`: not allocated separately; `wrap` reads directly from the caller's `ByteBuffer`
  *
  * Note: `pendingCiphertext` tracks whether `netOutBuf` still holds bytes that could not be flushed on a previous write attempt. The next
  * call to `writeTls` must flush that buffer before encrypting new plaintext.
  */
// Single owner: every NioTlsState field, including the mutable `netInBuf` (reassigned when the ciphertext accumulator must grow) and the
// `pendingCiphertext` flag, is read and written ONLY on the NIO Selector carrier that owns this handle. No other carrier touches a handle's
// NioTlsState, so the bare `var`s carry no cross-carrier hazard and need no fence.
final private[kyo] class NioTlsState(
    val engine: SSLEngine,
    var netInBuf: ByteBuffer,
    val netOutBuf: ByteBuffer,
    val appInBuf: ByteBuffer,
    var pendingCiphertext: Boolean = false
):
    /** Per-handle reused accumulator for decrypted plaintext. NioTlsState is single-owner (one Selector carrier per handle),
      * so no concurrency concern. Reset before each unwrap pass; never reallocated after the first warm-up.
      */
    val decryptAcc: GrowableByteBuffer = new GrowableByteBuffer

    /** Whether the peer's TLS close_notify alert was consumed on this connection's read side (the JDK `SSLEngine` reported `Status.CLOSED` /
      * `isInboundDone` after unwrapping the peer's close_notify record, RFC 8446 6.1). Set on the NIO Selector carrier when the TLS read path
      * observes the orderly close; read by the connection's `status` accessor to tell an orderly close (close_notify received) from a bare
      * TCP FIN with no close_notify (the truncation-attack condition). Once set it stays set: a clean close is terminal for the inbound side.
      * `@volatile` carries the Selector carrier's write to the caller's carrier that reads it via `status`. Mirrors
      * [[kyo.net.internal.posix.PosixHandle.peerCleanClose]] on the engine path so both transports converge on the same close-reason semantics.
      */
    @volatile var peerCleanClose: Boolean = false

    /** Whether a bare TCP FIN (a `channel.read == -1` end-of-stream, or an `IOException` on read) was observed on this connection's read side
      * WITHOUT a preceding TLS close_notify. Set on the NIO Selector carrier when the TLS read path delivers a peer-initiated EOF that did not
      * come from a consumed close_notify (the [[peerCleanClose]] path). Read by the connection's `status` accessor: a peer EOF with
      * `peerCleanClose` unset is a truncation (RFC 8446 6.1), while a local `close()` with neither flag set is an ordinary local close.
      * `@volatile` carries the Selector carrier's write to the reader. Mirrors [[kyo.net.internal.posix.PosixHandle.peerEof]] on the engine path.
      */
    @volatile var peerEof: Boolean = false
end NioTlsState
