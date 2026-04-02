package kyo.internal

import kyo.*

/** TLS-configured backends for tests. Uses TlsTestHelper's ephemeral certs. */
object TlsTestBackend:

    /** Client that trusts any certificate (for connecting to self-signed test servers). */
    lazy val client: HttpBackend.Client =
        new HttpTransportClient2(
            new NioTransport2(clientSslContext = Present(TlsTestHelper.trustAllSslContext))
        )

    /** Server that terminates TLS with a self-signed cert for localhost. */
    lazy val server: HttpBackend.Server =
        new HttpTransportServer2(
            new NioTransport2(serverSslContext = Present(TlsTestHelper.serverSslContext))
        )

    /** WebSocket client that trusts any certificate. */
    lazy val wsClient: HttpBackend.WebSocketClient =
        new WsTransportClient2(
            new NioTransport2(clientSslContext = Present(TlsTestHelper.trustAllSslContext))
        )

end TlsTestBackend
