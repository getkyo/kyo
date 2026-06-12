package kyo.net.internal.tls

import kyo.*
import kyo.ffi.Buffer

/** Provider-agnostic TLS engine: the surface the drivers and the STARTTLS upgrade path consume.
  *
  * An engine wraps one TLS session as a pure in-memory state machine over two ciphertext buffers (the classic two-`BIO_s_mem` model): the
  * driver owns all socket I/O and the engine never touches a file descriptor. Inbound ciphertext the driver read from the socket is pushed in
  * with [[feedCiphertext]]; outbound ciphertext the engine queued is pulled out with [[drainCiphertext]] for the driver to send. Application
  * data crosses the same way: [[writePlain]] encrypts caller plaintext into the outbound ciphertext buffer, [[readPlain]] decrypts buffered
  * ciphertext into caller plaintext. [[handshakeStep]] advances the handshake one step against whatever ciphertext is currently buffered.
  *
  * Because every method operates on in-memory buffers rather than sockets, each is a plain unsafe-tier operation under `(using AllowUnsafe)`,
  * never a `@blocking` / `Fiber.Unsafe` call: there is no syscall to suspend on. The implementations ([[NativeSslEngine]] over the bundled
  * BoringSSL or the system-OpenSSL fallback on JVM and Native, [[JdkSslEngine]] the JVM SSLEngine fallback) collapse the previously
  * hand-rolled per-platform TLS-state paths into this one abstraction so the drivers stay TLS-provider-blind.
  *
  * Return-code convention for [[handshakeStep]]: `1` handshake complete, `0` want-read (feed more ciphertext), `-1` want-write (drain
  * produced ciphertext), `-2` fatal error. [[feedCiphertext]] returns bytes accepted (`-1` on error); [[drainCiphertext]] returns bytes
  * copied out (`0` when nothing is pending); [[readPlain]] returns bytes decrypted (`> 0`), `0` on want-read, `-1` on want-write, `-2` on
  * fatal error, `-3` on a clean close (the peer's close_notify was consumed); [[writePlain]] returns bytes consumed (`> 0`), `0` / `-1` on
  * want-read / want-write, `-2` on fatal error.
  *
  * Closure (RFC 8446 6.1 / RFC 5246 7.2.1): [[shutdownStep]] emits this side's close_notify alert (the produced record lands on the drain
  * side for the driver to flush before closing the fd), and the [[readPlain]] `-3` clean-close code is distinct from the `0` want-read code
  * so the caller can tell an orderly close (the peer's authenticated close_notify was consumed) from a bare TCP FIN with no close_notify (the
  * truncation-attack condition). The two were previously collapsed into `0`, which made a truncation indistinguishable from a clean end.
  */
private[net] trait TlsEngine:

    /** Advance the handshake one step against the currently buffered ciphertext. `1` done, `0` want-read, `-1` want-write, `-2` error. */
    def handshakeStep()(using AllowUnsafe): Int

    /** Push `len` bytes of inbound ciphertext from `buf` into the engine's read side. Returns bytes accepted, or `-1` on error. */
    def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int

    /** Pull up to `len` bytes of outbound ciphertext the engine has queued into `buf`. Returns bytes copied (`0` when nothing pending). */
    def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int

    /** Decrypt up to `len` plaintext bytes into `buf`. `> 0` bytes decrypted, `0` want-read / clean close, `-1` want-write, `-2` error. */
    def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int

    /** Encrypt `len` plaintext bytes from `buf` (ciphertext lands in the drain side). `> 0` consumed, `0` / `-1` want-read / want-write. */
    def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int

    /** Whether the engine holds decrypted-but-unread application bytes (a complete record arrived alongside the handshake's final record). */
    def hasBufferedPlaintext(using AllowUnsafe): Boolean

    /** Drain the buffered decrypted plaintext, or an empty span when there is none. */
    def readBuffered()(using AllowUnsafe): Span[Byte]

    /** RFC 5929 tls-server-end-point: SHA-256 of the peer leaf certificate DER (the SCRAM channel-binding token). 32 bytes, or `Absent`
      * when there is no peer certificate.
      */
    def certSha256()(using AllowUnsafe): Maybe[Span[Byte]]

    /** Advance the TLS close handshake one step, emitting this side's close_notify alert (RFC 8446 6.1 / RFC 5246 7.2.1: each party MUST send
      * a close_notify before closing the write side). The produced alert record lands on the drain side; the caller [[drainCiphertext]]s it and
      * sends it before closing the fd. Returns `1` (bidirectional shutdown complete: both close_notifys exchanged), `0` (this side's
      * close_notify was emitted, awaiting the peer's), or `-2` (fatal error). The close default is ONE-directional: the caller runs one step to
      * emit and flush its own close_notify and does NOT block waiting for the peer's reply (RFC 8446 6.1 permits closing without waiting), so a
      * `0` return is the normal success case for the close path.
      */
    def shutdownStep()(using AllowUnsafe): Int

    /** Release the engine's native resources. Idempotent at the call site (the handle slot is cleared after the first free). */
    def free()(using AllowUnsafe): Unit
end TlsEngine
