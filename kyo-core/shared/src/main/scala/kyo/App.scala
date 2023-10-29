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
    IOs.run(App.runFiber(run(args.toList)).map(_.block).map(_.get))

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
    def v1 = Randoms.run(v)
    def v2 = Consoles.run(v1)
    def v3 = Clocks.run(v2)
    def v4 = Resources.run(v3)
    def v5 = Aspects.run(v4)
    def v6 = Timers.run(v5)
    def v7 = Tries.run(v6)
    def v8 = Fibers.timeout(timeout)(v7)
    def v9 = Timers.run(v8)
    IOs(Fibers.run(IOs.runLazy(v9)))
  }
}
