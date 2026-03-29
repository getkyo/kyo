package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client            = new FetchClientBackend
    lazy val server: HttpBackend.Server            = new NodeServerBackend
    lazy val wsClient: HttpBackend.WebSocketClient = new JsWebSocketClientBackend
end HttpPlatformBackend
