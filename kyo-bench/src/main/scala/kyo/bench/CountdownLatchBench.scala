package kyo.bench

import org.openjdk.jmh.annotations._
import scala.util._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.ArrayDeque
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.Executors
import kyo.concurrent.scheduler.Scheduler
import org.openjdk.jmh.infra.Blackhole
import kyo.core._
import kyo.concurrent.fibers._
import kyo.ios.IOs
import java.util.concurrent.locks.LockSupport
import cats.effect.kernel.Deferred
import cats.effect.IO

import kyo.bench.Bench

import kyo.concurrent.fibers
import kyo.concurrent.scheduler.Scheduler
import kyo.concurrent.fibers

class CountdownLatchBench extends Bench[Int] {

  val depth = 10000

  def catsBench() = {
    import cats.effect.std.CountDownLatch

    def iterate(l: CountDownLatch[IO], n: Int): IO[Any] =
      if (n <= 0) IO.unit
      else l.release.flatMap(_ => iterate(l, n - 1))

    for {
      l <- CountDownLatch[IO](depth)
      _ <- iterate(l, depth).start
      _ <- l.await
    } yield 0
  }

  def kyoBench() = Fibers.block(Fibers.fork(kyoBenchFiber()))

  override def kyoBenchFiber() = {
    import kyo.core._
    import kyo.concurrent.fibers._
    import kyo.concurrent.latches._
    import kyo.ios._

    def iterate(l: Latch, n: Int): Unit > IOs =
      if (n <= 0) IOs.unit
      else l.release(_ => iterate(l, n - 1))

    for {
      l <- Latches(depth)
      _ <- Fibers.forkFiber(iterate(l, depth))
      _ <- l.await
    } yield 0
  }

  def zioBench() = {
    import zio._
    import zio.concurrent._

    def iterate(l: CountdownLatch, n: Int): ZIO[Any, Nothing, Any] =
      if (n <= 0) ZIO.unit
      else l.countDown.flatMap(_ => iterate(l, n - 1))

    for {
      l <- CountdownLatch.make(depth)
      _ <- iterate(l, depth).forkDaemon
      _ <- l.await
    } yield 0
  }
}
