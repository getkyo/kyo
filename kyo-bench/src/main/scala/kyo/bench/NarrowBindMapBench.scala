package kyo.bench

class NarrowBindMapBench extends Bench[Int] {

  val depth = 10000

  def kyoBench() = {
    import kyo.core._
    import kyo.ios._
    import kyo.lists._

    def loop(i: Int): Int > IOs =
      if (i < depth)
        IOs(i + 11)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(loop)
      else IOs(i)

    IOs(0)(loop)
  }

  def catsBench() = {
    import cats.effect._

    def loop(i: Int): IO[Int] =
      if (i < depth)
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
      else IO(i)

    IO(0).flatMap(loop)
  }

  def zioBench() = {
    import zio._

    def loop(i: Int): UIO[Int] =
      if (i < depth)
        ZIO.succeed[Int](i + 11)
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
      else ZIO.succeed(i)

    ZIO.succeed(0).flatMap[Any, Nothing, Int](loop)
  }
}
