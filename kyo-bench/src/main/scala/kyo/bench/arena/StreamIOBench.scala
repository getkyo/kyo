package kyo.bench.arena

class StreamIOBench extends ArenaBench.SyncAndFork(25000000):
    val seq = (0 until 10000).toVector

    def catsBench() =
        import cats.effect.*
        import fs2.*
        Stream.emits(seq)
            .evalFilter(v => IO(v % 2 == 0))
            .evalMap(v => IO(v + 1))
            .compile
            .fold(0)(_ + _)
    end catsBench

    def kyoBench() =
        import kyo.*
        Stream.init(seq)
            .filter(v => Sync(v % 2 == 0))
            .map(v => Sync(v + 1))
            .fold(0)(_ + _)
    end kyoBench

    def zioBench() =
        import zio.*
        import zio.stream.*
        ZStream.fromIterable(seq)
            .filterZIO(v => ZIO.succeed(v % 2 == 0))
            .mapZIO(v => ZIO.succeed(v + 1))
            .runSum
    end zioBench
end StreamIOBench
