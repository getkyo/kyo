package kyo

import kyo.randoms.Randoms

import scala.concurrent.duration.Duration

import core._
import ios._
import clocks._
import consoles._
import resources._
import tries._
import aborts._
import concurrent.fibers._
import concurrent.timers._

trait KyoApp {

  final def main(args: Array[String]): Unit =
    KyoApp.run(Duration.Inf)(run(args.toList))

  def run(args: List[String])
      : Unit > (IOs | Fibers | Resources | Clocks | Consoles | Randoms | Timers)

}

object KyoApp {
  def run[T](timeout: Duration)(v: T > (IOs | Fibers | Resources | Clocks | Consoles | Randoms |
    Timers)): T = {
    val v1: T > (IOs | Fibers | Resources | Clocks | Consoles | Timers) = Randoms.run(v)
    val v2: T > (IOs | Fibers | Resources | Clocks | Timers)            = Consoles.run(v1)
    val v3: T > (IOs | Fibers | Resources | Timers)                     = Clocks.run(v2)
    val v4: T > (IOs | Fibers | Timers)                                 = Resources.run(v3)
    val v5: T > (IOs | Fibers)                                          = Timers.run(v4)
    val v6: T > (IOs | Fibers | Timers) = Fibers.timeout(timeout)(v5)
    val v7: T > (IOs | Fibers)          = Timers.run(v6)
    val v8: T > Fibers                  = IOs.lazyRun(v7)
    IOs.run((v8 << Fibers)(_.block))
  }
}
