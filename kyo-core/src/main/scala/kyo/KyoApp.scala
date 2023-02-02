package kyo

import core._
import ios._
import clocks._
import consoles._
import scopes._
import tries._
import aborts._
import concurrent.fibers._
import concurrent.timers._
import kyo.randoms.Randoms

trait KyoApp {

  final def main(args: Array[String]): Unit =
    val v0: Unit > (IOs | Fibers | Scopes | Clocks | Consoles | Randoms | Timers) = run(args.toList)
    val v1: Unit > (IOs | Fibers | Scopes | Clocks | Consoles | Randoms)          = Timers.run(v0)
    val v2: Unit > (IOs | Fibers | Scopes | Clocks | Consoles)                    = Randoms.run(v1)
    val v3: Unit > (IOs | Fibers | Scopes | Clocks)                               = Consoles.run(v2)
    val v4: Unit > (IOs | Fibers | Scopes)                                        = Clocks.run(v3)
    val v5: Unit > (IOs | Fibers)                                                 = Scopes.close(v4)
    val v6: Unit > Fibers                                                         = IOs.lazyRun(v5)

    IOs.run((v6 << Fibers)(_.block))

  def run(args: List[String]): Unit > (IOs | Fibers | Scopes | Clocks | Consoles | Randoms | Timers)

}
