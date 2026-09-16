package kyo

abstract class BaseHttpTest extends kyo.test.Test[Any]:

    // Linux Native CI HTTP server bring-up + per-request latency can exceed the production 5-second HttpClient
    // default, so every test request would fail with HttpTimeoutException. Wrap every leaf so test requests get a
    // 60s client request timeout (production users still see the 5s default until they set their own via withConfig).
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(60.seconds)) {
            Abort.run[Any](body).map {
                case Result.Success(value) => value
                // A leaf that stands one up on a host that cannot bind has nothing to test rather than something to
                // fail: the browser row runs the suites that do not serve and cancels the ones that do, with no
                // per-leaf annotation to keep in step with the suite. Only this exact refusal cancels, so a bind that
                // fails for any other reason, on any other host, is still a failure.
                case Result.Failure(e: HttpBindException) if e.cause.isInstanceOf[HttpUnsupportedOnHostException] =>
                    throw new kyo.test.TestCancelled(s"this test serves over a socket, and ${e.cause.getMessage}")
                case Result.Failure(e) => Abort.fail(e)
                case Result.Panic(t)   => Abort.panic(t)
            }
        }

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
