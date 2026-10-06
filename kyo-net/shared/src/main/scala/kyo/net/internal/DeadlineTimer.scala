package kyo.net.internal

import kyo.*

/** The timer a finite connect or handshake deadline arms.
  *
  * A zero deadline has expired by the time it is armed, so its timer is already complete and every callback attached to it runs at once,
  * before the operation it bounds can finish. A live zero-length sleep would instead be scheduled on the clock executor and race that
  * operation, so a zero deadline would sometimes fire and sometimes not. Not for the accept backoff or the reclaim graces: a timer that
  * completes inline re-arms inline there, which recurses.
  */
private[net] object DeadlineTimer:
    def arm(clock: Clock, timeout: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        if timeout == Duration.Zero then Fiber.Unsafe.fromResult(Result.succeed(()))
        else clock.unsafe.sleep(timeout)
end DeadlineTimer
