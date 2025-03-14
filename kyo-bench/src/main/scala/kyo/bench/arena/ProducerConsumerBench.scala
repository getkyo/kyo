package kyo.bench.arena

class ProducerConsumerBench extends ArenaBench.ForkOnly(()):

    val depth = 10000

    def catsBench() =
        import cats.effect.*
        import cats.effect.std.*
        import cats.effect.kernel.*

        def repeat[A](n: Int)(io: IO[A]): IO[A] =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        Queue.bounded[IO, Unit](depth / 2).flatMap { q =>
            for
                producer <- repeat(depth)(q.offer(())).start
                consumer <- repeat(depth)(q.take).start
                _        <- producer.join
                _        <- consumer.join
            yield {}
        }
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        import kyo.Access

        def repeat[A](n: Int)(io: A < (Async & Abort[Closed])): A < (Async & Abort[Closed]) =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        Channel.init[Unit](depth / 2, Access.SingleProducerSingleConsumer).flatMap { q =>
            for
                producer <- Async.run(repeat(depth)(q.put(())))
                consumer <- Async.run(repeat(depth)(q.take))
                _        <- producer.get
                _        <- consumer.get
            yield {}
        }
    end kyoBenchFiber

    def zioBench() =
        import zio.*

        def repeat[R, E, A](n: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
            if n <= 1 then zio
            else zio *> repeat(n - 1)(zio)

        Queue.bounded[Unit](depth / 2).flatMap { q =>
            for
                producer <- repeat(depth)(q.offer(())).fork
                consumer <- repeat(depth)(q.take).fork
                _        <- producer.await
                _        <- consumer.await
            yield {}
        }
    end zioBench

end ProducerConsumerBench
