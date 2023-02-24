package kyoTest.concurrent

import kyo.core._
import kyo.ios._
import kyo.concurrent.timers._
import kyo.concurrent.fibers._
import scala.concurrent.duration._

import kyoTest.KyoTest
import kyo.concurrent.atomics._
import org.scalatest.compatible.Assertion

class timersTest extends KyoTest {

  private def run[T](io: T > (IOs | Timers | Fibers)): T =
    val a: T > Fibers         = IOs.lazyRun(Timers.run(io))
    val b: Fiber[T] > Nothing = Fibers.run(a)
    val c: Fiber[T]           = b
    IOs.run(c.block)

  "schedule" in run {
    for {
      p <- Fibers.promise[String]
      _ <- Timers.schedule(
          p.complete("hello")(require),
          1.second
      )
      hello <- p.join
    } yield assert(hello == "hello")
  }

  "cancel" in run {
    for {
      p <- Fibers.promise[String]
      task <- Timers.schedule(
          p.complete("hello")(require),
          10.millis
      )
      _         <- task.cancel
      cancelled <- task.isCancelled
      done1     <- p.isDone
      _         <- Fibers.sleep(50.millis)
      done2     <- p.isDone
      taskDone  <- task.isDone
    } yield assert(cancelled && !done1 && !done2 && taskDone)
  }

  "scheduleAtFixedRate" in run {
    for {
      ref <- Atomics.makeInt(0)
      task <- Timers.scheduleAtFixedRate(
          ref.incrementAndGet.unit,
          10.millis,
          10.millis
      )
      _         <- Fibers.sleep(50.millis)
      n         <- ref.get
      cancelled <- task.cancel
    } yield assert(n > 0 && cancelled)
  }

  "scheduleWithFixedDelay" in run {
    for {
      ref <- Atomics.makeInt(0)
      task <- Timers.scheduleWithFixedDelay(
          ref.incrementAndGet.unit,
          10.millis,
          10.millis
      )
      _         <- Fibers.sleep(50.millis)
      n         <- ref.get
      cancelled <- task.cancel
    } yield assert(n > 0 && cancelled)
  }
}
