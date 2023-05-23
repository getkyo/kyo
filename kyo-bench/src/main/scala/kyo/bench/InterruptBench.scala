package kyo.bench

import cats.effect.IO
import cats.effect.kernel.Deferred
import kyo.bench.Bench
import kyo.concurrent.fibers
import kyo.concurrent.fibers._
import kyo.concurrent.scheduler.Scheduler
import kyo._
import kyo.ios.IOs
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import zio.UIO

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util._

class InterruptBench extends Bench[Unit] {

  val depth = 1000

  def catsBench() = {
    import cats.effect.{Deferred, IO}
    import cats.effect.unsafe.IORuntime

    def loop(): IO[Any] =
      IO(loop())

    def iterate(n: Int): IO[Unit] =
      if (n <= 0) IO.unit
      else loop().start.flatMap(_.cancel).flatMap(_ => iterate(n - 1))

    iterate(depth)
  }

  def kyoBench() = Fibers.block(Fibers.fork(kyoBenchFiber()))

  override def kyoBenchFiber() = {
    import kyo._
    import kyo.concurrent.fibers._
    import kyo.ios._

    def loop(): Unit > IOs =
      IOs(loop())

    def iterate(n: Int): Unit > (IOs & Fibers) =
      if (n <= 0) IOs.unit
      else Fibers.forkFiber(loop()).flatMap(_.interruptAwait).flatMap(_ => iterate(n - 1))

    iterate(depth)
  }

  def zioBench() = {
    import zio.{Promise, RIO, ZIO}

    def loop(): RIO[Any, Any] =
      ZIO.suspend(loop())

    def iterate(n: Int): UIO[Unit] =
      if (n <= 0) ZIO.unit
      else loop().fork.flatMap(_.interrupt).flatMap(_ => iterate(n - 1))

    iterate(depth)
  }
}
