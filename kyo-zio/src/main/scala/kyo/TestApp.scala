package kyo

import zio.ZIO
import zios._
import ios._
import concurrent.fibers._
import kyo.consoles.Consoles
import kyo.resources.Resources
import kyo.randoms.Randoms
import kyo.core.>
import kyo.clocks.Clocks
import kyo.concurrent.timers.Timers
import kyo.envs.Envs

object TestApp extends KyoZioApp {
  def run(args: List[String]): Unit > (Fibers | IOs | Consoles | ZIOs) =
    for {
      f1 <- Fibers.forkFiber(2)
      f2 <- ZIOs(ZIO.succeed(3).fork)
      v1 <- f1.join
      v2 <- ZIOs(f2.join)
      _  <- Consoles.println(s"Kyo: v1 = $v1, v2 = $v2")
      _  <- ZIOs(zio.Console.printLine(s"ZIO: v1 = $v1, v2 = $v2"))
    } yield ()
}
