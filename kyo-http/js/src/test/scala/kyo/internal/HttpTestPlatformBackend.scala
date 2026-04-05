package kyo.internal

import kyo.*

/** Test-only backends with TLS support. JS implementation uses openssl-generated ephemeral certs. */
private[kyo] object HttpTestPlatformBackend:
    lazy val client: HttpBackend.Client =
        new HttpTransportClient(new TrustAllJsTransport)
    lazy val server: HttpBackend.Server =
        new HttpTransportServer(new JsTransport)
    lazy val wsClient: HttpBackend.WebSocketClient =
        new WsTransportClient(new TrustAllJsTransport)
    val tlsServerAvailable: Boolean = true
    val serverTlsConfig: TlsConfig  = TlsTestHelper.serverTlsConfig
end HttpTestPlatformBackend

/** Transport wrapper that forces trustAll=true on client TLS connections for self-signed test certs. */
private[kyo] class TrustAllJsTransport extends Transport:

    private val inner = new JsTransport

    type Connection = inner.Connection

    def connect(address: TransportAddress, tls: Maybe[TlsConfig])(using
        Frame
    ): Connection < (Async & Abort[HttpException]) =
        val adjusted = tls.map(_.copy(trustAll = true))
        inner.connect(address, adjusted)
    end connect

    def listen(address: TransportAddress, backlog: Int, tls: Maybe[TlsConfig])(using
        Frame
    ): TransportListener[Connection] < (Async & Scope) =
        inner.listen(address, backlog, tls)

    def isAlive(c: Connection)(using Frame): Boolean < Sync =
        inner.isAlive(c)

    def closeNow(c: Connection)(using Frame): Unit < Async =
        inner.closeNow(c)

    def close(c: Connection, gracePeriod: Duration)(using Frame): Unit < Async =
        inner.close(c, gracePeriod)

end TrustAllJsTransport
