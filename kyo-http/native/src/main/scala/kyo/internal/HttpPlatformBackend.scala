package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client            = new CurlClientBackend(daemon = true)
    lazy val server: HttpBackend.Server            = new H2oServerBackend
    lazy val wsClient: HttpBackend.WebSocketClient = new NativeWebSocketClientBackend
end HttpPlatformBackend
