package kyo.internal

import kyo.*

/** Test-only backends. Native TLS not yet implemented — TLS tests skipped. */
private[kyo] object HttpTestPlatformBackend:
    lazy val client: HttpBackend.Client            = HttpPlatformBackend.client
    lazy val server: HttpBackend.Server            = HttpPlatformBackend.server
    lazy val wsClient: HttpBackend.WebSocketClient = HttpPlatformBackend.wsClient
    val tlsServerAvailable: Boolean                = false
end HttpTestPlatformBackend
