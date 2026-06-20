package kyo

abstract class BaseHttpTest extends kyo.test.Test[Any]:

    // Socket-leak detection is disabled (file-descriptor, thread, and fiber detection stay on) because the shared NIO transport
    // can leave a closed socket's fd open past the run: closing a channel only cancels its selector key, and on JDK 11+ the real
    // fd close is deferred until the driver selector runs another select(); nothing wakes the selector on close, so when the fork
    // goes idle these suites flakily leave a connection or listening fd lingering. The leaked target is an opaque socket:[inode]
    // with a per-run inode, so the allowlist cannot match it. The transport is being replaced by kyo-net; the bug and its fix are
    // documented for that module in HANDOFF-listener-fd-leak.md.
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
