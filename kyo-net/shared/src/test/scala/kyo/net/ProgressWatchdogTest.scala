package kyo.net

import kyo.*

class ProgressWatchdogTest extends Test:

    private def driveUntilDone[A, S](tc: Clock.TimeControl, fiber: Fiber[A, S], step: Duration)(using Frame): Unit < Async =
        Loop.foreach {
            fiber.done.map { done =>
                if done then Loop.done(())
                else tc.awaitPendingSleepers(1).andThen(tc.advance(step)).andThen(Loop.continue)
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
