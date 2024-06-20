package kyo.bench

class CountdownLatchBench extends Bench.ForkOnly(0):

    val depth = 10000

    def catsBench() =
        import cats.effect.*
        import cats.effect.std.*

        def iterate(l: CountDownLatch[IO], n: Int): IO[Any] =
            if n <= 0 then IO.unit
            else l.release.flatMap(_ => iterate(l, n - 1))

        for
            l <- CountDownLatch[IO](depth)
            _ <- iterate(l, depth).start
            _ <- l.await
        yield 0
        end for
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        def iterate(l: Latch, n: Int): Unit < IOs =
            if n <= 0 then IOs.unit
            else l.release.flatMap(_ => iterate(l, n - 1))

        for
            l <- Latches.init(depth)
            _ <- Fibers.init(iterate(l, depth))
            _ <- l.await
        yield 0
        end for
    end kyoBenchFiber

    override def kyoBenchFiber2() =
        import kyo2.*

        def iterate(l: Latch, n: Int): Unit < IO =
            if n <= 0 then IO.unit
            else l.release.flatMap(_ => iterate(l, n - 1))

        for
            l <- Latch.init(depth)
            _ <- Async.run(iterate(l, depth))
            _ <- l.await
        yield 0
        end for
    end kyoBenchFiber2

    def zioBench() =
        import zio.*
        import zio.concurrent.*

        def iterate(l: CountdownLatch, n: Int): ZIO[Any, Nothing, Any] =
            if n <= 0 then ZIO.unit
            else l.countDown.flatMap(_ => iterate(l, n - 1))

        for
            l <- CountdownLatch.make(depth)
            _ <- iterate(l, depth).forkDaemon
            _ <- l.await
        yield 0
        end for
    end zioBench
end CountdownLatchBench
