package kyo.internal.client

import kyo.*

/** The client backend this host offers.
  *
  * One Scala.js artifact runs on hosts that differ in what they have. Node, Bun and Deno have sockets, so a client there is
  * [[HttpClientBackend]] over the process-shared transport, the same as the JVM and Scala Native.
  *
  * Every other JS host gets [[FetchClientBackend]]. A browser page is the one this was built for, but it is not the only host without a
  * socket: an edge runtime, an embedded engine and a WasmGC host with no Node shim are all in the same position, and all of them have
  * `fetch`. Asking those for the socket transport reaches a `node:net` that is not there, and the backend probe's failure arrives as a panic
  * rather than as anything a caller can handle, so the rule is what the host has rather than what it is called.
  *
  * The choice is read when the program runs, because one bundle is served to whatever loads it.
  */
private[kyo] object ClientPlatform:

    /** Builds the backend a client on this host sends through. */
    def backend(
        maxConnectionsPerHost: Int,
        idleConnectionTimeout: Duration,
        defaultTlsConfig: HttpTlsConfig,
        transportConfig: HttpTransportConfig
    )(using AllowUnsafe, Frame): ClientBackend =
        if kyo.internal.Platform.isNodeLike then
            HttpClientBackend.init(
                kyo.net.NetPlatform.transport,
                maxConnectionsPerHost,
                idleConnectionTimeout,
                defaultTlsConfig,
                transportConfig
            )
        else new FetchClientBackend

end ClientPlatform
