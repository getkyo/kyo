package kyo.internal.client

import kyo.*

/** The client backend this platform offers.
  *
  * The JVM and Scala Native both have sockets, so a client is always [[HttpClientBackend]] over the process-shared transport. Scala.js
  * answers this differently, because a browser page has no socket.
  */
private[kyo] object ClientPlatform:

    /** Builds the backend a client on this platform sends through. */
    def backend(
        maxConnectionsPerHost: Int,
        idleConnectionTimeout: Duration,
        defaultTlsConfig: HttpTlsConfig,
        transportConfig: HttpTransportConfig
    )(using AllowUnsafe, Frame): ClientBackend =
        HttpClientBackend.init(
            kyo.net.NetPlatform.transport,
            maxConnectionsPerHost,
            idleConnectionTimeout,
            defaultTlsConfig,
            transportConfig
        )

end ClientPlatform
