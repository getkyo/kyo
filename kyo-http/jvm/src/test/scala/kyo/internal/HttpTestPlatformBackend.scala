package kyo.internal

import kyo.*

/** Test-only backends with TLS support. JVM implementation uses keytool-generated ephemeral certs. */
private[kyo] object HttpTestPlatformBackend:
    lazy val client: HttpBackend.Client =
        new HttpTransportClient2(
            new NioTransport2(clientSslContext = Present(TlsTestHelper.trustAllSslContext))
        )
    lazy val server: HttpBackend.Server =
        new HttpTransportServer2(
            new NioTransport2(serverSslContext = Present(TlsTestHelper.serverSslContext))
        )
    lazy val wsClient: HttpBackend.WebSocketClient =
        new WsTransportClient2(
            new NioTransport2(clientSslContext = Present(TlsTestHelper.trustAllSslContext))
        )
    val tlsServerAvailable: Boolean = true
end HttpTestPlatformBackend
