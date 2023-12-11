package kyo.bench

class NarrowBindBench extends Bench.SyncAndFork[Int] {

  val depth = 10000

  def kyoBench() = {
    import kyo._
    import kyo.ios._

    def loop(i: Int): Int < IOs =
      if (i < depth) IOs(i + 1).flatMap(loop)
      else IOs(i)

    IOs(0).flatMap(loop)
  }

  def catsBench() = {
    import cats.effect._

    def loop(i: Int): IO[Int] =
      if (i < depth) IO(i + 1).flatMap(loop)
      else IO(i)

    IO(0).flatMap(loop)
  }

  def zioBench() = {
    import zio._

    def loop(i: Int): UIO[Int] =
      if (i < depth) ZIO.succeed[Int](i + 1).flatMap(loop)
      else ZIO.succeed(i)

    ZIO.succeed(0).flatMap[Any, Nothing, Int](loop)
  }
}
