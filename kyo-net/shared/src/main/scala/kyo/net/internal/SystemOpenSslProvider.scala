package kyo.net.internal

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.ffi.Ffi

/** The system-OpenSSL TLS provider (priority 20), the Native fallback below BoringSSL (priority 30). Lives in `shared` alongside
  * [[BoringSslProvider]]: one provider over the one shared [[OpenSslBindings]]. Only the Native `TlsProviderPlatform` registers it (the JVM
  * registry uses BoringSSL plus the JDK floor, and the JS/Wasm registry uses BoringSSL plus the Node floor); building it on JVM too lets the
  * cross-platform `TlsEngineTest` exercise the system-OpenSSL engine under Panama as well as `@extern`.
  *
  * It supplies the OpenSSL binding, name, priority, and library id to [[SslLibProvider]], which carries the shared engine construction,
  * config application, client-identity binding, and capability probe. A host without system OpenSSL probes as not bundled, so TLS falls
  * through to whatever else is registered and the report names the library that was missing.
  */
private[net] object SystemOpenSslProvider extends SslLibProvider:

    def name = "openssl"

    def priority = 20

    def libraryIds: Chunk[String] = Chunk(OpenSslBindings.library)

    private[internal] def lib(using AllowUnsafe): SslLibBindings = Ffi.load[OpenSslBindings]

end SystemOpenSslProvider
