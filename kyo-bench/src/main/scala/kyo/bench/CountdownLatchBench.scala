package kyo.bench

class CountdownLatchBench extends Bench.ForkOnly[Int] {

  val depth = 10000

  def catsBench() = {
    import cats.effect._
    import cats.effect.std._

    def iterate(l: CountDownLatch[IO], n: Int): IO[Any] =
      if (n <= 0) IO.unit
      else l.release.flatMap(_ => iterate(l, n - 1))

    for {
      l <- CountDownLatch[IO](depth)
      _ <- iterate(l, depth).start
      _ <- l.await
    } yield 0
  }

  override def kyoBenchFiber() = {
    import kyo._
    import kyo.ios._
    import kyo.concurrent.fibers._
    import kyo.concurrent.latches._

    def iterate(l: Latch, n: Int): Unit > IOs =
      if (n <= 0) IOs.unit
      else l.release.flatMap(_ => iterate(l, n - 1))

    for {
      l <- Latches.init(depth)
      _ <- Fibers.init(iterate(l, depth))
      _ <- l.await
    } yield 0
  }

  def zioBench() = {
    import zio._
    import zio.concurrent._

    def iterate(l: CountdownLatch, n: Int): ZIO[Any, Nothing, Any] =
      if (n <= 0) ZIO.unit
      else l.countDown.flatMap(_ => iterate(l, n - 1))

    for {
      l <- CountdownLatch.make(depth)
      _ <- iterate(l, depth).forkDaemon
      _ <- l.await
    } yield 0
  }
}
