package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:

    // Transport2 for HTTP/1.1 request-response (stream-first)
    // EpollNativeTransport2 is not yet implemented — Linux falls back to kqueue for now
    private val transport2 =
        new KqueueNativeTransport2 // macOS/BSD; TODO: add EpollNativeTransport2 for Linux

    lazy val client: HttpBackend.Client            = new HttpTransportClient2(transport2)
    lazy val server: HttpBackend.Server            = new HttpTransportServer2(transport2)
    lazy val wsClient: HttpBackend.WebSocketClient = new WsTransportClient2(transport2)

end HttpPlatformBackend
