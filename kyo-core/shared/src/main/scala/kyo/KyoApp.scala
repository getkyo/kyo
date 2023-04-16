package kyo

import core._
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

trait KyoApp {

  final def main(args: Array[String]): Unit =
    IOs.run(KyoApp.runFiber(Duration.Inf)(run(args.toList)).block)

  def run(args: List[String])
      : Unit > (IOs | Fibers | Resources | Clocks | Consoles | Randoms | Timers | Aspects)

}

object KyoApp {
  def runFiber[T](timeout: Duration)(v: T > (IOs | Fibers | Resources | Clocks | Consoles |
    Randoms |
    Timers | Aspects)): Fiber[T] = {
    val v1: T > (IOs | Fibers | Resources | Clocks | Consoles | Timers | Aspects) = Randoms.run(v)
    val v2: T > (IOs | Fibers | Resources | Clocks | Timers | Aspects)            = Consoles.run(v1)
    val v3: T > (IOs | Fibers | Resources | Timers | Aspects)                     = Clocks.run(v2)
    val v4: T > (IOs | Fibers | Timers | Aspects) = Resources.run(v3)
    val v5: T > (IOs | Fibers | Timers)           = Aspects.run(v4)
    val v6: T > (IOs | Fibers)                    = Timers.run(v5)
    // val v7: T > (IOs | Fibers | Timers)           = Fibers.timeout(timeout)(v6)
    val v8: T > (IOs | Fibers) = Timers.run(v6)
    val v9: Fiber[T] > Nothing = IOs.lazyRun(v8) << Fibers
    v9
  }
}
