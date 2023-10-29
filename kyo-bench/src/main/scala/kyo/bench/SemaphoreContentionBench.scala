package kyo.bench

import kyo.bench.Bench
import org.openjdk.jmh.annotations._

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SemaphoreContentionBench extends Bench.ForkOnly[Unit] {

  val permits = 10
  val fibers  = 100
  val depth   = 1000

  def catsBench() = {
    import cats.effect.IO
    import cats.effect.std.Semaphore
    import cats.effect.std.CountDownLatch

    def repeat[A](n: Int)(io: IO[A]): IO[A] =
      if (n <= 1) io
      else io.flatMap(_ => repeat(n - 1)(io))

    def loop(sem: Semaphore[IO], cdl: CountDownLatch[IO], i: Int = 0): IO[Unit] =
      if (i >= depth)
        cdl.release
      else
        sem.acquire.flatMap(_ => sem.release)
          .flatMap(_ => loop(sem, cdl, i + 1))

    for {
      sem <- Semaphore[IO](permits)
      cdl <- CountDownLatch[IO](fibers)
      _   <- repeat(fibers)(loop(sem, cdl).start)
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

    def loop(sem: Meter, cdl: Latch, i: Int = 0): Unit > (IOs with Fibers) =
      if (i >= depth)
        cdl.release
      else
        sem.run(()).flatMap(_ => loop(sem, cdl, i + 1))

    for {
      sem <- Meters.initSemaphore(permits)
      cdl <- Latches.init(fibers)
      _   <- repeat(fibers)(Fibers.forkFiber(loop(sem, cdl)))
      _   <- cdl.await
    } yield {}
  }

  def zioBench() = {
    import zio._
    import zio.concurrent._

    def repeat[R, E, A](n: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
      if (n <= 1) zio
      else zio.flatMap(_ => repeat(n - 1)(zio))

    def loop(sem: Semaphore, cdl: CountdownLatch, i: Int = 0): Task[Unit] =
      if (i >= depth)
        cdl.countDown
      else
        sem.withPermit(ZIO.succeed(()))
          .flatMap(_ => loop(sem, cdl, i + 1))

    for {
      sem <- Semaphore.make(permits)
      cdl <- CountdownLatch.make(fibers)
      _   <- repeat(fibers)(loop(sem, cdl).forkDaemon)
      _   <- cdl.await
    } yield {}
  }
}
