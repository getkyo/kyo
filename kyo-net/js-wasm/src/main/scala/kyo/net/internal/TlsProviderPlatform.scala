package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.NetTlsProviderUnavailableException
import kyo.net.internal.backend.CapabilityOutcome
import kyo.net.internal.backend.IoBackend

/** JS/Wasm Node TLS provider. Node terminates TLS itself (the Node transport drives Node `tls`), so `NodeTlsProvider` is selection-only: it
  * carries the shared capability identity for provider reporting but builds no in-process `TlsEngine`. The engine-building surface for the
  * KOFFI posix transport on JS/Wasm is `BoringSslProvider` (a `TlsEngineProvider`, shared), registered below alongside it.
  */
private[net] object NodeTlsProvider extends TlsProvider:
    def name                                                       = "node"
    def priority                                                   = 10
    def libraryIds: Chunk[String]                                  = Chunk.empty
    private[net] def doProbe(using AllowUnsafe): CapabilityOutcome = CapabilityOutcome.Available
end NodeTlsProvider

private[net] object TlsProviderPlatform:

    /** Both TLS identities are selectable via `-Dkyo.net.tls`: `boringssl` (the koffi engine driving the posix transport) ranks above `node`
      * (Node's own TLS driving `JsTransport`), matching the JVM/Native precedent.
      */
    val registered: Chunk[TlsProvider] = Chunk(BoringSslProvider, NodeTlsProvider)

    /** The engine-building providers: only `BoringSslProvider` builds an in-process `TlsEngine` on JS/Wasm (Node terminates its own TLS, so
      * `NodeTlsProvider` has no engine). The posix transport's TLS goes through here.
      */
    private val engineProviders: Chunk[TlsEngineProvider] = Chunk(BoringSslProvider)

    /** The names the koffi posix transport advertises in [[kyo.net.TransportCapabilities.tlsProviders]]: only the engine providers it can
      * drive (`boringssl`), NOT the selection-only `node` in `registered`. Advertising `node` would admit a `[posix / node]` handshake that
      * then selects no engine and fails; excluding it makes that combination reject up front. Mirrors the `jvm-native` base default, which is
      * `registered` there because JVM/Native register only engine providers.
      */
    def engineProviderNames: Set[String] = engineProviders.map(_.name).toSet

    /** True when this host can actually BUILD an in-process TLS engine, the JS/Wasm counterpart of the `jvm-native` base's member of the same
      * name. It ranges over [[engineProviders]] rather than `registered` for the reason `engineProviderNames` does: `NodeTlsProvider`'s probe
      * is unconditionally available because Node terminates its own TLS, so a check over `registered` reports yes on a host with no staged
      * BoringSSL, where the koffi posix transport has no engine to build and the next `engine` call fails.
      */
    def hasAvailableEngine(using AllowUnsafe): Boolean = engineProviders.exists(_.probe.isAvailable)

    /** The selected JS/Wasm TLS provider honoring `-Dkyo.net.tls`. Reuses the SAME `IoBackend.select` as the I/O registry. Used for
      * provider-name reporting and by the Node transport (which terminates TLS itself).
      */
    def selected(using AllowUnsafe, Frame): TlsProvider =
        IoBackend.select[TlsProvider, NetTlsProviderUnavailableException](
            registered,
            forced = Maybe(kyo.net.tls()).filter(_.nonEmpty),
            onUnavailable = (forced, report) => NetTlsProviderUnavailableException(forced.getOrElse("<default>"), report.render)
        ).getOrThrow

    /** Build the koffi TLS engine for the posix transport, honoring a `NetTlsConfig.tlsProvider` pin (fail-closed if unavailable) and otherwise
      * the platform-selected default. Mirrors `jvm-native` `TlsProviderPlatformBase.engine`.
      */
    def engine(config: NetTlsConfig, hostname: String, isServer: Boolean)(using AllowUnsafe, Frame): TlsEngine =
        TlsProvider.selectFor(engineProviders, config).createEngine(config, hostname, isServer)

end TlsProviderPlatform
