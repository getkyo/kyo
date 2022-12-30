package kyo.bench

import org.openjdk.jmh.annotations._
import scala.util._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.ArrayDeque
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.Executors
import kyo.scheduler.Scheduler
import org.openjdk.jmh.infra.Blackhole
import kyo.core._
import kyo.ios.IOs
import java.util.concurrent.locks.LockSupport
import cats.effect.kernel.Deferred
import cats.effect.IO

import kyo.bench.Bench
import kyo.bench.CatsRuntime

class ChainedForkBench extends Bench {

  val depth = 10000

  @Benchmark
  def chainedForkCats(): Int = {
    import cats.effect.{Deferred, IO}
    import cats.effect.unsafe.IORuntime

    def iterate(deferred: Deferred[IO, Unit], n: Int): IO[Any] =
      if (n <= 0) deferred.complete(())
      else IO.unit.flatMap(_ => iterate(deferred, n - 1).start)

    val io = for {
      deferred <- IO.deferred[Unit]
      _        <- iterate(deferred, depth).start
      _        <- deferred.get
    } yield 0

    CatsRuntime.run(io)
  }

  @Benchmark
  def chainedForkKyo(): Int = {
    import kyo.core._
    import kyo.fibers._
    import kyo.ios._

    def iterate(p: Promise[Unit], n: Int): Boolean > (IOs | Fibers) =
      if (n <= 0) p.complete(())
      else Fibers.fork(iterate(p, n - 1))(_ >> Fibers)

    val io: Int > (IOs | Fibers) = for {
      p <- Fibers.promise[Unit]
      _ <- Fibers.fork(iterate(p, depth))
      _ <- p.join
    } yield 0

    IOs.run(Fibers.block(IOs.lazyRun(io)))
  }

}
