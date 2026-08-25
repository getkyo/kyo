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

    /** Polls until `condition` holds, giving up after a bound that only exists so a broken subject fails instead of spinning forever. The
      * interval sleeps on the live clock (not `Async.sleep`), so it works inside `Clock.withTimeControl`; a slower machine just polls more.
      */
    def pollUntil(condition: => Boolean, maxPolls: Int = 10000)(using Frame): Boolean < Async =
        Loop.indexed { i =>
            if condition then Loop.done(true)
            else if i >= maxPolls then Loop.done(false)
            else Clock.live.sleep(1.milli).map(_.get).andThen(Loop.continue)
        }

end BaseHttpTest
