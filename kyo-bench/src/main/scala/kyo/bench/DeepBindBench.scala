package kyo.bench

class DeepBindBench extends Bench.SyncAndFork(()):

    val depth = 10000

    def kyoBench() =
        import kyo.*

        def loop(i: Int): Unit < IO =
            IO {
                if i > depth then
                    ()
                else
                    loop(i + 1)
            }
        loop(0)
    end kyoBench

    def catsBench() =
        import cats.effect.*
        def loop(i: Int): IO[Unit] =
            IO.unit.flatMap { _ =>
                if i > depth then
                    IO.unit
                else
                    loop(i + 1)
            }
        loop(0)
    end catsBench

    def zioBench() =
        import zio.*
        def loop(i: Int): UIO[Unit] =
            ZIO.unit.flatMap { _ =>
                if i > depth then
                    ZIO.unit
                else
                    loop(i + 1)
            }
        loop(0)
    end zioBench
end DeepBindBench
