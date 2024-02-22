package kyo.bench

class PingPongBench extends Bench.ForkOnly[Unit]:

    val depth = 1000

    def catsBench() =
        import cats.effect.*
        import cats.effect.std.*

        def repeat[A](n: Int)(io: IO[A]): IO[A] =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        def iterate(deferred: Deferred[IO, Unit], n: Int): IO[Any] =
            for
                ref   <- IO.ref(n)
                queue <- Queue.bounded[IO, Unit](1)
                effect = queue.offer(()).start >>
                    queue.take >>
                    ref
                        .modify(n =>
                            (n - 1, if n == 1 then deferred.complete(()) else IO.unit)
                        )
                        .flatten
                _ <- repeat(depth)(effect.start)
            yield ()

        for
            deferred <- IO.deferred[Unit]
            _        <- iterate(deferred, depth).start
            _        <- deferred.get
        yield ()
        end for
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        def repeat[A](n: Int)(io: A < Fibers): A < Fibers =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        def iterate(promise: Promise[Unit], n: Int): Unit < Fibers =
            for
                ref  <- Atomics.initInt(n)
                chan <- Channels.init[Unit](1)
                effect =
                    for
                        _ <- Fibers.init(chan.put(()))
                        _ <- chan.take
                        n <- ref.decrementAndGet
                        _ <- if n == 0 then promise.complete(()).unit else IOs.unit
                    yield ()
                _ <- repeat(depth)(Fibers.init(effect))
            yield ()

        for
            promise <- Fibers.initPromise[Unit]
            _       <- Fibers.init(iterate(promise, depth))
            _       <- promise.get
        yield ()
        end for
    end kyoBenchFiber

    def zioBench() =
        import zio.*

        def repeat[R, E, A](n: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
            if n <= 1 then zio
            else zio *> repeat(n - 1)(zio)

        def iterate(promise: Promise[Nothing, Unit], n: Int): ZIO[Any, Nothing, Any] =
            for
                ref   <- Ref.make(n)
                queue <- Queue.bounded[Unit](1)
                effect = queue.offer(()).forkDaemon *>
                    queue.take *>
                    ref
                        .modify(n =>
                            (if n == 1 then promise.succeed(()) else ZIO.unit, n - 1)
                        )
                        .flatten
                _ <- repeat(depth)(effect.forkDaemon)
            yield ()

        for
            promise <- Promise.make[Nothing, Unit]
            _       <- iterate(promise, depth).forkDaemon
            _       <- promise.await
        yield ()
        end for
    end zioBench
end PingPongBench
