package kyo.internal

import kyo.*
import kyo.internal.client.*

/** Test-only backends using platform transport. */
private[kyo] object HttpTestPlatformBackend:
    private lazy val transport = kyo.net.NetPlatform.transport

    /** The socket client the suites drive, which a browser page has no transport for.
      *
      * A suite reaching for it in a page is asking for something the host does not have, so the leaf cancels rather than failing on the
      * backend probe. Suites hold this in a `lazy val`, so the cancel happens inside the leaf, where the runner can report it, and not in
      * the suite's constructor, where it would take the whole suite down.
      */
    lazy val client: HttpClientBackend =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        if kyo.internal.Platform.isBrowser then
            throw new kyo.test.TestCancelled("this test drives a socket client, and this host is a browser page")
        HttpClientBackend.init(transport, 100, 60.seconds, HttpTlsConfig(trustAll = true))
    end client

    val tlsServerAvailable: Boolean    = true
    val serverTlsConfig: HttpTlsConfig = TlsTestHelper.serverTlsConfig
end HttpTestPlatformBackend
