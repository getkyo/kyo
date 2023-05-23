package kyo

import kyo.randoms.Randoms
import kyo.zios.ZIOs
import zio.Task
import zio.ZIO

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import ios._
import clocks._
import consoles._
import resources._
import tries._
import aborts._
import concurrent.fibers._
import concurrent.timers._

trait KyoZioApp {

  final def main(args: Array[String]): Unit =
    KyoZioApp.run(Duration.Inf)(run(args.toList))

  def run(args: List[String])
      : Unit > (IOs & Fibers & Resources & Clocks & Consoles & Randoms & Timers & ZIOs)

}

object KyoZioApp {

  def block[T](timeout: Duration)(t: Task[T]): T =
    zio.Unsafe.unsafe(implicit u =>
      zio.Runtime.default.unsafe.run(
          t.timeoutFail(new TimeoutException)(zio.Duration.fromScala(timeout))
      ).getOrThrow()
    )

  def run[T](timeout: Duration)(v: T > (IOs & Fibers & Resources & Clocks & Consoles & Randoms &
    Timers & ZIOs)): T =
    block(timeout)(runTask(v))

  def runTask[T](v: T > (IOs & Fibers & Resources & Clocks & Consoles & Randoms & Timers & ZIOs))
      : Task[T] = {
    val v1: T > (IOs & Fibers & Resources & Clocks & Consoles & Timers & ZIOs) = Randoms.run(v)
    val v2: T > (IOs & Fibers & Resources & Clocks & Timers & ZIOs)            = Consoles.run(v1)
    val v3: T > (IOs & Fibers & Resources & Timers & ZIOs)                     = Clocks.run(v2)
    val v4: T > (IOs & Fibers & Timers & ZIOs)                                 = Resources.run(v3)
    val v5: T > (IOs & Fibers & ZIOs)                                          = Timers.run(v4)
    val v6: T > (IOs & ZIOs)             = v5 >> (Fibers -> ZIOs)
    val v7: T > ZIOs                     = IOs.lazyRun(v6)
    val v8: ZIO[Any, Throwable, T] > Any = v7 << ZIOs
    v8
  }
}
