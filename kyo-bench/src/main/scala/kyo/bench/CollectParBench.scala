package kyo.bench

import kyo.concurrent.fibers.Fibers

class CollectParBench extends Bench.ForkOnly[Seq[Int]] {

  val count = 1000

  val kyoTasks  = List.fill(count)(kyo.ios.IOs(1))
  val catsTasks = List.fill(count)(cats.effect.IO(1))
  val zioTasks  = List.fill(count)(zio.ZIO.succeed(1))

  override def kyoBenchFiber() = {
    import kyo._
    import kyo.ios._
    import kyo.seqs._

    Fibers.parallel(kyoTasks)
  }

  def catsBench() = {
    import cats.effect._
    import cats.implicits._

    catsTasks.parSequence
  }

  def zioBench() = {
    import zio._

    ZIO.collectAllPar(zioTasks)
  }
}
