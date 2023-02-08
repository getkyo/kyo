package kyo

import core._
import ios._
import clocks._
import consoles._
import resources._
import tries._
import aborts._
import concurrent.fibers._
import concurrent.timers._
import kyo.randoms.Randoms

trait KyoApp {

  final def main(args: Array[String]): Unit =
    val v0: Unit > (IOs | Fibers | Resources | Clocks | Consoles | Randoms | Timers) =
      run(args.toList)
    val v1: Unit > (IOs | Fibers | Resources | Clocks | Consoles | Randoms) = Timers.run(v0)
    val v2: Unit > (IOs | Fibers | Resources | Clocks | Consoles)           = Randoms.run(v1)
    val v3: Unit > (IOs | Fibers | Resources | Clocks)                      = Consoles.run(v2)
    val v4: Unit > (IOs | Fibers | Resources)                               = Clocks.run(v3)
    val v5: Unit > (IOs | Fibers)                                           = Resources.close(v4)
    val v6: Unit > Fibers                                                   = IOs.lazyRun(v5)

    IOs.run((v6 << Fibers)(_.block))

  def run(args: List[String])
      : Unit > (IOs | Fibers | Resources | Clocks | Consoles | Randoms | Timers)

}
