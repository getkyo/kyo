package kyo2

import java.util.concurrent.Executors
import org.scalatest.compatible.Assertion

class TimerTest extends Test:

    "schedule" in run {
        for
            p     <- Promise.init[Nothing, String]
            _     <- Timers.schedule(1.milli)(p.complete(Result.success("hello")).map(require(_)))
            hello <- p.get
        yield assert(hello == "hello")
    }

    "custom executor" in runJVM {
        val exec = Executors.newSingleThreadScheduledExecutor()
        Timers.let(Timer(exec)) {
            for
                p     <- Promise.init[Nothing, String]
                _     <- Timers.schedule(1.milli)(p.complete(Result.success("hello")).map(require(_)))
                hello <- p.get
            yield assert(hello == "hello")
        }
    }

    "cancel" in runJVM {
        for
            p         <- Promise.init[Nothing, String]
            task      <- Timers.schedule(5.seconds)(p.complete(Result.success("hello")).map(require(_)))
            _         <- task.cancel
            cancelled <- retry(task.isCancelled)
            done1     <- p.isDone
            _         <- Async.sleep(5.millis)
            done2     <- p.isDone
        yield assert(cancelled && !done1 && !done2)
    }

    "scheduleAtFixedRate" in run {
        for
            ref <- Atomic.initInt(0)
            task <- Timers.scheduleAtFixedRate(
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
            ref <- Atomic.initInt(0)
            task <- Timers.scheduleWithFixedDelay(
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
            ref <- Atomic.initInt(0)
            task <- Timers.scheduleWithFixedDelay(
                1.milli
            )(ref.incrementAndGet.unit)
            _         <- Async.sleep(5.millis)
            n         <- ref.get
            cancelled <- task.cancel
        yield assert(n > 0 && cancelled)
    }
end TimerTest
