package kyo.net.internal

import kyo.*

/** Native `registered` TLS list. The `selected` and `engine` selection bodies live on the shared [[TlsProviderPlatformBase]]; this object
  * supplies only the Native provider list: `BoringSslProvider` (priority 30, bundled) is the primary, `SystemOpenSslProvider` (priority 20) is
  * the system-OpenSSL fallback selected when BoringSSL is not staged/loadable or when `-Dkyo.net.tls=openssl` is forced. Both flow through the
  * shared `IoBackend.select`.
  */
private[net] object TlsProviderPlatform extends TlsProviderPlatformBase:

    val registered: Chunk[TlsEngineProvider] = Chunk(BoringSslProvider, SystemOpenSslProvider)

end TlsProviderPlatform
