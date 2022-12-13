package kyo

import org.openjdk.jmh.annotations._
import scala.util._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.ArrayDeque
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

@State(Scope.Benchmark)
@Fork(
    jvmArgs = Array(
        // "-XX:+UnlockDiagnosticVMOptions",
        // "-XX:+LogCompilation",
        // "-Dgraal.Dump=", //, "-Dgraal.MethodFilter=kyo2.*",
        // "-XX:+PrintCompilation", "-XX:+PrintInlining",
        // "-XX:+TraceTypeProfile"
        "-Dcats.effect.tracing.mode=DISABLED"
    ),
    jvm = "/Users/flavio.brasil/Downloads/graalvm-ce-java17-22.3.0/Contents/Home/bin/java"
)
class Bench {

  var depth = 10000

  val catsEffectRuntime = cats.effect.unsafe.implicits.global
  val zioRuntime        = zio.Runtime.default

  @Benchmark
  def deepBindKyo(): Unit = {
    import kyo.ios._
    import kyo.core._
    import kyo.futures._
    def loop(i: Int): Unit > IOs =
      IOs {
        if (i > depth)
          ()
        else
          loop(i + 1)
      }
    Futures.block(
        Futures.fork(IOs.run(loop(0))),
        Duration.Inf
    )
  }

  @Benchmark
  def deepBindCatsEffect3(): Unit = {
    import cats.effect.IO

    def loop(i: Int): IO[Unit] =
      IO.unit.flatMap { _ =>
        if (i > depth)
          IO.unit
        else
          loop(i + 1)
      }

    runCatsEffect3(loop(0))
  }

  @Benchmark
  def deepBindZio2(): Unit = {
    import zio._

    def loop(i: Int): UIO[Unit] =
      ZIO.unit.flatMap { _ =>
        if (i > depth)
          ZIO.unit
        else
          loop(i + 1)
      }

    runZIO(loop(0))
  }

  @Benchmark
  def deepMapBindKyo(): Int = {
    import kyo.core._
    import kyo.futures._
    import kyo.ios._

    def loop(i: Int): Int > IOs =
      IOs {
        if (i > depth) i
        else
          IOs(i + 11)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(loop)
      }
    Futures.block(
        Futures.fork(IOs.run(loop(0))),
        Duration.Inf
    )
  }

  val a = {
    import kyo.core._
    import kyo.ios._
    import kyo.arrows._
    Arrows[Int, Nothing, Int, IOs](
        _(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(
            _ - 1
        )
    )
  }

  @Benchmark
  def deepMapBindKyoArrow(): Int = {
    import kyo.core._
    import kyo.futures._
    import kyo.ios._
    
    def loop(i: Int): Int > IOs =
      IOs {
        if (i > depth) i
        else
          IOs(i + 11)(a(_))(loop)
      }
    Futures.block(
        Futures.fork(IOs.run(loop(0))),
        Duration.Inf
    )
  }

  @Benchmark
  def deepMapBindCatsEffect3(): Int = {
    import cats.effect.IO
    import cats.effect.unsafe.implicits.global

    def loop(i: Int): IO[Int] =
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
            .flatMap(loop)
      }
    runCatsEffect3(loop(0))
  }

  @Benchmark
  def deepMapBindZio2(): Int = {
    import zio._

    def loop(i: Int): UIO[Int] =
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
            .flatMap(loop)
      }

    runZIO(loop(0))
  }

  private[this] def runCatsEffect3[A](io: cats.effect.IO[A]): A =
    (cats.effect.IO.cede.flatMap(_ => io)).unsafeRunSync()(catsEffectRuntime)

  private[this] def runZIO[A](io: zio.ZIO[Any, Throwable, A]): A =
    zio.Unsafe.unsafe(implicit u =>
      zioRuntime.unsafe.run(zio.ZIO.yieldNow.flatMap(_ => io)).getOrThrow()
    )
}
