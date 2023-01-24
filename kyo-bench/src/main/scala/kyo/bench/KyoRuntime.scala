package kyo.bench

import kyo.core._
import kyo.ios._
import kyo.concurrent.fibers._
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

object KyoRuntime {

  def runIO[T](v: T > IOs): T =
    IOs.run(v)

  def runFiber[T](v: T > IOs): T =
    IOs.run(Fibers.forkFiber(v)(_.block))
}
