package kyo.net

import kyo.*

class ProgressWatchdogTest extends Test:

    private def driveUntilDone[A](tc: Clock.TimeControl, fiber: Fiber[A, Any], step: Duration)(using Frame): Unit < Async =
        Scope.run {
            for
                _ <- Scope.acquireRelease(Fiber.initUnscoped(
                    Loop.forever(tc.awaitPendingSleepers(1).andThen(tc.advance(step)))
                ))(driver => driver.interrupt.andThen(driver.getResult).unit)
                _ <- fiber.getResult
            yield ()
        }

    "the clock driver exits when work finishes before its next sleeper" in {
        Clock.withTimeControl { tc =>
            Scope.run {
                for
                    completionGate <- Latch.init(1)
                    clockWaitEntry <- Latch.init(1)
                    clockWaitGate  <- Latch.init(1)
                    work           <- Scope.acquireRelease(Fiber.initUnscoped(completionGate.await.andThen(42)))(fiber =>
                        fiber.interrupt.andThen(fiber.getResult).unit
                    )
                    gatedClock = new Clock.TimeControl:
                        export tc.{awaitPendingSleepers as _, *}
                        def awaitPendingSleepers(count: Int): Unit < Async =
                            clockWaitEntry.release.andThen(clockWaitGate.await).andThen(tc.awaitPendingSleepers(count))
                    driver <- Scope.acquireRelease(Fiber.initUnscoped(driveUntilDone(gatedClock, work, 20.seconds)))(fiber =>
                        fiber.interrupt.andThen(fiber.getResult).unit
                    )
                    _                    <- clockWaitEntry.await
                    driverCompletedEarly <- driver.done
                    _                    <- completionGate.release
                    value                <- work.get
                    // Complete the work in the gap before the driver's sleeper wait is allowed to start.
                    _ <- clockWaitGate.release
                    _ <- driver.get
                yield
                    assert(!driverCompletedEarly)
                    assert(value == 42)
            }
        }
    }

    "a computation whose progress stops is failed as stalled" in {
        Clock.withTimeControl { tc =>
            for
                never <- Promise.init[Unit, Any]
                fiber <- Fiber.initUnscoped(Abort.run[NetException | Closed | ProgressWatchdog.Stalled](
                    ProgressWatchdog.run(20.seconds, 3)(progress => progress.tick.andThen(never.get))
                ))
                _      <- driveUntilDone(tc, fiber, 20.seconds)
                result <- fiber.get
            yield result match
                case Result.Failure(ProgressWatchdog.Stalled(windows, window, _)) =>
                    assert((windows, window) == (3, 20.seconds))
                case other => fail(s"a stalled computation was not failed as stalled: $other")
        }
    }

    "a computation that keeps progressing completes, however long it takes" in {
        // 120 virtual seconds with a tick every 30: no three 20-second windows pass without one.
        Clock.withTimeControl { tc =>
            for
                fiber <- Fiber.initUnscoped(Abort.run[NetException | Closed | ProgressWatchdog.Stalled](
                    ProgressWatchdog.run(20.seconds, 3) { progress =>
                        Loop(0) { i =>
                            if i == 4 then Loop.done(i)
                            else progress.tick.andThen(Async.sleep(30.seconds)).andThen(Loop.continue(i + 1))
                        }
                    }
                ))
                _      <- driveUntilDone(tc, fiber, 10.seconds)
                result <- fiber.get
            yield assert(result == Result.succeed(4))
        }
    }

    "a computation's own failure ends the watch with that failure" in {
        Clock.withTimeControl { _ =>
            Abort.run[NetException | Closed | ProgressWatchdog.Stalled](
                ProgressWatchdog.run(20.seconds, 3)(_ => Abort.fail(Closed("watched", summon[Frame], "closed by the body")))
            ).map {
                case Result.Failure(_: Closed) => succeed
                case other                     => fail(s"the body's failure was not kept: $other")
            }
        }
    }
end ProgressWatchdogTest
