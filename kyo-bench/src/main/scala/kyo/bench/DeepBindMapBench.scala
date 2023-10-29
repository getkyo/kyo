package kyo.bench

class DeepBindMapBench extends Bench.SyncAndFork[Int] {

  val depth = 10000

  def kyoBench() = {
    import kyo._
    import kyo.ios._
    def loop(i: Int): Int > IOs =
      IOs {
        if (i > depth) i
        else
          IOs(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
            .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
      }
    loop(0)
  }

  def catsBench() = {
    import cats.effect._
    def loop(i: Int): IO[Int] =
      IO.unit.flatMap { _ =>
        if (i > depth)
          IO.pure(i)
        else
          IO(i + 11)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .flatMap(loop)
      }
    loop(0)
  }

  def zioBench() = {
    import zio._
    def loop(i: Int): UIO[Int] =
      ZIO.unit.flatMap { _ =>
        if (i > depth)
          ZIO.succeed(i)
        else
          ZIO.unit
            .map(_ => (i + 11))
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .flatMap(loop)
      }
    loop(0)
  }
}
