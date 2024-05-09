package kyo.bench

class StreamBench extends Bench.SyncAndFork[Int]:

    val seq            = (0 until 10000).toVector
    val expectedResult = 25000000

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
        Streams.initSeq(seq)
            .filter(_ % 2 == 0)
            .transform(_ + 1)
            .runFold(0)(_ + _)
            .pure._1
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
