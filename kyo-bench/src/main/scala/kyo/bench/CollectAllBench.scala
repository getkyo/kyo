package kyo.bench

import org.openjdk.jmh.annotations._
import cats.effect.IO
import kyo.core.>
import kyo.ios.IOs
import kyo.concurrent.fibers._

class CollectAllBench extends Bench[Long] {

  val count = 1000

  def kyoBench() = {
    import kyo.core._
    import kyo.ios._

    val tasks = (0 until count).map(_ => IOs(1)).toList
    collect(tasks)(_.sum.toLong)
  }

  override def kyoBenchFiber() = {
    import kyo.core._
    import kyo.ios._

    val tasks = (0 until count).map(_ => IOs(1)).toList
    Fibers.collect(tasks)(_.sum.toLong)
  }

  def catsBench() = {
    import cats.implicits._

    val tasks = (0 until count).map(_ => IO(1)).toList
    tasks.sequence.map(_.sum.toLong)
  }

  def zioBench() = {
    import zio._
    val tasks = (0 until count).map(_ => ZIO.succeed(1)).toList
    ZIO.collectAll(tasks).map(_.sum.toLong)
  }
}
