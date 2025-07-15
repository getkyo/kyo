package kyo.bench.arena

class StreamAsyncBench extends ArenaBench.ForkOnly(()):
    val seq = (0 until 10000).toVector

    def catsBench() =
        import cats.effect.*
        import fs2.*
        Stream.emits(seq)
            .parEvalMap(6)(v => IO(v + 1))
            .compile
            .drain
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*
        Stream.init(seq)
            .mapPar(6)(v => Sync.defer(v + 1))
            .discard
    end kyoBenchFiber

    def zioBench() =
        import zio.*
        import zio.stream.*
        ZStream.fromIterable(seq)
            .mapZIOPar(6)(v => ZIO.succeed(v + 1))
            .runDrain
            .unit
    end zioBench
end StreamAsyncBench
