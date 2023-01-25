package kyo.bench

import kyo.core._
import kyo.concurrent.fibers._
import kyo.ios.IOs
import zio.UIO
import org.openjdk.jmh.annotations._
import cats.effect.IO

@State(Scope.Benchmark)
@Fork(
    value = 1,
    jvmArgs = Array(
        // "-XX:+UnlockDiagnosticVMOptions",
        // "-Dgraal.PrintCompilation=true",
        // "-Dgraal.Log",
        // "-Dgraal.MethodFilter=kyo.scheduler.IOFiber.*",
        // "-XX:+TraceDeoptimization",
        // "-XX:+LogCompilation",
        // "-Dgraal.Dump=", //, "-Dgraal.MethodFilter=kyo2.*",
        // "-XX:+PrintCompilation", "-XX:+PrintInlining",
        // "-XX:+TraceTypeProfile"
        "-Dcats.effect.tracing.mode=DISABLED"
    ),
    // jvm = "/Users/flavio.brasil/Downloads/graalvm-ce-java17-22.3.0/Contents/Home/bin/java"
)
@BenchmarkMode(Array(Mode.Throughput))
abstract class Bench[T] {

  def zioBench(): UIO[T]
  def kyoBenchFiber(): T > (IOs | Fibers) = kyoBench()
  def kyoBench(): T > IOs
  def catsBench(): IO[T]

  @Benchmark
  def syncKyo(): T = KyoRuntime.run(kyoBench())

  @Benchmark
  def forkKyo(): T = KyoRuntime.runFork(kyoBenchFiber())

  @Benchmark
  def syncCats(): T = CatsRuntime.run(catsBench())

  @Benchmark
  def forkCats(): T = CatsRuntime.runFork(catsBench())

  @Benchmark
  def syncZio(): T = ZioRuntime.run(zioBench())

  @Benchmark
  def forkZio(): T = ZioRuntime.runFork(zioBench())
}
