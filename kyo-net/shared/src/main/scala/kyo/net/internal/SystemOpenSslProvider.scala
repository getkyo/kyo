package kyo.net.internal

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.ffi.Ffi
import kyo.net.internal.backend.CapabilityOutcome

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

    private[net] val compiledStubOutcome: CapabilityOutcome.CompiledStub = CapabilityOutcome.CompiledStub(
        "kyo_net_openssl.c",
        "OpenSSL",
        "install OpenSSL's development files (libssl-dev, openssl-devel, or Homebrew openssl@3) on the machine that links the binary, enable " +
            "kyo-natives-plugin, which links them when they link, and relink"
    )

    // bound once for the reason KqueuePollerBackend.kq is: the shared stateless binding was
    // reloaded per TLS operation. Lazy so the provider registry's touch of this object does not
    // dlopen before its capability probe passed.
    // Unsafe: first use is always under a caller that holds AllowUnsafe, and each binding method
    // still requires AllowUnsafe per call.
    private[internal] lazy val lib: SslLibBindings =
        import AllowUnsafe.embrace.danger
        Ffi.load[OpenSslBindings]

end SystemOpenSslProvider
