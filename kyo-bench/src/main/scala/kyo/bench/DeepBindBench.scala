package kyo.bench

import cats.effect.IO
import kyo.core._
import kyo.ios._
import org.openjdk.jmh.annotations._
import zio.UIO
import zio.ZIO

import java.util.concurrent.atomic.AtomicInteger

import kyo.bench.Bench
import kyo.bench.CatsRuntime
import kyo.bench.KyoRuntime
import kyo.bench.ZioRuntime

class DeepBindBench extends Bench {

  val depth = 10000

  final def kyoLoop(i: Int): Unit > IOs =
    IOs {
      if (i > depth)
        ()
      else
        kyoLoop(i + 1)
    }

  final def catsLoop(i: Int): IO[Unit] =
    IO.unit.flatMap { _ =>
      if (i > depth)
        IO.unit
      else
        catsLoop(i + 1)
    }

  final def zioLoop(i: Int): UIO[Unit] =
    ZIO.unit.flatMap { _ =>
      if (i > depth)
        ZIO.unit
      else
        zioLoop(i + 1)
    }

  @Benchmark
  def kyoIO = KyoRuntime.runIO(kyoLoop(0))

  @Benchmark
  def forkedKyoFiber = KyoRuntime.runFiber(kyoLoop(0))

  @Benchmark
  def forkedKyoFuture = KyoRuntime.runFuture(kyoLoop(0))

  @Benchmark
  def forkedCats = CatsRuntime.runForked(catsLoop(0))

  @Benchmark
  def zio = ZioRuntime.run(zioLoop(0))

  @Benchmark
  def forkedZio = ZioRuntime.runForked(zioLoop(0))
}
