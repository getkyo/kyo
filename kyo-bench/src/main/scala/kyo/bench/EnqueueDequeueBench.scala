package kyo.bench

class EnqueueDequeueBench extends Bench.ForkOnly[Unit] {

  val depth = 10000

  def catsBench() = {
    import cats.effect._
    import cats.effect.std._

    def loop(q: Queue[IO, Unit], i: Int): IO[Unit] =
      if (i >= depth)
        IO.unit
      else
        q.offer(()).flatMap(_ => q.take.flatMap(_ => loop(q, i + 1)))

    Queue.bounded[IO, Unit](1).flatMap(loop(_, 0))
  }

  override def kyoBenchFiber() = {
    import kyo._

    import kyo.Access

    def loop(c: Channel[Unit], i: Int): Unit < Fibers =
      if (i >= depth)
        IOs.unit
      else
        c.put(()).flatMap(_ => c.take.flatMap(_ => loop(c, i + 1)))

    Channels.init[Unit](1, Access.Spsc).flatMap(loop(_, 0))
  }

  def zioBench() = {
    import zio._

    def loop(q: Queue[Unit], i: Int): ZIO[Any, Nothing, Unit] =
      if (i >= depth)
        ZIO.unit
      else
        q.offer(()).flatMap(_ => q.take.flatMap(_ => loop(q, i + 1)))

    Queue.bounded[Unit](1).flatMap(loop(_, 0))
  }
}
