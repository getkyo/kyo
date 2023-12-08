package kyo.bench

class CollectAllBench extends Bench.SyncAndFork[Long] {

  val count = 1000

  def kyoBench() = {
    import kyo._
    import kyo.ios._
    import kyo.seqs._

    val tasks = (0 until count).map(_ => IOs(1)).toList
    Seqs.collect(tasks).map(_.sum.toLong)
  }

  def catsBench() = {
    import cats.effect._
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
