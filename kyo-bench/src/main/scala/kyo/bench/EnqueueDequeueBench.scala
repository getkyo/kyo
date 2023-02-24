package kyo.bench

import org.openjdk.jmh.annotations._
import cats.effect.IO
import kyo.core._
import kyo.ios._
import zio.{ZIO, UIO}
import java.util.concurrent.Executors
import kyo.concurrent.fibers._
import kyo.concurrent.channels._
import kyo.concurrent.Access

import kyo.bench.Bench
import java.util.concurrent.atomic.AtomicInteger

class EnqueueDequeueBench extends Bench[Unit] {

  val depth = 10000

  def catsBench(): IO[Unit] = {
    import cats.effect.std.Queue

    def loop(q: Queue[IO, Unit], i: Int): IO[Unit] =
      if (i >= depth)
        IO.unit
      else
        q.offer(()).flatMap(_ => q.take.flatMap(_ => loop(q, i + 1)))

    Queue.bounded[IO, Unit](1).flatMap(loop(_, 0))
  }

  def kyoBench() = Fibers.block(kyoBenchFiber())

  override def kyoBenchFiber(): Unit > (IOs | Fibers) = {

    def loop(c: Channels.Blocking[Unit], i: Int): Unit > (IOs | Fibers) =
      if (i >= depth)
        IOs.unit
      else
        c.put(())(_ => c.take(_ => loop(c, i + 1)))

    Channels.makeBlocking[Unit](1, Access.Spsc)(loop(_, 0))
  }

  def zioBench(): UIO[Unit] = {
    import zio.Queue

    def loop(q: Queue[Unit], i: Int): ZIO[Any, Nothing, Unit] =
      if (i >= depth)
        ZIO.unit
      else
        q.offer(()).flatMap(_ => q.take.flatMap(_ => loop(q, i + 1)))

    Queue.bounded[Unit](1).flatMap(loop(_, 0))
  }
}
