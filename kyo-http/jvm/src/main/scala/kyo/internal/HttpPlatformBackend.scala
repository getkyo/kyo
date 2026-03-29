package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client            = new NettyClientBackend
    lazy val server: HttpBackend.Server            = new NettyServerBackend
    lazy val wsClient: HttpBackend.WebSocketClient = new NettyWebSocketClientBackend
end HttpPlatformBackend
