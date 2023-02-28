package kyoTest.concurrent

import kyoTest.KyoTest
import kyo.concurrent.semaphores._
import kyo.concurrent.fibers._
import kyo.core._
import kyo.ios._
import scala.concurrent.duration._
import kyo.concurrent.timers._

class semaphoresTest extends KyoTest {

  def run(v: Unit > (IOs | Fibers | Timers)) =
    Fibers.block(IOs.lazyRun(Timers.run(v)))

  "executes a task with a semaphore" in run {
    for {
      sem <- Semaphores.make(1)
      v   <- sem.run(2)
    } yield assert(v == 2)
  }

  "limits the number of concurrent tasks" in run {
    for {
      sem <- Semaphores.make(1)
      p   <- Fibers.promise[Int]
      b1  <- Fibers.promise[Unit]
      f1  <- Fibers.forkFiber(sem.run(b1.complete(())(_ => p.block)))
      _   <- b1.join
      a1  <- sem.availablePermits
      b2  <- Fibers.promise[Unit]
      f2  <- Fibers.forkFiber(b2.complete(())(_ => sem.run(2)))
      _   <- b2.join
      a2  <- sem.availablePermits
      d1  <- f1.isDone
      d2  <- f2.isDone
      _   <- p.complete(1)
      v1  <- f1.join
      v2  <- f2.join
      a3  <- sem.availablePermits
    } yield assert(a1 == 0 && !d1 && !d2 && a2 == 0 && v1 == 1 && v2 == 2 && a3 == 1)
  }

  "limits the number of concurrent tasks - tryRun" in run {
    for {
      sem <- Semaphores.make(1)
      p   <- Fibers.promise[Int]
      b1  <- Fibers.promise[Unit]
      f1  <- Fibers.forkFiber(sem.tryRun(b1.complete(())(_ => p.block)))
      _   <- b1.join
      a1  <- sem.availablePermits
      b1  <- sem.tryRun(2)
      b2  <- f1.isDone
      _   <- p.complete(1)
      v1  <- f1.join
    } yield assert(a1 == 0 && b1 == None && !b2 && v1 == Some(1))
  }

}
