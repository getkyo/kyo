package kyo.net.internal.tls

import kyo.*
import kyo.net.NetTlsProviderUnavailableException
import kyo.net.internal.backend.IoBackend

/** JS/Wasm Node TLS provider. Node terminates TLS itself (the Node transport drives Node `tls`), so the only entry is `NodeTlsProvider` and
  * nothing on JS/Wasm builds an in-process `TlsEngine`. This provider is selection-only: it carries name/priority/isAvailable for provider
  * reporting but has no engine-building surface (that lives in the `jvm-native` `TlsEngineProvider`).
  */
private[net] object NodeTlsProvider extends TlsProvider:
    def name                                    = "node"
    def priority                                = 10
    def isAvailable(using AllowUnsafe): Boolean = true
end NodeTlsProvider

private[net] object TlsProviderPlatform:

    val registered: Chunk[TlsProvider] = Chunk(NodeTlsProvider)

    /** The selected JS/Wasm TLS provider honoring `-Dkyo.net.tls`. Reuses the SAME `IoBackend.select` as the I/O registry. Used for
      * provider-name reporting; the Node transport terminates TLS itself, so no engine is built here.
      */
    def selected(using AllowUnsafe, Frame): TlsProvider =
        IoBackend.select[TlsProvider, NetTlsProviderUnavailableException](
            registered,
            _.name,
            _.priority,
            _.isAvailable,
            forced = Maybe(kyo.net.tls()).filter(_.nonEmpty),
            onUnavailable = _ => NetTlsProviderUnavailableException("<default>")
        ).getOrThrow

end TlsProviderPlatform
