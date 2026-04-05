package kyo.internal

import kyo.*

private[kyo] object HttpPlatformTransport:
    lazy val default: Transport                = new JsTransport
    lazy val defaultServer: HttpBackend.Server = new HttpTransportServer(new JsTransport)
end HttpPlatformTransport
