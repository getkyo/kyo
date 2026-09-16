package kyo.internal.client

import kyo.*

/** The client backend this host offers.
  *
  * One Scala.js artifact runs on hosts that differ in what they have. Node, Bun and Deno have sockets, so a client there is
  * [[HttpClientBackend]] over the process-shared transport, the same as the JVM and Scala Native. A browser page has none, and reaching the
  * transport would ask for a `node:net` that is not there, so a page gets [[FetchClientBackend]] over its own `fetch`.
  *
  * The choice is the host's, read when the program runs, because one bundle is served to whatever loads it.
  */
private[kyo] object ClientPlatform:

    /** Builds the backend a client on this host sends through. */
    def backend(
        maxConnectionsPerHost: Int,
        idleConnectionTimeout: Duration,
        defaultTlsConfig: HttpTlsConfig,
        transportConfig: HttpTransportConfig
    )(using AllowUnsafe, Frame): ClientBackend =
        if kyo.internal.Platform.isBrowser then new FetchClientBackend
        else
            HttpClientBackend.init(
                kyo.net.NetPlatform.transport,
                maxConnectionsPerHost,
                idleConnectionTimeout,
                defaultTlsConfig,
                transportConfig
            )

end ClientPlatform
