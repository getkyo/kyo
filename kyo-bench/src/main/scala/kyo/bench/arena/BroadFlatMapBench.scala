package kyo.bench.arena

class BroadFlatMapBench extends ArenaBench.SyncAndFork(BigInt(610)):

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

        def kyoFib(n: Int): BigInt < Sync =
            if n <= 1 then BigInt(n)
            else kyoFib(n - 1).flatMap(a => kyoFib(n - 2).flatMap(b => a + b))

        kyoFib(depth)
    end kyoBench

    def zioBench() =
        import zio.*
        def zioFib(n: Int): UIO[BigInt] =
            if n <= 1 then
                ZIO.succeed(BigInt(n))
            else
                zioFib(n - 1).flatMap(a => zioFib(n - 2).flatMap(b => ZIO.succeed(a + b)))
        zioFib(depth)
    end zioBench

end BroadFlatMapBench
