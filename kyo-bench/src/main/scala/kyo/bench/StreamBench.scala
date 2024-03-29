package kyo.bench

class StreamBench extends Bench.SyncAndFork[Int]:

    val seq = (0 until 10000).toList

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
        val a = Streams[Int].emit(seq)
        val b = Streams[Int].filter(a)(_ % 2 == 0)
        val c = Streams[Int].transform(b)(_ + 1)
        val d = Streams[Int].runFold(c)(0)(_ + _)
        d.pure._1
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
