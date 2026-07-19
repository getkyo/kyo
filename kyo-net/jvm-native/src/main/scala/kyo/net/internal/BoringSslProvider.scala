package kyo.net.internal

import kyo.AllowUnsafe
import kyo.ffi.Ffi

/** The BoringSSL TLS provider, the priority-30 primary on JVM and Native. Lives in the `jvm-native` shared source set: one provider over the
  * one shared [[BoringSslBindings]], built once for the two platforms that bundle BoringSSL (JS terminates TLS in Node instead).
  *
  * It supplies the BoringSSL binding, name, and priority to [[SslLibProvider]], which carries the shared engine construction, config
  * application, client-identity binding, and availability memo. A host without the staged bundle has `isAvailable` report `false` (the probe
  * collapses any load failure), so TLS falls through to the JVM `jdk` floor or the Native `openssl` fallback.
  */
private[net] object BoringSslProvider extends SslLibProvider:

    def name = "boringssl"

    def priority = 30

    private[internal] def lib(using AllowUnsafe): SslLibBindings = Ffi.load[BoringSslBindings]

end BoringSslProvider
