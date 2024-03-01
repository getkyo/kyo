package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

class ProducerConsumerBench extends Bench.ForkOnly[Unit]:

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

        def repeat[A](n: Int)(io: A < Fibers): A < Fibers =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        Channels.init[Unit](depth / 2, Access.Spsc).flatMap { q =>
            for
                producer <- Fibers.init(repeat(depth)(q.put(())))
                consumer <- Fibers.init(repeat(depth)(q.take))
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

    @Benchmark
    def forkOx() =
        import ox.*
        import ox.channels.*

        val q = Channel.buffered[Unit](depth / 2)
        scoped {
            val f1 =
                fork {
                    for _ <- 0 until depth do q.send(())
                }
            val f2 =
                fork {
                    for _ <- 0 until depth do q.take(1).drain()
                }
            f1.join()
            f2.join()
        }
    end forkOx
end ProducerConsumerBench
