package kyo

abstract class BaseHttpTest extends kyo.test.Test[Any]:

    // No test request carries a deadline of its own: any finite one is a pass condition on wall-clock time, and a loaded
    // machine fails a correct leaf with HttpTimeoutException. The leaf cap alone bounds a request that is never answered.
    // A leaf that tests the timeout sets its own through withConfig.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(HttpClientConfig.TimeLimit.unlimited))(body)

    /** Creates a scoped client that trusts all TLS certificates. For testing only. */
    def initTrustAllClient(
        maxConnectionsPerHost: HttpClient.PoolSize = HttpClient.PoolSize.default,
        idleConnectionTimeout: HttpClientConfig.TimeLimit = HttpClientConfig.TimeLimit.defaultIdleConnectionTimeout
    )(using Frame): HttpClient < (Async & Scope) =
        HttpClient.init(maxConnectionsPerHost, idleConnectionTimeout, HttpTlsConfig(trustAll = true))

    /** Polls until `condition` holds, giving up after `maxPolls`, a bound that only exists so a broken subject fails instead of spinning
      * forever. Between checks it yields to the scheduler, by awaiting a fiber it starts, rather than sleeping: no poll reads a clock, so the
      * pass condition is the observed state alone, never elapsed time, and the poll works the same inside `Clock.withTimeControl`, where a
      * sleep would wait on virtual time. Each yield lets the fibers under test take a step, so the bound counts their steps, and it is
      * large because a yield costs microseconds.
      */
    def pollUntil(condition: => Boolean, maxPolls: Int = 1000000)(using Frame): Boolean < Async =
        Loop.indexed { i =>
            if condition then Loop.done(true)
            else if i >= maxPolls then Loop.done(false)
            else Fiber.initUnscoped(Kyo.unit).map(_.get).andThen(Loop.continue)
        }

end BaseHttpTest
