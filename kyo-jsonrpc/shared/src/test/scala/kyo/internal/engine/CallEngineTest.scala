package kyo.internal.engine

import java.util.concurrent.atomic.AtomicReference
import kyo.*

class CallEngineTest extends JsonRpcTest:

    private val start = Instant.Epoch + 1000.days

    private def withSteppedWall[A](f: (Clock.TimeControl, Clock, AtomicReference[Instant]) => A < (Async & Abort[Any]))(using
        Frame
    ): A < (Async & Abort[Any]) =
        Clock.withTimeControl { control =>
            Clock.get.map { controlled =>
                val wall  = new AtomicReference(start)
                val clock = Clock(Clock.Unsafe.withWall(controlled.unsafe)(() => wall.get()))
                Clock.let(clock)(f(control, clock, wall))
            }
        }

    // One poll interval: the monitor either re-arms its sleep (still waiting) or completes the signal (fired). The race
    // awaits a per-tick copy of the signal, since interrupting the losing arm would otherwise interrupt the signal itself.
    private def tick(control: Clock.TimeControl, signal: Fiber.Promise[Unit, Any])(using Frame): Boolean < Async =
        for
            _     <- control.advance(500.millis)
            fired <- Fiber.Promise.init[Unit, Any]
            _     <- signal.onComplete(_ => fired.completeUnitDiscard)
            ended <- Async.race(control.awaitPendingSleepers(1).andThen(false), fired.get.andThen(true))
        yield ended

    "deadlineNowMillis reads monotonic time, unmoved by a wall-clock step" in {
        import AllowUnsafe.embrace.danger
        withSteppedWall { (control, clock, wall) =>
            for
                before <- Sync.defer(CallEngine.deadlineNowMillis(clock))
                _      <- Sync.defer(wall.set(start + 1.day))
                after  <- Sync.defer(CallEngine.deadlineNowMillis(clock))
                _      <- control.advance(3.seconds)
                later  <- Sync.defer(CallEngine.deadlineNowMillis(clock))
            yield
                assert(after == before)
                assert(later == before + 3000L)
        }
    }

    "the progress-reset monitor times out on monotonic time whichever way the wall clock steps" in {
        import AllowUnsafe.embrace.danger
        withSteppedWall { (control, clock, wall) =>
            for
                signal <- Fiber.Promise.init[Unit, Any]
                dref = AtomicLong.Unsafe.init(CallEngine.deadlineNowMillis(clock) + 5000L)
                _ <- Fiber.initUnscoped(CallEngine.monitorLoop(500.millis, dref, signal, clock))
                _ <- control.awaitPendingSleepers(1)
                // A deadline judged on the wall would be an hour overdue.
                _     <- Sync.defer(wall.set(start + 1.hour))
                early <- Kyo.foreach(1 to 10)(_ => tick(control, signal))
                // A deadline judged on the wall would be a day away.
                _   <- Sync.defer(wall.set(start - 1.day))
                due <- tick(control, signal)
            yield
                assert(early == Chunk.fill(10)(false))
                assert(due)
        }
    }

end CallEngineTest
