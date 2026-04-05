package kyo.internal

import kyo.*

private[kyo] object HttpPlatformTransport:
    lazy val default: Transport                = new NioTransport
    lazy val defaultServer: HttpBackend.Server = new HttpTransportServer(new NioTransport)
end HttpPlatformTransport
