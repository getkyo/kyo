package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:
    private val transport                          = new JsTransport
    lazy val client: HttpBackend.Client            = new HttpTransportClient(transport)
    lazy val server: HttpBackend.Server            = new HttpTransportServer(transport)
    lazy val wsClient: HttpBackend.WebSocketClient = new WsTransportClient(transport)
end HttpPlatformBackend
