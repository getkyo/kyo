package kyo.bench

import org.openjdk.jmh.annotations.*

class BroadFlatMapBench extends Bench.SyncAndFork(BigInt(610)):

    val depth = 15

    def catsBench() =
        import cats.effect.*

        def catsFib(n: Int): IO[BigInt] =
            if n <= 1 then IO.pure(BigInt(n))
            else
                catsFib(n - 1).flatMap(a => catsFib(n - 2).flatMap(b => IO.pure(a + b)))

        catsFib(depth)
    end catsBench

    def kyoBench() =
        import kyo.*

        def kyoFib(n: Int): BigInt < IOs =
            if n <= 1 then BigInt(n)
            else kyoFib(n - 1).flatMap(a => kyoFib(n - 2).flatMap(b => a + b))

        kyoFib(depth)
    end kyoBench

    @Benchmark
    override def kyoBench2() =
        import kyo2.*

        def kyoFib(n: Int): BigInt < Any =
            if n <= 1 then BigInt(n)
            else kyoFib(n - 1).map(a => kyoFib(n - 2).map(b => a + b))

        kyoFib(depth)
    end kyoBench2

    def zioBench() =
        import zio.*
        def zioFib(n: Int): UIO[BigInt] =
            if n <= 1 then
                ZIO.succeed(BigInt(n))
            else
                zioFib(n - 1).flatMap(a => zioFib(n - 2).flatMap(b => ZIO.succeed(a + b)))
        zioFib(depth)
    end zioBench

    def oxFib(n: Int): BigInt =
        if n <= 1 then n
        else oxFib(n - 1) + oxFib(n - 2)

    @Benchmark
    def syncOx(): BigInt =
        oxFib(depth)

    @Benchmark
    def forkOx(): BigInt =
        import ox.*
        scoped {
            fork(oxFib(depth)).join()
        }
    end forkOx
end BroadFlatMapBench
