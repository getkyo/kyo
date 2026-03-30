package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client            = new HttpTransportClient(new KqueueNativeTransport, Http1Protocol)
    lazy val server: HttpBackend.Server            = new HttpTransportServer(new KqueueNativeTransport, Http1Protocol)
    lazy val wsClient: HttpBackend.WebSocketClient = new WsTransportClient(new KqueueNativeTransport)
end HttpPlatformBackend
