package kyo.bench

class StreamBufferBench extends Bench.ForkOnly[Int]:

    val seq = (0 until 10000).toList

    def catsBench() =
        import cats.effect.*
        import fs2.*
        Stream.emits(seq)
            .filter(_ % 2 == 0)
            .buffer(10)
            .map(_ + 1)
            .covary[IO]
            .compile
            .fold(0)(_ + _)
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*
        Streams.initSeq(seq)
            .filter(_ % 2 == 0)
            .buffer(10)
            .transform(_ + 1)
            .runFold(0)(_ + _)
            .map(_._1)
    end kyoBenchFiber

    def zioBench() =
        import zio.*
        import zio.stream.*
        ZStream.fromIterable(seq)
            .filter(_ % 2 == 0)
            .buffer(10)
            .map(_ + 1)
            .runSum
    end zioBench

end StreamBufferBench
