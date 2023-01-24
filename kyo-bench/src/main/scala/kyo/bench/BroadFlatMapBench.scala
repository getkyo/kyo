package kyo.bench

import org.openjdk.jmh.annotations._
import cats.effect.IO
import kyo.core._
import kyo.ios._
import zio.{ZIO, UIO}
import java.util.concurrent.Executors

class BroadFlatMapBench extends Bench {

  val depth = 15

  def catsFib(n: Int): IO[BigInt] =
    if (n <= 1) IO.pure(BigInt(n))
    else
      catsFib(n - 1).flatMap(a => catsFib(n - 2).flatMap(b => IO.pure(a + b)))

  def kyoFib(n: Int): BigInt > IOs =
    if (n <= 1) IOs.value(BigInt(n))
    else kyoFib(n - 1)(a => kyoFib(n - 2)(b => IOs.value(a + b)))

  def zioFib(n: Int): UIO[BigInt] =
    if (n <= 1)
      ZIO.succeed(BigInt(n))
    else
      zioFib(n - 1).flatMap(a => zioFib(n - 2).flatMap(b => ZIO.succeed(a + b)))

  @Benchmark
  def kyoIO = KyoRuntime.runIO(kyoFib(depth))

  @Benchmark
  def forkedKyoFiber = KyoRuntime.runFiber(kyoFib(depth))

  @Benchmark
  def forkedCats = CatsRuntime.runForked(catsFib(depth))

  @Benchmark
  def zio = ZioRuntime.run(zioFib(depth))

  @Benchmark
  def forkedZio = ZioRuntime.runForked(zioFib(depth))

}
