package kyo.bench

class DeepBindBench extends Bench.SyncAndFork[Unit] {

  val depth = 10000

  def kyoBench() = {
    import kyo._
    import kyo.ios._
    def loop(i: Int): Unit < IOs =
      IOs {
        if (i > depth)
          ()
        else
          loop(i + 1)
      }
    loop(0)
  }

  def catsBench() = {
    import cats.effect._
    def loop(i: Int): IO[Unit] =
      IO.unit.flatMap { _ =>
        if (i > depth)
          IO.unit
        else
          loop(i + 1)
      }
    loop(0)
  }

  def zioBench() = {
    import zio._
    def loop(i: Int): UIO[Unit] =
      ZIO.unit.flatMap { _ =>
        if (i > depth)
          ZIO.unit
        else
          loop(i + 1)
      }
    loop(0)
  }
}
