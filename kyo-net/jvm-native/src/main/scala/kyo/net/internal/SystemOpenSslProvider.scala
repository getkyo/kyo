package kyo.net.internal

import kyo.AllowUnsafe
import kyo.ffi.Ffi

/** The system-OpenSSL TLS provider (priority 20), the Native fallback below BoringSSL (priority 30). Lives in the `jvm-native` shared source
  * set alongside [[BoringSslProvider]]: one provider over the one shared [[OpenSslBindings]]. Only the Native `TlsProviderPlatform` registers
  * it (the JVM registry uses BoringSSL plus the JDK floor); building it on JVM too lets the cross-platform `TlsEngineTest` exercise the
  * system-OpenSSL engine under Panama as well as `@extern`.
  *
  * It supplies the OpenSSL binding, name, and priority to [[SslLibProvider]], which carries the shared engine construction, config
  * application, client-identity binding, and availability memo. A host without system OpenSSL has `isAvailable` report `false` (the probe
  * collapses any load failure), so TLS falls through to whatever else is registered.
  */
private[net] object SystemOpenSslProvider extends SslLibProvider:

    def name = "openssl"

    def priority = 20

    private[internal] def lib(using AllowUnsafe): SslLibBindings = Ffi.load[OpenSslBindings]

end SystemOpenSslProvider
