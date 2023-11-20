package kyo.bench

import org.openjdk.jmh.annotations._

class ForkSpawnBench extends Bench.ForkOnly[Unit] {

  val depth = 5
  val width = 10
  val total = ((Math.pow(width, depth) - 1) / (width - 1)).toInt

  def catsBench() = {
    import cats.effect.IO
    import cats.effect.std.Semaphore
    import cats.effect.std.CountDownLatch

    def repeat[A](n: Int)(io: IO[A]): IO[A] =
      if (n <= 1) io
      else io.flatMap(_ => repeat(n - 1)(io))

    def loop(cdl: CountDownLatch[IO], level: Int): IO[Unit] =
      if (level == depth) {
        cdl.release
      } else {
        repeat(width)(loop(cdl, level + 1).start.map(_ => ()))
      }

    for {
      cdl <- CountDownLatch[IO](total)
      _   <- loop(cdl, 0)
      _   <- cdl.await
    } yield {}
  }

  override def kyoBenchFiber() = {
    import kyo._
    import kyo.ios._
    import kyo.concurrent.fibers._
    import kyo.concurrent.meters._
    import kyo.concurrent.latches._

    def repeat[A](n: Int)(io: A > IOs): A > IOs =
      if (n <= 1) io
      else io.flatMap(_ => repeat(n - 1)(io))

    def loop(cdl: Latch, level: Int): Unit > Fibers =
      if (level == depth) {
        cdl.release
      } else {
        repeat(width)(Fibers.fork(loop(cdl, level + 1)).map(_ => ()))
      }

    for {
      cdl <- Latches.init(total)
      _   <- loop(cdl, 0)
      _   <- cdl.await
    } yield {}
  }

  def zioBench() = {
    import zio._
    import zio.concurrent._

    def repeat[A](n: Int)(io: Task[A]): Task[A] =
      if (n <= 1) io
      else io.flatMap(_ => repeat(n - 1)(io))

    def loop(cdl: CountdownLatch, level: Int): Task[Unit] =
      if (level == depth) {
        cdl.countDown
      } else {
        repeat(width)(loop(cdl, level + 1).forkDaemon.map(_ => ()))
      }

    for {
      cdl <- CountdownLatch.make(total)
      _   <- loop(cdl, 0).orDie
      _   <- cdl.await
    } yield {}
  }

  @Benchmark
  def forkOx() = {
    import ox._
    import java.util.concurrent._
    scoped {
      val cdl = new CountDownLatch(total)
      def loop(level: Int): Unit =
        if (level == depth) {
          cdl.countDown()
        } else {
          for (_ <- 0 until width) {
            fork {
              loop(level + 1)
            }
          }
        }
      loop(0)
      cdl.await()
    }
  }
}
