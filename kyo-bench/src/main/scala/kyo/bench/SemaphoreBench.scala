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

class SemaphoreBench extends Bench.ForkOnly[Unit] {

  val depth = 10000

  def catsBench(): IO[Unit] = {
    import cats.effect.std.Semaphore

    def loop(s: Semaphore[IO], i: Int): IO[Unit] =
      if (i >= depth)
        IO.unit
      else
        s.acquire.flatMap(_ => s.release).flatMap(_ => loop(s, i + 1))

    Semaphore[IO](1).flatMap(loop(_, 0))
  }

  override def kyoBenchFiber(): Unit > (IOs with Fibers) = {
    import kyo.concurrent.meters._

    def loop(s: Meter, i: Int): Unit > (IOs with Fibers) =
      if (i >= depth)
        IOs.unit
      else
        s.run(()).flatMap(_ => loop(s, i + 1))

    Meters.initSemaphore(1).flatMap(loop(_, 0))
  }

  def zioBench(): UIO[Unit] = {
    import zio.Semaphore

    def loop(s: Semaphore, i: Int): ZIO[Any, Nothing, Unit] =
      if (i >= depth)
        ZIO.unit
      else
        s.withPermit(ZIO.succeed(())).flatMap(_ => loop(s, i + 1))

    Semaphore.make(1).flatMap(loop(_, 0))
  }

  @Benchmark
  def forkOx() = {
    import java.util.concurrent.Semaphore
    import ox._
    scoped {
      val sem = new Semaphore(1, true)
      val f = fork {
        for (_ <- 0 to depth) {
          sem.acquire()
          sem.release()
        }
      }
      f.join()
    }
  }
}
