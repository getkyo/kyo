package kyo.net.internal

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Full binding to the bundled BoringSSL shim (`kyonet_boringssl`), built on the loadable seam [[BoringSslProbe]].
  *
  * The shim (`kyo_net_boringssl.c`) statically links the staged BoringSSL archives and re-exports the TLS surface behind a `kyo_bssl_*`
  * prefix so the raw `SSL_*` ABI stays insulated from any system OpenSSL and from the system-OpenSSL `kyo_ossl_*` fallback. The binding
  * declares the backend-neutral [[SslLibBindings]] surface; the companion `Ffi.Config` symbols map carries the per-method `kyo_bssl_*` C
  * symbol so the kyo-ffi codegen binds each neutral method to its prefixed export.
  *
  * Opaque `SSL_CTX*` / per-SSL state pointers cross the FFI boundary as `Long` (the shim casts them to `(long)(intptr_t)ptr` and back, the
  * exact ABI of the committed shim): a non-zero value is a live pointer, `0` is allocation failure. The caller never dereferences them; it
  * only round-trips them back into these functions. Ciphertext and plaintext are marshalled through caller-owned `Buffer[Byte]`.
  *
  * Every method is part of the unsafe FFI tier under a trailing `(using AllowUnsafe)`: each call is a side effect on the native session
  * state. None is `@Ffi.blocking`: the shim runs entirely in memory (the two `BIO_s_mem` buffers), so there is no syscall to suspend on.
  */
private[net] trait BoringSslBindings extends SslLibBindings, Ffi:

    def ctxNew(isServer: Int)(using AllowUnsafe): Long
    def ctxFree(ctx: Long)(using AllowUnsafe): Unit
    def ctxSetCert(ctx: Long, certPem: String, keyPem: String)(using AllowUnsafe): Int
    def ctxSetVerifyMode(ctx: Long, mode: Int)(using AllowUnsafe): Unit
    def ctxLoadCa(ctx: Long, caPem: String)(using AllowUnsafe): Int
    def ctxLoadSystemCa(ctx: Long)(using AllowUnsafe): Int
    def ctxSetMinMaxVersion(ctx: Long, min: Int, max: Int)(using AllowUnsafe): Int
    def sslNew(ctx: Long, hostname: String)(using AllowUnsafe): Long
    def sslSetVerifyName(ssl: Long, hostname: String)(using AllowUnsafe): Int
    def sslRequireUnmatchableIdentity(ssl: Long)(using AllowUnsafe): Int
    def sslSetConnectState(ssl: Long)(using AllowUnsafe): Unit
    def sslSetAcceptState(ssl: Long)(using AllowUnsafe): Unit
    def sslFree(ssl: Long)(using AllowUnsafe): Unit
    def doHandshakeStep(ssl: Long)(using AllowUnsafe): Int
    def feedCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int
    def drainCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int
    def readPlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int
    def writePlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int
    def pending(ssl: Long)(using AllowUnsafe): Int
    def shutdownStep(ssl: Long)(using AllowUnsafe): Int
    def peerCertSha256(ssl: Long, outBuf: Buffer[Byte], outLen: Int)(using AllowUnsafe): Int
    def probeAvailable()(using AllowUnsafe): Boolean

end BoringSslBindings

private[net] object BoringSslBindings extends Ffi.Config(
        library = "kyonet_boringssl",
        headers = Chunk("openssl/ssl.h", "openssl/x509.h"),
        // The neutral SslLibBindings method names map to the shim's kyo_bssl_* C symbols here, so the
        // generated binding resolves each method to its prefixed export on Panama (JVM) and @extern (Native).
        symbols = Map(
            "ctxNew"                        -> "kyo_bssl_ctx_new",
            "ctxFree"                       -> "kyo_bssl_ctx_free",
            "ctxSetCert"                    -> "kyo_bssl_ctx_set_cert",
            "ctxSetVerifyMode"              -> "kyo_bssl_ctx_set_verify_mode",
            "ctxLoadCa"                     -> "kyo_bssl_ctx_load_ca",
            "ctxLoadSystemCa"               -> "kyo_bssl_ctx_load_system_ca",
            "ctxSetMinMaxVersion"           -> "kyo_bssl_ctx_set_min_max_version",
            "sslNew"                        -> "kyo_bssl_ssl_new",
            "sslSetVerifyName"              -> "kyo_bssl_ssl_set_verify_name",
            "sslRequireUnmatchableIdentity" -> "kyo_bssl_ssl_require_unmatchable_identity",
            "sslSetConnectState"            -> "kyo_bssl_ssl_set_connect_state",
            "sslSetAcceptState"             -> "kyo_bssl_ssl_set_accept_state",
            "sslFree"                       -> "kyo_bssl_ssl_free",
            "doHandshakeStep"               -> "kyo_bssl_do_handshake_step",
            "feedCiphertext"                -> "kyo_bssl_feed_ciphertext",
            "drainCiphertext"               -> "kyo_bssl_drain_ciphertext",
            "readPlain"                     -> "kyo_bssl_read_plain",
            "writePlain"                    -> "kyo_bssl_write_plain",
            "pending"                       -> "kyo_bssl_pending",
            "shutdownStep"                  -> "kyo_bssl_shutdown_step",
            "peerCertSha256"                -> "kyo_bssl_peer_cert_sha256",
            "probeAvailable"                -> "kyo_bssl_probe_available"
        ),
        // On Native the shim's C (kyo_net_boringssl.c) is compiled INTO the binary (copied under
        // resources/scala-native by KyoFfiPlugin) and the staged BoringSSL archives are archive-linked
        // via ffiNativeLinkingOptions, so the generated Native binding must NOT emit
        // @link("kyonet_boringssl"). On JVM the plugin-compiled loadable lib is dlopen'd by Panama.
        nativeBundled = true
    )
