package kyo.internal

import kyo.*

private[kyo] object HttpPlatformBackend:
    private val transport2              = new NioTransport2
    lazy val client: HttpBackend.Client = new HttpTransportClient2(transport2)
    lazy val server: HttpBackend.Server = new HttpTransportServer2(transport2)
    // WsTransportClient still uses old Transport — will be migrated when WsCodec supports TransportStream2
    lazy val wsClient: HttpBackend.WebSocketClient = new WsTransportClient(new NioTransport)
end HttpPlatformBackend
