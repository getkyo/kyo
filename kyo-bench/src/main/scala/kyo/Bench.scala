package kyo

import org.openjdk.jmh.annotations._
import scala.util._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.ArrayDeque
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import core._
import defers._
import ios._
import tries._

// object test extends App {
//   println((new Bench).deepBindKyo())
// }

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

  // val catsEffectRuntime = cats.effect.unsafe.implicits.global
  // val zioRuntime        = zio.Runtime.default

  @Benchmark
  def deepBindKyo(): Unit = {
    import kyo.defers._
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
        Futures.fork(IOs.run(loop(0)) < Tries),
        Duration.Inf
    ).get
  }

  @Benchmark
  def deepBindKyoDefer(): Unit = {
    import kyo.defers._
    import kyo.core._
    import kyo.futures._
    def loop(i: Int): Unit > Defers =
      Defers {
        if (i > depth)
          ()
        else
          loop(i + 1)
      }
    Futures.block(
        Futures.fork((loop(0) < Defers).run()),
        Duration.Inf
    )
  }

  // @Benchmark
  // def deepBindCatsEffect3(): Unit = {
  //   import cats.effect.IO

  //   def loop(i: Int): IO[Unit] =
  //     IO.unit.flatMap { _ =>
  //       if (i > depth)
  //         IO.unit
  //       else
  //         loop(i + 1)
  //     }

  //   runCatsEffect3(loop(0))
  // }

  // @Benchmark
  // def deepBindZio2(): Unit = {
  //   import zio._

  //   def loop(i: Int): UIO[Unit] =
  //     ZIO.unit.flatMap { _ =>
  //       if (i > depth)
  //         ZIO.unit
  //       else
  //         loop(i + 1)
  //     }

  //   runZIO(loop(0))
  // }

  // @Benchmark
  // def deepMapBindKyo(): Int = {
  //   import kyo.core._
  //   import kyo.defers._
  //   import kyo.futures._

  //   def loop(i: Int): Int > Defers =
  //     Defers {
  //       if (i > depth) i
  //       else
  //         Defers(i + 11)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(loop)
  //     }
  //   Futures.block(
  //       Futures.fork((loop(0) < Defers).run()),
  //       Duration.Inf
  //   )
  // }

  // val a: Int > Nothing => Int > Defers = {
  //   import kyo.core._
  //   import kyo.defers._
  //   import kyo.arrows._
  //   Arrows[Int, Nothing, Int, Defers](
  //       _(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(_ - 1)(
  //           _ - 1
  //       )
  //   )
  // }

  // @Benchmark
  // def deepMapBindKyoArrow(): Int = {
  //   import kyo.core._
  //   import kyo.defers._
  //   import kyo.futures._

  //   def loop(i: Int): Int > Defers =
  //     Defers {
  //       if (i > depth) i
  //       else
  //         Defers(i + 11)(a(_))(loop)
  //     }
  //   Futures.block(
  //       Futures.fork((loop(0) < Defers).run()),
  //       Duration.Inf
  //   )
  // }

  // @Benchmark
  // def deepMapBindCatsEffect3(): Int = {
  //   import cats.effect.IO
  //   import cats.effect.unsafe.implicits.global

  //   def loop(i: Int): IO[Int] =
  //     IO.unit.flatMap { _ =>
  //       if (i > depth)
  //         IO.pure(i)
  //       else
  //         IO(i + 11)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .flatMap(loop)
  //     }
  //   runCatsEffect3(loop(0))
  // }

  // @Benchmark
  // def deepMapBindZio2(): Int = {
  //   import zio._

  //   def loop(i: Int): UIO[Int] =
  //     ZIO.unit.flatMap { _ =>
  //       if (i > depth)
  //         ZIO.succeed(i)
  //       else
  //         ZIO.unit
  //           .map(_ => (i + 11))
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .map(_ - 1)
  //           .flatMap(loop)
  //     }

  //   runZIO(loop(0))
  // }

  // private[this] def runCatsEffect3[A](io: cats.effect.IO[A]): A =
  //   (cats.effect.IO.cede.flatMap(_ => io)).unsafeRunSync()(catsEffectRuntime)

  // private[this] def runZIO[A](io: zio.ZIO[Any, Throwable, A]): A =
  //   zio.Unsafe.unsafe(implicit u =>
  //     zioRuntime.unsafe.run(zio.ZIO.yieldNow.flatMap(_ => io)).getOrThrow()
  //   )
}
