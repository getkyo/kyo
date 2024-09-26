package kyo

import java.util.concurrent.Executors
import org.scalatest.compatible.Assertion

class TimerTest extends Test:

    "schedule" in run {
        for
            p     <- Promise.init[Nothing, String]
            _     <- Timer.schedule(1.milli)(p.complete(Result.success("hello")).map(require(_)))
            hello <- p.get
        yield assert(hello == "hello")
    }

    "custom executor" in runJVM {
        val exec = Executors.newSingleThreadScheduledExecutor()
        import AllowUnsafe.embrace.danger
        Timer.let(Timer(Timer.Unsafe(exec))) {
            for
                p     <- Promise.init[Nothing, String]
                _     <- Timer.schedule(1.milli)(p.complete(Result.success("hello")).map(require(_)))
                hello <- p.get
            yield assert(hello == "hello")
        }
    }

    "cancel" in runJVM {
        for
            p         <- Promise.init[Nothing, String]
            task      <- Timer.schedule(5.seconds)(p.complete(Result.success("hello")).map(require(_)))
            _         <- task.cancel
            cancelled <- untilTrue(task.cancelled)
            done1     <- p.done
            _         <- Async.sleep(5.millis)
            done2     <- p.done
        yield assert(cancelled && !done1 && !done2)
    }

    "scheduleAtFixedRate" in run {
        for
            ref <- AtomicInt.init(0)
            task <- Timer.scheduleAtFixedRate(
                1.milli,
                1.milli
            )(ref.incrementAndGet.unit)
            _         <- Async.sleep(5.millis)
            n         <- ref.get
            cancelled <- task.cancel
        yield assert(n > 0 && cancelled)
    }

    "scheduleWithFixedDelay" in runJVM {
        for
            ref <- AtomicInt.init(0)
            task <- Timer.scheduleWithFixedDelay(
                1.milli,
                1.milli
            )(ref.incrementAndGet.unit)
            _         <- Async.sleep(5.millis)
            n         <- ref.get
            cancelled <- task.cancel
        yield assert(n > 0 && cancelled)
    }

    "scheduleWithFixedDelay 2" in runJVM {
        for
            ref <- AtomicInt.init(0)
            task <- Timer.scheduleWithFixedDelay(
                1.milli
            )(ref.incrementAndGet.unit)
            _         <- Async.sleep(5.millis)
            n         <- ref.get
            cancelled <- task.cancel
        yield assert(n > 0 && cancelled)
    }

    "unsafe" - {
        import AllowUnsafe.embrace.danger

        "should schedule task correctly" in {
            val testUnsafe = new TestUnsafeTimer()
            val task       = testUnsafe.schedule(1.second)(())
            assert(testUnsafe.scheduledTasks.nonEmpty)
            assert(task.isInstanceOf[TimerTask.Unsafe])
        }

        "should schedule at fixed rate correctly" in {
            val testUnsafe = new TestUnsafeTimer()
            val task       = testUnsafe.scheduleAtFixedRate(1.second, 2.seconds)(())
            assert(testUnsafe.fixedRateTasks.nonEmpty)
            assert(task.isInstanceOf[TimerTask.Unsafe])
        }

        "should schedule with fixed delay correctly" in {
            val testUnsafe = new TestUnsafeTimer()
            val task       = testUnsafe.scheduleWithFixedDelay(1.second, 2.seconds)(())
            assert(testUnsafe.fixedDelayTasks.nonEmpty)
            assert(task.isInstanceOf[TimerTask.Unsafe])
        }

        "should convert to safe Timer" in {
            val testUnsafe = new TestUnsafeTimer()
            val safeTimer  = testUnsafe.safe
            assert(safeTimer.isInstanceOf[Timer])
        }
    }

    class TestUnsafeTimer extends Timer.Unsafe:
        var scheduledTasks  = List.empty[(Duration, () => Unit)]
        var fixedRateTasks  = List.empty[(Duration, Duration, () => Unit)]
        var fixedDelayTasks = List.empty[(Duration, Duration, () => Unit)]

        def schedule(delay: Duration)(f: => Unit)(using AllowUnsafe): TimerTask.Unsafe =
            scheduledTasks = (delay, () => f) :: scheduledTasks
            TimerTask.Unsafe.noop

        def scheduleAtFixedRate(initialDelay: Duration, period: Duration)(f: => Unit)(using AllowUnsafe): TimerTask.Unsafe =
            fixedRateTasks = (initialDelay, period, () => f) :: fixedRateTasks
            TimerTask.Unsafe.noop

        def scheduleWithFixedDelay(initialDelay: Duration, period: Duration)(f: => Unit)(using AllowUnsafe): TimerTask.Unsafe =
            fixedDelayTasks = (initialDelay, period, () => f) :: fixedDelayTasks
            TimerTask.Unsafe.noop
    end TestUnsafeTimer

end TimerTest
