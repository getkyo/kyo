package kyo.net.internal.tls

import kyo.*

/** JVM `registered` TLS list. The `selected` and `engine` selection bodies live on the shared [[TlsProviderPlatformBase]]; this object supplies
  * only the JVM provider list: `BoringSslProvider` (priority 30) is the primary, `SslEngineProvider` (priority 10) is the pure-JDK `jdk` floor
  * selected when BoringSSL is not staged/loadable or when `-Dkyo.net.tls=jdk` is forced. Both flow through the shared `IoBackend.select`.
  */
private[net] object TlsProviderPlatform extends TlsProviderPlatformBase:

    val registered: Chunk[TlsEngineProvider] = Chunk(BoringSslProvider, SslEngineProvider)

end TlsProviderPlatform
