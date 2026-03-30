package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:

    private val transport: Transport =
        if isLinux then new EpollNativeTransport
        else new KqueueNativeTransport // macOS/BSD

    lazy val client: HttpBackend.Client            = new HttpTransportClient(transport, Http1Protocol)
    lazy val server: HttpBackend.Server            = new HttpTransportServer(transport, Http1Protocol)
    lazy val wsClient: HttpBackend.WebSocketClient = new WsTransportClient(transport)

    private def isLinux: Boolean =
        val os = java.lang.System.getProperty("os.name", "").toLowerCase
        os.contains("linux")

end HttpPlatformBackend
