package kyo.bench

class StreamBench extends Bench.SyncAndFork(25000000):

    val seq = (0 until 10000).toVector

    def catsBench() =
        import cats.effect.*
        import fs2.*
        Stream.emits(seq)
            .filter(_ % 2 == 0)
            .map(_ + 1)
            .covary[IO]
            .compile
            .fold(0)(_ + _)
    end catsBench

    def kyoBench() =
        import kyo.*
        Stream.init(seq)
            .filter(_ % 2 == 0)
            .map(_ + 1)
            .runFold(0)(_ + _)
            .eval
    end kyoBench

    def zioBench() =
        import zio.*
        import zio.stream.*
        ZStream.fromIterable(seq)
            .filter(_ % 2 == 0)
            .map(_ + 1)
            .runSum
    end zioBench

end StreamBench
