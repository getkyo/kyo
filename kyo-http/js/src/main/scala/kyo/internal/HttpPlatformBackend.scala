package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client            = new HttpTransportClient(new JsTransport, Http1Protocol)
    lazy val server: HttpBackend.Server            = new HttpTransportServer(new JsTransport, Http1Protocol)
    lazy val wsClient: HttpBackend.WebSocketClient = new WsTransportClient(new JsTransport)
end HttpPlatformBackend
