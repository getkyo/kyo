package kyo

import ios._
import clocks._
import consoles._
import resources._
import tries._
import aborts._
import aspects._
import randoms._
import concurrent.fibers._
import concurrent.timers._
import scala.concurrent.duration.Duration
import kyo.App.Effects
import scala.util.Try

abstract class App {

  final def main(args: Array[String]): Unit =
    IOs.run(App.runFiber(run(args.toList)).map(_.block))

  def run(
      args: List[String]
  ): Unit > Effects

}

object App {

  type Effects =
    IOs with Fibers with Resources with Clocks with Consoles with Randoms with Timers with Aspects
      with Tries

  def run[T](timeout: Duration)(v: T > Effects): T =
    IOs.run(runFiber(timeout)(v).map(_.block).map(_.get))

  def run[T](v: T > Effects): T =
    run(Duration.Inf)(v)

  def runFiber[T](v: T > Effects): Fiber[Try[T]] > IOs =
    runFiber(Duration.Inf)(v)

  def runFiber[T](timeout: Duration)(v: T > Effects): Fiber[Try[T]] > IOs = {
    val v0: Try[
        T
    ] > (IOs with Fibers with Resources with Clocks with Consoles with Timers with Aspects with Randoms) =
      Tries.run(v)
    val v1: Try[
        T
    ] > (IOs with Fibers with Resources with Clocks with Consoles with Timers with Aspects) =
      Randoms.run(v0)
    val v2: Try[T] > (IOs with Fibers with Resources with Clocks with Timers with Aspects) =
      Consoles.run(v1)
    val v3: Try[T] > (IOs with Fibers with Resources with Timers with Aspects) = Clocks.run(v2)
    val v4: Try[T] > (IOs with Fibers with Timers with Aspects)                = Resources.run(v3)
    val v5: Try[T] > (IOs with Fibers with Timers)                             = Aspects.run(v4)
    val v6: Try[T] > (IOs with Fibers)                                         = Timers.run(v5)
    val v7: Try[T] > (IOs with Fibers with Timers) = Fibers.timeout(timeout)(v6)
    val v8: Try[T] > (IOs with Fibers)             = Timers.run(v6)
    IOs(Fibers.run(IOs.runLazy(v8)))
  }
}
