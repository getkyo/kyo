package kyo.net.internal.tls

import kyo.AllowUnsafe
import kyo.ffi.Buffer

/** The shared FFI surface of a memory-BIO TLS library: the cross-backend intersection of the BoringSSL and system-OpenSSL shims, named without
  * a library-specific prefix.
  *
  * Both the bundled-BoringSSL shim (`kyo_net_boringssl.c`, exported as `kyo_bssl_*`) and the system-OpenSSL shim (`kyo_net_openssl.c`, exported
  * as `kyo_ossl_*`) implement the identical two-memory-BIO TLS state machine over the same `SSL_*` / `BIO_s_mem` primitives; they differ only
  * in their export prefix and the library they compile against. This trait abstracts that intersection so one engine and one provider can drive
  * either backend through a single type. A concrete binding (`BoringSslBindings`, `OpenSslBindings`) extends this trait alongside `Ffi` and
  * re-declares each method in its own body, mapping the neutral name to its prefixed C symbol through its companion `Ffi.Config`: the kyo-ffi
  * codegen binds each backend from its own declarations, so the shared type carries the contract while each backend carries its symbols.
  *
  * Opaque `SSL_CTX*` / per-SSL state pointers cross the FFI boundary as `Long` (each shim casts them to `(long)(intptr_t)ptr` and back): a
  * non-zero value is a live pointer, `0` is allocation failure. The caller never dereferences them; it only round-trips them back into these
  * methods. Ciphertext and plaintext are marshalled through caller-owned `Buffer[Byte]`.
  *
  * Every method is part of the unsafe FFI tier under a trailing `(using AllowUnsafe)`: each call is a side effect on the native session state.
  * None is `@Ffi.blocking`: the shim runs entirely in memory (the two `BIO_s_mem` buffers), so there is no syscall to suspend on.
  */
private[net] trait SslLibBindings:

    /** Create an `SSL_CTX` from `TLS_method()` with a TLS 1.2 floor. `isServer` is accepted for symmetry; the role is selected per-SSL via the
      * connect/accept-state calls. Returns the context pointer as a `Long`, or `0` on allocation failure.
      */
    def ctxNew(isServer: Int)(using AllowUnsafe): Long

    /** `SSL_CTX_free`. A `0` pointer is a no-op. */
    def ctxFree(ctx: Long)(using AllowUnsafe): Unit

    /** Load a PEM certificate (chain) and PEM private key into the context. Returns `0` on success, `-1` on any failure (bad PEM, key/cert
      * mismatch).
      */
    def ctxSetCert(ctx: Long, certPem: String, keyPem: String)(using AllowUnsafe): Int

    /** Peer-verify mode. `0` none, `1` optional (verify if presented), `2` required. */
    def ctxSetVerifyMode(ctx: Long, mode: Int)(using AllowUnsafe): Unit

    /** Load PEM CA certificate(s) into the trust store. Returns the count added (`>= 1`) or `-1`. */
    def ctxLoadCa(ctx: Long, caPem: String)(using AllowUnsafe): Int

    /** Pin the TLS version window (`2` = TLS 1.2, `3` = TLS 1.3, `0` = library default). `0` ok, `-1` on a rejected version. */
    def ctxSetMinMaxVersion(ctx: Long, min: Int, max: Int)(using AllowUnsafe): Int

    /** Create an SSL from the context wired to a fresh pair of `BIO_s_mem` buffers; a non-empty DNS `hostname` sets SNI only (an IP literal
      * sets no SNI). It does NOT bind the verification reference identity, so chain validation and name checking stay decoupled; bind the
      * reference identity separately via [[sslSetVerifyName]]. Returns the per-SSL state pointer as a `Long`, or `0` on failure.
      */
    def sslNew(ctx: Long, hostname: String)(using AllowUnsafe): Long

    /** Bind the client reference identity for verification, decoupled from SNI. An IP literal is bound through the IP-ID path
      * (`X509_VERIFY_PARAM_set1_ip_asc`, RFC 9525 exact `iPAddress`-SAN matching); any other non-empty string is bound through the DNS-ID path
      * (`SSL_set1_host`). The provider calls this for a verifying client that has a reference identity; for one with no identity it calls
      * [[sslRequireUnmatchableIdentity]] instead. Returns `1` on success, `0` on an empty host or a set failure.
      */
    def sslSetVerifyName(ssl: Long, hostname: String)(using AllowUnsafe): Int

    /** Fail closed for a verifying client with no reference identity by binding a reference identity no certificate can satisfy (the RFC 6761
      * `.invalid` special-use TLD), so the name check runs and rejects every peer, failing the handshake fatally rather than accepting any
      * chain-valid cert (RFC 9525 §6.1). Returns `1` when the unmatchable identity was set, `0` on error.
      */
    def sslRequireUnmatchableIdentity(ssl: Long)(using AllowUnsafe): Int

    /** Select the client (connect) role. */
    def sslSetConnectState(ssl: Long)(using AllowUnsafe): Unit

    /** Select the server (accept) role. */
    def sslSetAcceptState(ssl: Long)(using AllowUnsafe): Unit

    /** Free the SSL and its two owned BIOs and the wrapper. A `0` pointer is a no-op. */
    def sslFree(ssl: Long)(using AllowUnsafe): Unit

    /** One handshake step. `1` done, `0` want-read, `-1` want-write, `-2` fatal error. */
    def doHandshakeStep(ssl: Long)(using AllowUnsafe): Int

    /** Write `len` inbound ciphertext bytes from `buf` into the read BIO. Returns bytes written, or `-1`. */
    def feedCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int

    /** Read up to `len` outbound ciphertext bytes from the write BIO into `buf`. Returns bytes copied (`0` when nothing pending), or `-1`. */
    def drainCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int

    /** Decrypt up to `len` plaintext bytes into `buf`. `> 0` decrypted, `0` want-read / clean close, `-1` want-write, `-2` fatal error. */
    def readPlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int

    /** Encrypt `len` plaintext bytes from `buf` (ciphertext lands in the write BIO). `> 0` consumed, `0` / `-1` want-read / want-write, `-2`
      * fatal error.
      */
    def writePlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int

    /** `SSL_pending`, the count of decrypted-but-unread application bytes buffered in the SSL. */
    def pending(ssl: Long)(using AllowUnsafe): Int

    /** One shutdown (close_notify) step. `1` complete, `0` need more I/O, `-2` fatal error. */
    def shutdownStep(ssl: Long)(using AllowUnsafe): Int

    /** RFC 5929 tls-server-end-point, the SHA-256 of the peer leaf certificate DER (`i2d_X509` + SHA-256). Writes 32 bytes into `outBuf`;
      * returns `32` on success or `-1` when there is no peer cert / `outLen < 32` / a hashing error. The 32 bytes are identical across the two
      * backends for the same cert: same DER encoding, same digest.
      */
    def peerCertSha256(ssl: Long, outBuf: Buffer[Byte], outLen: Int)(using AllowUnsafe): Int

    /** `SSL_CTX_new(TLS_method())` then free, returning true when the backing library is present and functional (no `UnsatisfiedLinkError` /
      * missing symbol). The provider calls this to gate selecting the engine.
      */
    def probeAvailable()(using AllowUnsafe): Boolean

end SslLibBindings
