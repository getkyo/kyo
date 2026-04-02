package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:

    // Select kqueue (macOS/BSD) or epoll (Linux) based on the OS at startup.
    private val isLinux: Boolean =
        java.lang.System.getProperty("os.name", "").toLowerCase.contains("linux")

    // Two concrete transport instances — at most one is used at runtime.
    // Keeping concrete types is required so the compiler can resolve Tag[C] for the connection type.
    private val kqueueTransport = new KqueueNativeTransport
    private val epollTransport  = new EpollNativeTransport

    lazy val client: HttpBackend.Client =
        if isLinux then new HttpTransportClient(epollTransport)
        else new HttpTransportClient(kqueueTransport)

    lazy val server: HttpBackend.Server =
        if isLinux then new HttpTransportServer(epollTransport)
        else new HttpTransportServer(kqueueTransport)

    lazy val wsClient: HttpBackend.WebSocketClient =
        if isLinux then new WsTransportClient(epollTransport)
        else new WsTransportClient(kqueueTransport)

end HttpPlatformBackend
