package kyo.bench.arena

class NarrowBindBench extends ArenaBench.SyncAndFork(10000):

    val depth = 10000

    def kyoBench() =
        import kyo.*

        def loop(i: Int): Int < Sync =
            if i < depth then Sync(i + 1).flatMap(loop)
            else Sync(i)

        Sync(0).flatMap(loop)
    end kyoBench

    def catsBench() =
        import cats.effect.*

        def loop(i: Int): IO[Int] =
            if i < depth then IO(i + 1).flatMap(loop)
            else IO(i)

        IO(0).flatMap(loop)
    end catsBench

    def zioBench() =
        import zio.*

        def loop(i: Int): UIO[Int] =
            if i < depth then ZIO.succeed[Int](i + 1).flatMap(loop)
            else ZIO.succeed(i)

        ZIO.succeed(0).flatMap[Any, Nothing, Int](loop)
    end zioBench
end NarrowBindBench
