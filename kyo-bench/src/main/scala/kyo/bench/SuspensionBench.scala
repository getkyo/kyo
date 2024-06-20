package kyo.bench

class SuspensionBench extends Bench.SyncAndFork(()):

    def catsBench() =
        import cats.effect.*

        IO(())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
    end catsBench

    def kyoBench() =
        import kyo.*

        IOs(())
            .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
            .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
            .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
            .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
            .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
    end kyoBench

    override def kyoBench2() =
        import kyo2.*

        IO(())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
            .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
    end kyoBench2

    def zioBench() =
        import zio.*
        ZIO.succeed(())
            .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
            .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
            .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
            .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
            .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
    end zioBench
end SuspensionBench
