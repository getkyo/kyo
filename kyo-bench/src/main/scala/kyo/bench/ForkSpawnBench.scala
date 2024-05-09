package kyo.bench

import org.openjdk.jmh.annotations.*

class ForkSpawnBench extends Bench.ForkOnly[Unit]:

    val depth          = 5
    val width          = 10
    val total          = ((Math.pow(width, depth) - 1) / (width - 1)).toInt
    val expectedResult = ()

    def catsBench() =
        import cats.effect.IO
        import cats.effect.std.CountDownLatch

        def repeat[A](n: Int)(io: IO[A]): IO[A] =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        def loop(cdl: CountDownLatch[IO], level: Int): IO[Unit] =
            if level == depth then
                cdl.release
            else
                repeat(width)(loop(cdl, level + 1).start.map(_ => ()))

        for
            cdl <- CountDownLatch[IO](total)
            _   <- loop(cdl, 0)
            _   <- cdl.await
        yield {}
        end for
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        def repeat[A](n: Int)(io: A < IOs): A < IOs =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        def loop(cdl: Latch, level: Int): Unit < Fibers =
            if level == depth then
                cdl.release
            else
                repeat(width)(Fibers.init(loop(cdl, level + 1)).map(_ => ()))

        for
            cdl <- Latches.init(total)
            _   <- loop(cdl, 0)
            _   <- cdl.await
        yield {}
        end for
    end kyoBenchFiber

    def zioBench() =
        import zio.*
        import zio.concurrent.*

        def repeat[A](n: Int)(io: Task[A]): Task[A] =
            if n <= 1 then io
            else io.flatMap(_ => repeat(n - 1)(io))

        def loop(cdl: CountdownLatch, level: Int): Task[Unit] =
            if level == depth then
                cdl.countDown
            else
                repeat(width)(loop(cdl, level + 1).forkDaemon.map(_ => ()))

        for
            cdl <- CountdownLatch.make(total)
            _   <- loop(cdl, 0).orDie
            _   <- cdl.await
        yield {}
        end for
    end zioBench

    @Benchmark
    def forkOx() =
        import ox.*
        import java.util.concurrent.*
        scoped {
            val cdl = new CountDownLatch(total)
            def loop(level: Int): Unit =
                if level == depth then
                    cdl.countDown()
                else
                    for _ <- 0 until width do
                        fork {
                            loop(level + 1)
                        }
            loop(0)
            cdl.await()
        }
    end forkOx
end ForkSpawnBench
