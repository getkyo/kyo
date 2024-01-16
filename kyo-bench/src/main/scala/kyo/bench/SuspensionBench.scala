package kyo.bench

class SuspensionBench extends Bench.SyncAndFork[Unit] {

  def catsBench() = {
    import cats.effect._

    IO(())
      .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
      .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
      .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
      .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
      .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
  }

  def kyoBench() = {
    import kyo._

    IOs(())
      .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
      .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
      .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
      .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
      .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
  }

  def zioBench() = {
    import zio._
    ZIO.succeed(())
      .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
      .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
      .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
      .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
      .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
  }
}
