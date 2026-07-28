package kyo

abstract class BaseHttpTest extends kyo.test.Test[Any]:

    // Linux Native CI HTTP server bring-up + per-request latency can exceed the production 5-second HttpClient
    // default, so every test request would fail with HttpTimeoutException. Wrap every leaf so test requests get a
    // 60s client request timeout (production users still see the 5s default until they set their own via withConfig).
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(60.seconds))(body)

    /** Creates a scoped client that trusts all TLS certificates. For testing only. */
    def initTrustAllClient(
        maxConnectionsPerHost: Int = 100,
        idleConnectionTimeout: Duration = 60.seconds
    )(using Frame): HttpClient < (Async & Scope) =
        HttpClient.init(maxConnectionsPerHost, idleConnectionTimeout, HttpTlsConfig(trustAll = true))

end BaseHttpTest
