package kyo

import java.util.concurrent.Executors
import org.scalatest.compatible.Assertion

class TimerTest extends Test:

    "schedule" in run {
        for
            p     <- Promise.init[Nothing, String]
            _     <- Timer.schedule(1.milli)(p.complete(Result.succeed("hello")).map(require(_)))
            hello <- p.get
        yield assert(hello == "hello")
    }

    "custom executor" in runJVM {
        val exec = Executors.newSingleThreadScheduledExecutor()
        Timer.let(Timer(exec)) {
            for
                p     <- Promise.init[Nothing, String]
                _     <- Timer.schedule(1.milli)(p.complete(Result.succeed("hello")).map(require(_)))
                hello <- p.get
            yield assert(hello == "hello")
        }
    }

    "cancel" in runJVM {
        for
            p         <- Promise.init[Nothing, String]
            task      <- Timer.schedule(5.seconds)(p.complete(Result.succeed("hello")).map(require(_)))
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
end TimerTest
