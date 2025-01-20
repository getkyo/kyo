package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

class SemaphoreBench extends Bench.ForkOnly(()):

    val depth = 10000

    def catsBench() =
        import cats.effect.*
        import cats.effect.std.*

        def loop(s: Semaphore[IO], i: Int): IO[Unit] =
            if i >= depth then
                IO.unit
            else
                s.acquire.flatMap(_ => s.release).flatMap(_ => loop(s, i + 1))

        Semaphore[IO](1).flatMap(loop(_, 0))
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        def loop(s: Meter, i: Int): Unit < (Async & Abort[Closed]) =
            if i >= depth then
                IO.unit
            else
                s.run(()).flatMap(_ => loop(s, i + 1))

        Meter.initSemaphore(1).flatMap(loop(_, 0))
    end kyoBenchFiber

    def zioBench() =
        import zio.*

        def loop(s: Semaphore, i: Int): ZIO[Any, Nothing, Unit] =
            if i >= depth then
                ZIO.unit
            else
                s.withPermit(ZIO.succeed(())).flatMap(_ => loop(s, i + 1))

        Semaphore.make(1).flatMap(loop(_, 0))
    end zioBench

end SemaphoreBench
