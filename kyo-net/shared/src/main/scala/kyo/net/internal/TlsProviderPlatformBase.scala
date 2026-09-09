package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.NetTlsProviderUnavailableException
import kyo.net.internal.backend.IoBackend

/** Shared TLS-provider selection body for the JVM and Native `TlsProviderPlatform` objects. The `selected` and `engine` bodies are identical
  * across platforms (both run over the leaf's `registered` list through the shared `IoBackend.select`); only the `registered` Chunk differs
  * (JVM: BoringSSL + the JDK floor; Native: BoringSSL + the system-OpenSSL fallback), so each leaf `TlsProviderPlatform` extends this base and
  * supplies only its `registered`.
  */
private[net] trait TlsProviderPlatformBase:

    /** The platform's TLS providers, highest priority first. Supplied by each leaf `TlsProviderPlatform`. */
    def registered: Chunk[TlsEngineProvider]

    /** The names of the in-process TLS engine providers the posix transport drives (exactly what `engine` selects among), for the transport to
      * advertise in [[kyo.net.TransportCapabilities.tlsProviders]]. JVM and Native register only engine providers, so this is every registered
      * provider; the JS/Wasm `TlsProviderPlatform` overrides it because its `registered` also carries the selection-only `NodeTlsProvider`.
      */
    def engineProviderNames: Set[String] = registered.map(_.name).toSet

    /** True when this host can actually BUILD an in-process TLS engine, which is what [[engine]] needs and what a caller of `connectTls` on
      * the posix transport depends on. Distinct from "some provider is registered": the JS/Wasm registry also carries the selection-only
      * `NodeTlsProvider`, whose probe is unconditionally available because Node terminates its own TLS, so a registry-wide check reports yes
      * on a host where every engine provider is missing and the next `engine` call fails.
      */
    def hasAvailableEngine(using AllowUnsafe): Boolean = registered.exists(_.probe.isAvailable)

    /** The selected TLS provider honoring `-Dkyo.net.tls`. Reuses the SAME `IoBackend.select` as the I/O registry.
      *
      * A forced name that is unavailable surfaces in the failure by name, matching what the I/O side has always done; "&lt;default&gt;" is
      * reserved for the genuinely unforced case, where there is no name to report. The selection report rides along as the cause, so the
      * failure also says what every registered provider reported.
      */
    def selected(using AllowUnsafe, Frame): TlsEngineProvider =
        IoBackend.select[TlsEngineProvider, NetTlsProviderUnavailableException](
            registered,
            forced = Maybe(kyo.net.tls()).filter(_.nonEmpty),
            onUnavailable = (forced, report) => NetTlsProviderUnavailableException(forced.getOrElse("<default>"), report.render)
        ).getOrThrow

    /** Build the TLS engine for the given config/hostname/role, honoring a [[NetTlsConfig.tlsProvider]] pin (fail-closed if unavailable) and
      * otherwise the platform-selected default.
      */
    def engine(config: NetTlsConfig, hostname: String, isServer: Boolean)(using AllowUnsafe, Frame): TlsEngine =
        TlsProvider.selectFor(registered, config).createEngine(config, hostname, isServer)

end TlsProviderPlatformBase
