package kyo

import kyo.randoms.Randoms
import kyo.zios.ZIOs
import zio.Task
import zio.ZIO

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import kyo.core.internal._
import ios._
import clocks._
import consoles._
import resources._
import tries._
import aborts._
import concurrent.fibers._
import concurrent.timers._

abstract class KyoZioApp {

  final def main(args: Array[String]): Unit =
    KyoZioApp.run(Duration.Inf)(run(args.toList))

  def run(
      args: List[String]
  ): Unit > (IOs with Fibers with Resources with Clocks with Consoles with Randoms with Timers with ZIOs)

}

object KyoZioApp {

  def block[T](timeout: Duration)(t: Task[T]): T =
    zio.Unsafe.unsafe(implicit u =>
      zio.Runtime.default.unsafe.run(
          t.timeoutFail(new TimeoutException)(zio.Duration.fromScala(timeout))
      ).getOrThrow()
    )

  def run[T](timeout: Duration)(
      v: T > (IOs with Fibers with Resources with Clocks with Consoles with Randoms with Timers with ZIOs)
  ): T =
    block(timeout)(runTask(v))

  def runTask[T](
      v: T > (IOs with Fibers with Resources with Clocks with Consoles with Randoms with Timers with ZIOs)
  ): Task[T] = {
    val v1: T > (IOs with Fibers with Resources with Clocks with Consoles with Timers with ZIOs) =
      Randoms.run(v)
    val v2: T > (IOs with Fibers with Resources with Clocks with Timers with ZIOs) =
      Consoles.run(v1)
    val v3: T > (IOs with Fibers with Resources with Timers with ZIOs) = Clocks.run(v2)
    val v4: T > (IOs with Fibers with Timers with ZIOs)                = Resources.run(v3)
    val v5: T > (IOs with Fibers with ZIOs)                            = Timers.run(v4)

    val v6: T > (IOs with ZIOs) = inject[T, Fiber, Task, Fibers, ZIOs, IOs](Fibers, ZIOs)(v5)
    val v7: T > ZIOs            = IOs.runLazy(v6)
    ZIOs.run(v7)
  }
}
