package kyo.bench.arena

class ForkChainedBench extends ArenaBench.ForkOnly(0):

    val depth = 10000

    def catsBench() =
        import cats.effect.*

        def iterate(deferred: Deferred[IO, Unit], n: Int): IO[Any] =
            if n <= 0 then deferred.complete(())
            else IO.unit.flatMap(_ => iterate(deferred, n - 1).start)

        for
            deferred <- IO.deferred[Unit]
            _        <- iterate(deferred, depth).start
            _        <- deferred.get
        yield 0
        end for
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        def iterate(p: Promise[Nothing, Unit], n: Int): Unit < Sync =
            if n <= 0 then p.complete(Result.unit).unit
            else Kyo.unit.flatMap(_ => Async.run(iterate(p, n - 1)).unit)

        for
            p <- Promise.init[Nothing, Unit]
            _ <- Async.run(iterate(p, depth))
            _ <- p.get
        yield 0
        end for
    end kyoBenchFiber

    def zioBench() =
        import zio.{Promise, ZIO}

        def iterate(promise: Promise[Nothing, Unit], n: Int): ZIO[Any, Nothing, Any] =
            if n <= 0 then promise.succeed(())
            else ZIO.unit.flatMap(_ => iterate(promise, n - 1).forkDaemon)

        for
            promise <- Promise.make[Nothing, Unit]
            _       <- iterate(promise, depth).forkDaemon
            _       <- promise.await
        yield 0
        end for
    end zioBench

end ForkChainedBench
