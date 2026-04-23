package kyo.internal

import kyo.*
import kyo.internal.client.*

/** Test-only backends using platform transport. */
private[kyo] object HttpTestPlatformBackend:
    private lazy val transport = HttpPlatformTransport.transport

    lazy val client: HttpClientBackend[?] =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        HttpClientBackend.init(transport, 100, 60.seconds, HttpTlsConfig(trustAll = true))
    end client

    val tlsServerAvailable: Boolean    = true
    val serverTlsConfig: HttpTlsConfig = TlsTestHelper.serverTlsConfig
end HttpTestPlatformBackend
