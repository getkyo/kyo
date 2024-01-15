package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

class SemaphoreBench extends Bench.ForkOnly[Unit] {

  val depth = 10000

  def catsBench() = {
    import cats.effect._
    import cats.effect.std._

    def loop(s: Semaphore[IO], i: Int): IO[Unit] =
      if (i >= depth)
        IO.unit
      else
        s.acquire.flatMap(_ => s.release).flatMap(_ => loop(s, i + 1))

    Semaphore[IO](1).flatMap(loop(_, 0))
  }

  override def kyoBenchFiber() = {
    import kyo._
    import kyo.ios._
    import kyo.meters._
    import kyo.fibers._

    def loop(s: Meter, i: Int): Unit < Fibers =
      if (i >= depth)
        IOs.unit
      else
        s.run(()).flatMap(_ => loop(s, i + 1))

    Meters.initSemaphore(1).flatMap(loop(_, 0))
  }

  def zioBench() = {
    import zio._

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
