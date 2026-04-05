package kyo.internal

import kyo.*

/** Test-only backends with TLS support. JVM implementation uses keytool-generated ephemeral certs. */
private[kyo] object HttpTestPlatformBackend:
    lazy val client: HttpBackend.Client =
        new HttpTransportClient(
            new NioTransport(clientSslContext = Present(TlsTestHelper.trustAllSslContext))
        )
    lazy val server: HttpBackend.Server =
        new HttpTransportServer(
            new NioTransport(serverSslContext = Present(TlsTestHelper.serverSslContext))
        )
    lazy val wsClient: HttpBackend.WebSocketClient =
        new WsTransportClient(
            new NioTransport(clientSslContext = Present(TlsTestHelper.trustAllSslContext))
        )
    val tlsServerAvailable: Boolean = true
    val serverTlsConfig: TlsConfig  = TlsConfig.default
end HttpTestPlatformBackend
