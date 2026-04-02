package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:

    // Transport for HTTP/1.1 request-response (stream-first)
    // EpollNativeTransport is not yet implemented — Linux falls back to kqueue for now
    private val transport =
        new KqueueNativeTransport // macOS/BSD; TODO: add EpollNativeTransport for Linux

    lazy val client: HttpBackend.Client            = new HttpTransportClient(transport)
    lazy val server: HttpBackend.Server            = new HttpTransportServer(transport)
    lazy val wsClient: HttpBackend.WebSocketClient = new WsTransportClient(transport)

end HttpPlatformBackend
