package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client            = new HttpTransportClient(new NioTransport, Http1Protocol)
    lazy val server: HttpBackend.Server            = new HttpTransportServer(new NioTransport, Http1Protocol)
    lazy val wsClient: HttpBackend.WebSocketClient = new WsTransportClient(new NioTransport)
end HttpPlatformBackend
