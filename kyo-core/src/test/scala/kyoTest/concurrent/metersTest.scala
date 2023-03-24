package kyoTest.concurrent

import kyo.concurrent.atomics._
import kyo.concurrent.fibers._
import kyo.concurrent.meters._
import kyo.concurrent.timers._
import kyo.core._
import kyo.ios._
import kyoTest.KyoTest

import scala.concurrent.duration._

class metersTest extends KyoTest {

  "mutex" - {
    "ok" in run {
      for {
        t <- Meters.mutex
        v <- t.run(2)
      } yield assert(v == 2)
    }

    "run" in run {
      for {
        t  <- Meters.mutex
        p  <- Fibers.promise[Int]
        b1 <- Fibers.promise[Unit]
        f1 <- Fibers.forkFiber(t.run(b1.complete(())(_ => p.block)))
        _  <- b1.join
        a1 <- t.isAvailable
        b2 <- Fibers.promise[Unit]
        f2 <- Fibers.forkFiber(b2.complete(())(_ => t.run(2)))
        _  <- b2.join
        a2 <- t.isAvailable
        d1 <- f1.isDone
        d2 <- f2.isDone
        _  <- p.complete(1)
        v1 <- f1.join
        v2 <- f2.join
        a3 <- t.isAvailable
      } yield assert(!a1 && !d1 && !d2 && !a2 && v1 == 1 && v2 == 2 && a3)
    }

    "tryRun" in run {
      for {
        sem <- Meters.semaphore(1)
        p   <- Fibers.promise[Int]
        b1  <- Fibers.promise[Unit]
        f1  <- Fibers.forkFiber(sem.tryRun(b1.complete(())(_ => p.block)))
        _   <- b1.join
        a1  <- sem.isAvailable
        b1  <- sem.tryRun(2)
        b2  <- f1.isDone
        _   <- p.complete(1)
        v1  <- f1.join
      } yield assert(!a1 && b1 == None && !b2 && v1 == Some(1))
    }
  }

  "semaphore" - {
    "ok" in run {
      for {
        t  <- Meters.semaphore(2)
        v1 <- t.run(2)
        v2 <- t.run(3)
      } yield assert(v1 == 2 && v2 == 3)
    }

    "run" in run {
      for {
        t  <- Meters.semaphore(2)
        p  <- Fibers.promise[Int]
        b1 <- Fibers.promise[Unit]
        f1 <- Fibers.forkFiber(t.run(b1.complete(())(_ => p.block)))
        _  <- b1.join
        b2 <- Fibers.promise[Unit]
        f2 <- Fibers.forkFiber(t.run(b2.complete(())(_ => p.block)))
        _  <- b2.join
        a1 <- t.isAvailable
        b3 <- Fibers.promise[Unit]
        f2 <- Fibers.forkFiber(b3.complete(())(_ => t.run(2)))
        _  <- b3.join
        a2 <- t.isAvailable
        d1 <- f1.isDone
        d2 <- f2.isDone
        _  <- p.complete(1)
        v1 <- f1.join
        v2 <- f2.join
        a3 <- t.isAvailable
      } yield assert(!a1 && !d1 && !d2 && !a2 && v1 == 1 && v2 == 2 && a3)
    }

    "tryRun" in run {
      for {
        sem <- Meters.semaphore(2)
        p   <- Fibers.promise[Int]
        b1  <- Fibers.promise[Unit]
        f1  <- Fibers.forkFiber(sem.tryRun(b1.complete(())(_ => p.block)))
        _   <- b1.join
        b2  <- Fibers.promise[Unit]
        f2  <- Fibers.forkFiber(sem.tryRun(b2.complete(())(_ => p.block)))
        _   <- b2.join
        a1  <- sem.isAvailable
        b3  <- sem.tryRun(2)
        b4  <- f1.isDone
        b5  <- f2.isDone
        _   <- p.complete(1)
        v1  <- f1.join
        v2  <- f2.join
      } yield assert(!a1 && b3 == None && !b4 && !b5 && v1 == Some(1) && v2 == Some(1))
    }
  }

  def loop(meter: Meter, counter: AtomicInt): Unit > (IOs | Fibers) =
    meter.run(counter.incrementAndGet)(_ => loop(meter, counter))

  "rate limiter" - {
    "ok" in run {
      for {
        t  <- Meters.rateLimiter(2, 10.millis)
        v1 <- t.run(2)
        v2 <- t.run(3)
      } yield assert(v1 == 2 && v2 == 3)
    }
    "one loop" in run {
      for {
        meter   <- Meters.rateLimiter(10, 10.millis)
        counter <- Atomics.forInt(0)
        f1      <- Fibers.forkFiber(loop(meter, counter))
        _       <- Fibers.sleep(50.millis)
        _       <- f1.interrupt
        v1      <- counter.get
      } yield assert(v1 >= 30 && v1 <= 200)
    }
    "two loops" in run {
      for {
        meter   <- Meters.rateLimiter(10, 10.millis)
        counter <- Atomics.forInt(0)
        f1      <- Fibers.forkFiber(loop(meter, counter))
        f2      <- Fibers.forkFiber(loop(meter, counter))
        _       <- Fibers.sleep(50.millis)
        _       <- f1.interrupt
        _       <- f2.interrupt
        v1      <- counter.get
      } yield assert(v1 >= 30 && v1 <= 200)
    }
  }

  "pipepline" - {

    "run" in run {
      for {
        meter   <- Meters.pipeline(Meters.rateLimiter(2, 1.millis), Meters.mutex)
        counter <- Atomics.forInt(0)
        f1      <- Fibers.forkFiber(loop(meter, counter))
        f2      <- Fibers.forkFiber(loop(meter, counter))
        _       <- Fibers.sleep(50.millis)
        _       <- f1.interrupt
        _       <- f2.interrupt
        v1      <- counter.get
      } yield assert(v1 > 50 && v1 < 200)
    }

    // "tryRun" in run {
    //   for {
    //     meter   <- Meters.pipeline(Meters.rateLimiter(2, 1.millis), Meters.mutex)
    //     counter <- Atomics.forInt(0)
    //     f1      <- Fibers.forkFiber(loop(meter, counter))
    //     _       <- Fibers.sleep(50.millis)
    //     _       <- retry(meter.isAvailable(!_))
    //     _       <- Fibers.sleep(50.millis)
    //     r       <- meter.tryRun(())
    //     _       <- f1.interrupt
    //   } yield assert(r == None)
    // }
  }
}
