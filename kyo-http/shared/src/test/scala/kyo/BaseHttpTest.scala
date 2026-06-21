package kyo

abstract class BaseHttpTest extends kyo.test.Test[Any]:

    // Only the socket category is disabled. These suites start and Scope-close HTTP servers, but the NIO transport
    // defers a listening socket's real fd close to its idle selector's next select(), which nothing wakes, so the
    // listener fd outlives the run. That fix belongs to the transport (frozen for the kyo-net rewrite); the socket is an
    // opaque socket:[inode] on an ephemeral port that no allowlist can match. File-descriptor, thread, and fiber
    // detection stay on.
    override def config = super.config.leakCheckSockets(false)

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
