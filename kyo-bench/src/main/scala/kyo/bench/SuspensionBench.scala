package kyo.bench

import org.openjdk.jmh.annotations._
import cats.effect.IO
import kyo._
import kyo.ios._
import zio.{ZIO, UIO}
import java.util.concurrent.Executors
import kyo.concurrent.fibers._
import kyo.concurrent.channels._
import kyo.concurrent.Access

import kyo.bench.Bench
import java.util.concurrent.atomic.AtomicInteger

class SuspensionBench extends Bench[Unit] {

  def catsBench(): IO[Unit] = {
    IO(())
      .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
      .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
      .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
      .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
      .flatMap(_ => IO(())).map(_ => ()).flatMap(_ => IO(())).map(_ => ())
  }

  def kyoBench() = {
    IOs(())
      .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
      .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
      .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
      .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
      .flatMap(_ => IOs(())).map(_ => ()).flatMap(_ => IOs(())).map(_ => ())
  }

  def zioBench(): UIO[Unit] = {
    ZIO.succeed(())
      .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
      .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
      .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
      .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
      .flatMap(_ => ZIO.succeed(())).map(_ => ()).flatMap(_ => ZIO.succeed(())).map(_ => ())
  }
}
