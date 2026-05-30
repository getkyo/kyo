package kyo.internal.engine

import kyo.*

private[kyo] object RateLimitEngine:

    /** Wraps eff in meter.run if meter is Present, else passes through.
      * Used for call/callWithProgress/callPartialResults only; notify bypasses (DESIGN §11, §20 invariant 4).
      *
      * Meter.run wraps eff via Sync.ensure(release())(eff). Sync.ensure fires cleanup inline only when eff
      * produces a pure value; if Abort.fail escapes eff, cleanup is deferred to IOTask termination. To ensure
      * the semaphore permit is released promptly on Abort failure, Abort.run[JsonRpcError | Closed] is passed
      * as the argument to m.run so that eff's Abort.fail is caught inside Sync.ensure (producing a pure Result),
      * and the re-raise happens OUTSIDE m.run after cleanup has already fired.
      */
    def maxInFlightGuard[A, S](
        meter: Maybe[Meter]
    )(eff: => A < (S & Abort[JsonRpcError | Closed]))(using Frame): A < (S & Async & Abort[JsonRpcError | Closed]) =
        meter match
            case Absent => eff
            case Present(m) =>
                m.run(Abort.run[JsonRpcError | Closed](eff)).map {
                    case Result.Success(v) => v
                    case Result.Failure(e) => Abort.fail(e)
                    case Result.Panic(t)   => Abort.panic(t)
                }

end RateLimitEngine
