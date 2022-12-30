package kyo.bench

import cats.effect.IO
import kyo.arrows._
import kyo.core.>
import kyo.ios.IOs
import kyo.scheduler.Scheduler
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import zio.UIO
import zio.ZIO

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util._

import kyo.bench.Bench
import kyo.bench.CatsRuntime
import kyo.bench.KyoRuntime

class DeepBindMapBench extends Bench {

  val depth = 10000

  final def kyoLoop(i: Int): Int > IOs =
    IOs {
      if (i > depth) i
      else
        IOs(i + 11)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(kyoLoop)
    }

  val mapArrow =
    Arrows[Int, Nothing, Int, IOs](
        _(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(
            _ - 1
        )
    )

  final def kyoLoopArrow(i: Int): Int > IOs =
    IOs {
      if (i > depth) i
      else
        IOs(i + 11)(mapArrow(_))(kyoLoopArrow)
    }

  final def catsLoop(i: Int): IO[Int] =
    IO.unit.flatMap { _ =>
      if (i > depth)
        IO.pure(i)
      else
        IO(i + 11)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .flatMap(catsLoop)
    }

  final def zioLoop(i: Int): UIO[Int] =
    ZIO.unit.flatMap { _ =>
      if (i > depth)
        ZIO.succeed(i)
      else
        ZIO.unit
          .map(_ => (i + 11))
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .map(_ - 1)
          .flatMap(zioLoop)
    }

  @Benchmark
  def kyoIO = KyoRuntime.runIO(kyoLoop(0))

  @Benchmark
  def forkedKyoFiber = KyoRuntime.runFiber(kyoLoop(0))

  @Benchmark
  def forkedKyoFuture = KyoRuntime.runFuture(kyoLoop(0))

  @Benchmark
  def kyoIOArrow = KyoRuntime.runIO(kyoLoopArrow(0))

  @Benchmark
  def forkedKyoFiberArrow = KyoRuntime.runFiber(kyoLoopArrow(0))

  @Benchmark
  def forkedKyoFutureArrow = KyoRuntime.runFuture(kyoLoopArrow(0))

  @Benchmark
  def forkedCats = CatsRuntime.runForked(catsLoop(0))

  @Benchmark
  def zio = ZioRuntime.run(zioLoop(0))

  @Benchmark
  def forkedZio = ZioRuntime.runForked(zioLoop(0))
}
