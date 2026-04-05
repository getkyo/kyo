package kyo.internal

import kyo.*

private[kyo] object HttpPlatformTransport:
    private val isLinux: Boolean =
        java.lang.System.getProperty("os.name", "").toLowerCase.contains("linux")
    lazy val default: Transport =
        if isLinux then new EpollNativeTransport
        else new KqueueNativeTransport
    lazy val defaultServer: HttpBackend.Server =
        if isLinux then new HttpTransportServer(new EpollNativeTransport)
        else new HttpTransportServer(new KqueueNativeTransport)
end HttpPlatformTransport
