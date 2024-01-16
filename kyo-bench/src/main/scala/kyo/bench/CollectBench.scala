package kyo.bench

class CollectBench extends Bench.SyncAndFork[Seq[Int]] {

  val count = 1000

  val kyoTasks  = List.fill(count)(kyo.IOs(1))
  val catsTasks = List.fill(count)(cats.effect.IO(1))
  val zioTasks  = List.fill(count)(zio.ZIO.succeed(1))

  def kyoBench() = {
    import kyo._

    import kyo.seqs._

    Seqs.collect(kyoTasks)
  }

  def catsBench() = {
    import cats.effect._
    import cats.implicits._

    catsTasks.sequence
  }

  def zioBench() = {
    import zio._

    ZIO.collectAll(zioTasks)
  }
}
