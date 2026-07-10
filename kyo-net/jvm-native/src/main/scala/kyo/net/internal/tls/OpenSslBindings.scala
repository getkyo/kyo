package kyo.net.internal.tls

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Full binding to the system-OpenSSL shim (`kyonet_openssl`), the Native fallback below BoringSSL.
  *
  * This is the system-OpenSSL twin of [[BoringSslBindings]]: the same backend-neutral [[SslLibBindings]] surface, but the companion
  * `Ffi.Config` symbols map carries each method's `kyo_ossl_*` C symbol (not `kyo_bssl_*`) and the shim (`kyo_net_openssl.c`) compiles
  * against the host's system OpenSSL (macOS: openssl@3; Linux: libssl-dev). The `kyo_ossl_*` prefix keeps the surface distinct from the
  * BoringSSL shim's `kyo_bssl_*` on the single Native binary; the raw `SSL_*` calls in both shims resolve to the one TLS implementation the
  * binary links (the system OpenSSL dylib), so the two prefixed surfaces coexist with no symbol clash. On Native the codegen probes
  * `openssl/ssl.h`: when it is absent the binding stubs out and [[SystemOpenSslProvider]] reports unavailable; the `headers` gate that probe.
  *
  * Opaque `SSL_CTX*` / per-SSL state pointers cross the FFI boundary as `Long` (the shim casts them to `(long)(intptr_t)ptr` and back): a
  * non-zero value is a live pointer, `0` is allocation failure. The caller never dereferences them; it only round-trips them back into these
  * functions. Ciphertext and plaintext are marshalled through caller-owned `Buffer[Byte]`.
  *
  * Every method is part of the unsafe FFI tier under a trailing `(using AllowUnsafe)`: each call is a side effect on the native session state.
  * None is `@Ffi.blocking`: the shim runs entirely in memory (the two `BIO_s_mem` buffers), so there is no syscall to suspend on.
  */
private[net] trait OpenSslBindings extends SslLibBindings, Ffi:

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

end OpenSslBindings

private[net] object OpenSslBindings extends Ffi.Config(
        library = "kyonet_openssl",
        headers = Chunk("openssl/ssl.h", "openssl/x509.h"),
        // The neutral SslLibBindings method names map to the shim's kyo_ossl_* C symbols here, so the
        // generated binding resolves each method to its prefixed export on Panama (JVM) and @extern (Native).
        symbols = Map(
            "ctxNew"                        -> "kyo_ossl_ctx_new",
            "ctxFree"                       -> "kyo_ossl_ctx_free",
            "ctxSetCert"                    -> "kyo_ossl_ctx_set_cert",
            "ctxSetVerifyMode"              -> "kyo_ossl_ctx_set_verify_mode",
            "ctxLoadCa"                     -> "kyo_ossl_ctx_load_ca",
            "ctxLoadSystemCa"               -> "kyo_ossl_ctx_load_system_ca",
            "ctxSetMinMaxVersion"           -> "kyo_ossl_ctx_set_min_max_version",
            "sslNew"                        -> "kyo_ossl_ssl_new",
            "sslSetVerifyName"              -> "kyo_ossl_ssl_set_verify_name",
            "sslRequireUnmatchableIdentity" -> "kyo_ossl_ssl_require_unmatchable_identity",
            "sslSetConnectState"            -> "kyo_ossl_ssl_set_connect_state",
            "sslSetAcceptState"             -> "kyo_ossl_ssl_set_accept_state",
            "sslFree"                       -> "kyo_ossl_ssl_free",
            "doHandshakeStep"               -> "kyo_ossl_do_handshake_step",
            "feedCiphertext"                -> "kyo_ossl_feed_ciphertext",
            "drainCiphertext"               -> "kyo_ossl_drain_ciphertext",
            "readPlain"                     -> "kyo_ossl_read_plain",
            "writePlain"                    -> "kyo_ossl_write_plain",
            "pending"                       -> "kyo_ossl_pending",
            "shutdownStep"                  -> "kyo_ossl_shutdown_step",
            "peerCertSha256"                -> "kyo_ossl_peer_cert_sha256",
            "probeAvailable"                -> "kyo_ossl_probe_available"
        ),
        // On Native the shim's C (kyo_net_openssl.c) is compiled INTO the binary (copied under
        // resources/scala-native by KyoFfiPlugin) and the system OpenSSL is linked via the
        // openssl-native-settings link options (-lssl -lcrypto), so the generated Native binding must
        // NOT emit @link("kyonet_openssl"). On JVM the plugin-compiled loadable lib is dlopen'd by Panama.
        nativeBundled = true
    )
