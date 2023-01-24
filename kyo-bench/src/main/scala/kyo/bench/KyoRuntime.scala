package kyo.bench

import kyo.core._
import kyo.ios._
import kyo.concurrent.fibers._
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

object KyoRuntime {

  def run[T](v: T > IOs): T =
    IOs.run(v)

  def runFork[T](v: T > (IOs | Fibers)): T =
    run(Fibers.block(Fibers.fork[T > (IOs | Fibers)](v)()))
}
