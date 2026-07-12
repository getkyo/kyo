package kyo.net.internal.tls

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.internal.backend.IoBackend

/** Shared TLS-provider selection body for the JVM and Native `TlsProviderPlatform` objects. The `selected` and `engine` bodies are identical
  * across platforms (both run over the leaf's `registered` list through the shared `IoBackend.select`); only the `registered` Chunk differs
  * (JVM: BoringSSL + the JDK floor; Native: BoringSSL + the system-OpenSSL fallback), so each leaf `TlsProviderPlatform` extends this base and
  * supplies only its `registered`.
  */
private[net] trait TlsProviderPlatformBase:

    /** The platform's TLS providers, highest priority first. Supplied by each leaf `TlsProviderPlatform`. */
    def registered: Chunk[TlsEngineProvider]

    /** The selected TLS provider honoring `-Dkyo.net.tls`. Reuses the SAME `IoBackend.select` as the I/O registry. */
    def selected(using AllowUnsafe, Frame): TlsEngineProvider =
        IoBackend.select[TlsEngineProvider](registered, _.name, _.priority, _.isAvailable, "kyo.net.tls").getOrThrow

    /** Build the TLS engine for the given config/hostname/role, honoring a [[NetTlsConfig.tlsProvider]] pin (fail-closed if unavailable) and
      * otherwise the platform-selected default.
      */
    def engine(config: NetTlsConfig, hostname: String, isServer: Boolean)(using AllowUnsafe, Frame): TlsEngine =
        TlsProvider.selectFor(registered, config).getOrThrow.createEngine(config, hostname, isServer)

end TlsProviderPlatformBase
