package kyo.internal

import kyo.*

/** Test-only backends with TLS support. Native implementation uses openssl-generated ephemeral certs. */
private[kyo] object HttpTestPlatformBackend:
    lazy val client: HttpBackend.Client =
        new HttpTransportClient(new TrustAllNativeTransport)
    lazy val server: HttpBackend.Server =
        new HttpTransportServer(new KqueueNativeTransport)
    lazy val wsClient: HttpBackend.WebSocketClient =
        new WsTransportClient(new TrustAllNativeTransport)
    val tlsServerAvailable: Boolean = true
    val serverTlsConfig: TlsConfig  = TlsTestHelper.serverTlsConfig
end HttpTestPlatformBackend

/** Transport wrapper that forces trustAll=true on client TLS connections for self-signed test certs. */
private[kyo] class TrustAllNativeTransport extends Transport:

    private val inner = new KqueueNativeTransport

    type Connection = inner.Connection

    def connect(host: String, port: Int, tls: Maybe[TlsConfig])(using
        Frame
    ): Connection < (Async & Abort[HttpException]) =
        val adjusted = tls.map(_.copy(trustAll = true))
        inner.connect(host, port, adjusted)
    end connect

    def listen(host: String, port: Int, backlog: Int, tls: Maybe[TlsConfig])(using
        Frame
    ): TransportListener[Connection] < (Async & Scope) =
        inner.listen(host, port, backlog, tls)

    def isAlive(c: Connection)(using Frame): Boolean < Sync =
        inner.isAlive(c)

    def closeNow(c: Connection)(using Frame): Unit < Sync =
        inner.closeNow(c)

    def close(c: Connection, gracePeriod: Duration)(using Frame): Unit < Async =
        inner.close(c, gracePeriod)

end TrustAllNativeTransport
