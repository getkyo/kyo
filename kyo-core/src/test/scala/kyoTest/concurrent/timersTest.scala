package kyoTest.concurrent

import kyo.concurrent.atomics._
import kyo.concurrent.fibers._
import kyo.concurrent.timers._
import kyo.core._
import kyo.ios._
import kyoTest.KyoTest
import org.scalatest.compatible.Assertion

import scala.concurrent.duration._

class timersTest extends KyoTest {

  private def run[T](io: T > (IOs | Timers | Fibers)): T =
    val a: T > Fibers         = IOs.lazyRun(Timers.run(io))
    val b: Fiber[T] > Nothing = Fibers.run(a)
    val c: Fiber[T]           = b
    IOs.run(c.block)

  "schedule" in run {
    for {
      p     <- Fibers.promise[String]
      _     <- Timers.schedule(1.milli)(p.complete("hello")(require))
      hello <- p.join
    } yield assert(hello == "hello")
  }

  "cancel" in run {
    for {
      p         <- Fibers.promise[String]
      task      <- Timers.schedule(5.second)(p.complete("hello")(require))
      _         <- task.cancel
      cancelled <- retry(task.isCancelled)
      done1     <- p.isDone
      _         <- Fibers.sleep(5.millis)
      done2     <- p.isDone
      taskDone  <- task.isDone
    } yield assert(cancelled && !done1 && !done2 && taskDone)
  }

  "scheduleAtFixedRate" in run {
    for {
      ref <- Atomics.forInt(0)
      task <- Timers.scheduleAtFixedRate(
          10.millis,
          10.millis
      )(ref.incrementAndGet.unit)
      _         <- Fibers.sleep(50.millis)
      n         <- ref.get
      cancelled <- task.cancel
    } yield assert(n > 0 && cancelled)
  }

  "scheduleWithFixedDelay" in run {
    for {
      ref <- Atomics.forInt(0)
      task <- Timers.scheduleWithFixedDelay(
          10.millis,
          10.millis
      )(ref.incrementAndGet.unit)
      _         <- Fibers.sleep(50.millis)
      n         <- ref.get
      cancelled <- task.cancel
    } yield assert(n > 0 && cancelled)
  }
}
