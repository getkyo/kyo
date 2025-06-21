package kyo.bench.arena

class ForkManyBench extends ArenaBench.ForkOnly(0):

    val depth = 10000

    def catsBench() =
        import cats.effect.IO

        def repeat[A](n: Int)(io: IO[A]): IO[A] =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        for
            deferred <- IO.deferred[Unit]
            ref      <- IO.ref(depth)
            effect = ref
                .modify(n => (n - 1, if n == 1 then deferred.complete(()) else IO.unit))
                .flatten
            _ <- repeat(depth)(effect.start)
            _ <- deferred.get
        yield 0
        end for
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        def repeat[A](n: Int)(io: A < Sync): A < Sync =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        for
            promise <- Promise.init[Nothing, Unit]
            ref     <- AtomicInt.init(depth)
            effect = ref.decrementAndGet.flatMap {
                case 1 =>
                    promise.complete(Result.unit)
                case _ =>
                    false
            }
            _ <- repeat(depth)(Async.run(effect))
            _ <- promise.get
        yield 0
        end for
    end kyoBenchFiber

    def zioBench() =
        import zio.{Promise, Ref, ZIO}

        def repeat[R, E, A](n: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
            if n <= 1 then zio
            else zio *> repeat(n - 1)(zio)

        for
            promise <- Promise.make[Nothing, Unit]
            ref     <- Ref.make(depth)
            effect = ref
                .modify(n => (if n == 1 then promise.succeed(()) else ZIO.unit, n - 1))
                .flatten
            _ <- repeat(depth)(effect.forkDaemon)
            _ <- promise.await
        yield 0
        end for
    end zioBench

end ForkManyBench
