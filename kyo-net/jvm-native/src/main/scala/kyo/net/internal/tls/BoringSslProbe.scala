package kyo.net.internal.tls

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.ffi.Ffi

/** Minimal load probe for the bundled BoringSSL shim (`kyonet_boringssl`).
  *
  * The `kyo_net_boringssl.c` shim links the staged BoringSSL static archives and re-exports the TLS
  * surface behind a `kyo_bssl_*` prefix so the raw `SSL_*` ABI stays insulated from any system
  * OpenSSL, and stays distinct from the system-OpenSSL path's `kyo_ossl_*` symbols (the OpenSSL
  * fallback shares one binary on Native, so the prefixes must not collide). This trait binds just
  * the one-call availability probe: it allocates and frees an `SSL_CTX`, which fails only when the
  * bundled library did not load (missing symbol / absent archive). `BoringSslProvider.isAvailable`
  * and the bundle load test call it to confirm the bundle resolves on JVM (Panama) and
  * Native with no `UnsatisfiedLinkError`.
  *
  * The full `BoringSslBindings` (ctx/ssl lifecycle, the two-BIO feed/drain handshake state machine,
  * `kyo_bssl_peer_cert_sha256`) builds on this probe, which is the loadable seam those bindings share.
  */
private[net] trait BoringSslProbe extends Ffi:

    /** `kyo_bssl_probe_available`: `SSL_CTX_new(TLS_method())` then free, returning true when the
      * bundled BoringSSL is present and functional. The bundled-lib-loads probe (no
      * `UnsatisfiedLinkError`).
      */
    def kyo_bssl_probe_available()(using AllowUnsafe): Boolean

end BoringSslProbe

private[net] object BoringSslProbe extends Ffi.Config(
        library = "kyonet_boringssl",
        headers = Chunk("openssl/ssl.h"),
        // On Native the shim's C (kyo_net_boringssl.c) is compiled INTO the binary (copied under
        // resources/scala-native by KyoFfiPlugin) and the staged BoringSSL archives are archive-linked
        // via ffiNativeLinkingOptions, so the generated Native binding must NOT emit
        // @link("kyonet_boringssl") (no such shared library exists to find a -l for). On JVM the
        // plugin-compiled loadable lib is dlopen'd by Panama at runtime.
        nativeBundled = true
    )
