package kyo

abstract class BaseHttpTest extends kyo.test.Test[Any]:

    // No test request carries a deadline of its own: any finite one is a pass condition on wall-clock time, and a loaded
    // machine fails a correct leaf with HttpTimeoutException. The leaf cap alone bounds a request that is never answered.
    // A leaf that tests the timeout sets its own through withConfig.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(Duration.Infinity))(body)

    /** Creates a scoped client that trusts all TLS certificates. For testing only. */
    def initTrustAllClient(
        maxConnectionsPerHost: Int = 100,
        idleConnectionTimeout: Duration = 60.seconds
    )(using Frame): HttpClient < (Async & Scope) =
        HttpClient.init(maxConnectionsPerHost, idleConnectionTimeout, HttpTlsConfig(trustAll = true))

    /** The router for routes the leaf knows to be servable; a refused route fails the leaf with its [[HttpRouteException]]. */
    def routerOf(
        handlers: Seq[HttpHandler[?, ?, ?]],
        cors: Maybe[HttpServerConfig.Cors]
    )(using Frame): kyo.internal.server.HttpRouter =
        kyo.internal.server.HttpRouter.init(handlers, cors).getOrThrow

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

    /** How long [[armedThenAdvance]] waits for its timer before failing: half the suite's leaf timeout, so the failure names the timer instead
      * of the leaf timing out.
      */
    def armedBudget: Duration = timeout * 0.5

    /** Waits until `timeControl` holds a pending sleeper of exactly `duration`, then advances the clock by `duration`. Fails naming `timer`
      * when no such sleeper is armed within [[armedBudget]]. A channel state or a parked put is no proof that a timer is armed: an advance
      * taken before the arming is lost, and the timer then fires a whole `duration` later than the leaf assumes.
      *
      * The fence matches by duration alone. A timer that replaces another of the same duration arms the new sleeper before it cancels the
      * old one, so a leaf first observes a state that follows the last such replacement, and the fence then sees only the timer it targets.
      */
    def armedThenAdvance(timeControl: Clock.TimeControl, timer: String, duration: Duration)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < Async =
        armedThenAdvance(timeControl, timer, duration, duration)

    /** [[armedThenAdvance]] advancing by `by` instead of the timer's whole `duration`, for a leaf that stops short of the deadline or crosses
      * it in steps.
      */
    def armedThenAdvance(timeControl: Clock.TimeControl, timer: String, duration: Duration, by: Duration)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < Async =
        awaitTimer(timeControl, timer, duration).andThen(timeControl.advance(by))

    /** The fence of [[armedThenAdvance]] without the advance, for a leaf that acts on the peer's side once the timer is armed. */
    def awaitTimer(timeControl: Clock.TimeControl, timer: String, duration: Duration)(using Frame, kyo.test.AssertScope): Unit < Async =
        awaitArmed(timeControl, timer, duration, armedBudget).map {
            case Present(missing) => fail(missing)
            case Absent           => ()
        }

    /** Waits up to `within` for a sleeper of exactly `duration` on `timeControl`: `Absent` once one is pending, or the failure message naming
      * `timer`. The bound is timed on the live clock, because a bound on the controlled clock would itself be a sleeper that never fires.
      */
    def awaitArmed(timeControl: Clock.TimeControl, timer: String, duration: Duration, within: Duration)(using
        Frame
    ): Maybe[String] < Async =
        Clock.let(Clock.live) {
            Abort.run[Timeout](Async.timeout(within)(timeControl.awaitPendingSleeper(duration))).map {
                case Result.Success(_) => Absent
                case _                 => Present(s"the $timer ($duration) was never armed on the controlled clock within $within")
            }
        }
    end awaitArmed

end BaseHttpTest
