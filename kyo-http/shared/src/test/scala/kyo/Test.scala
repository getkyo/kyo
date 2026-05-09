package kyo

import kyo.internal.BaseKyoCoreTest
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override def timeout = 60.seconds

    override given executionContext: ExecutionContext = kyo.internal.Platform.executionContext

    // Linux Native CI HTTP server bring-up + per-request latency can exceed the production
    // 5-second HttpClient default — every test request would fail with HttpTimeoutException.
    // Tests get a 60s client request timeout to match the per-test budget; production users
    // still see the 5s default until they set their own via withConfig.
    override def run(v: Future[Assertion] < (Abort[Any] & Async & Scope))(using Frame): Future[Assertion] =
        super.run(HttpClient.withConfig(_.timeout(60.seconds))(v))

    /** Creates a scoped client that trusts all TLS certificates. For testing only. */
    def initTrustAllClient(
        maxConnectionsPerHost: Int = 100,
        idleConnectionTimeout: Duration = 60.seconds
    )(using Frame): HttpClient < (Async & Scope) =
        HttpClient.init(maxConnectionsPerHost, idleConnectionTimeout, HttpTlsConfig(trustAll = true))

end Test
