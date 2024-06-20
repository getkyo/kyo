package kyo.bench

class EnqueueDequeueBench extends Bench.ForkOnly(()):

    val depth = 10000

    def catsBench() =
        import cats.effect.*
        import cats.effect.std.*

        def loop(q: Queue[IO, Unit], i: Int): IO[Unit] =
            if i >= depth then
                IO.unit
            else
                q.offer(()).flatMap(_ => q.take.flatMap(_ => loop(q, i + 1)))

        Queue.bounded[IO, Unit](1).flatMap(loop(_, 0))
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        import kyo.Access

        def loop(c: Channel[Unit], i: Int): Unit < Fibers =
            if i >= depth then
                IOs.unit
            else
                c.put(()).flatMap(_ => c.take.flatMap(_ => loop(c, i + 1)))

        Channels.init[Unit](1, Access.Spsc).flatMap(loop(_, 0))
    end kyoBenchFiber

    override def kyoBenchFiber2() =
        import kyo2.*

        def loop(c: Channel[Unit], i: Int): Unit < (Abort[Closed] & Async & IO) =
            if i >= depth then
                IO.unit
            else
                c.put(()).flatMap(_ => c.take.flatMap(_ => loop(c, i + 1)))

        Channel.init[Unit](1, Access.Spsc).flatMap(loop(_, 0))
    end kyoBenchFiber2

    def zioBench() =
        import zio.*

        def loop(q: Queue[Unit], i: Int): ZIO[Any, Nothing, Unit] =
            if i >= depth then
                ZIO.unit
            else
                q.offer(()).flatMap(_ => q.take.flatMap(_ => loop(q, i + 1)))

        Queue.bounded[Unit](1).flatMap(loop(_, 0))
    end zioBench
end EnqueueDequeueBench
