package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:

    // Transport2 for HTTP/1.1 request-response (stream-first)
    // EpollNativeTransport2 is not yet implemented — Linux falls back to old transport for now
    private val transport2 =
        new KqueueNativeTransport2 // macOS/BSD; TODO: add EpollNativeTransport2 for Linux

    lazy val client: HttpBackend.Client = new HttpTransportClient2(transport2)
    lazy val server: HttpBackend.Server = new HttpTransportServer2(transport2)

    // WsTransportClient still uses old Transport — will be migrated when WsCodec supports TransportStream2
    private val wsTransport: Transport =
        if isLinux then new EpollNativeTransport
        else new KqueueNativeTransport
    lazy val wsClient: HttpBackend.WebSocketClient = new WsTransportClient(wsTransport)

    private def isLinux: Boolean =
        val os = java.lang.System.getProperty("os.name", "").toLowerCase
        os.contains("linux")

end HttpPlatformBackend
