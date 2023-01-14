package kyo.bench

import org.openjdk.jmh.annotations._
import cats.effect.kernel.Fiber

import kyo.bench.Bench
import kyo.bench.CatsRuntime
import kyo.bench.ZioRuntime
class ForkManyBench extends Bench {

  @Benchmark
  def forkManyCats(): Int = {
    import cats.effect.IO

    def catsEffectRepeat[A](n: Int)(io: IO[A]): IO[A] =
      if (n <= 1) io
      else io.flatMap(_ => catsEffectRepeat(n - 1)(io))

    val io = for {
      deferred <- IO.deferred[Unit]
      ref      <- IO.ref(10000)
      effect = ref
        .modify(n => (n - 1, if (n == 1) deferred.complete(()) else IO.unit))
        .flatten
      _ <- catsEffectRepeat(10000)(effect.start)
      _ <- deferred.get
    } yield 0

    CatsRuntime.run(io)
  }

  @Benchmark
  def forkManyKyo(): Int = {
    import kyo.core._
    import kyo.ios._
    import kyo.concurrent.refs._
    import kyo.concurrent.fibers._

    def kyoRepeat[A](n: Int)(io: A > IOs): A > IOs =
      if (n <= 1) io
      else io(_ => kyoRepeat(n - 1)(io))

    val io: Int > (IOs | Fibers) =
      for {
        promise <- Fibers.promise[Unit]
        ref     <- IntRef(10000)
        effect = ref.decrementAndGet { i =>
          if (i == 1)
            promise.complete(())
          else
            false
        }
        _ <- kyoRepeat(10000)(Fibers.forkFiber(effect))
        _ <- promise.join
      } yield 0

    IOs.run(Fibers.block(io))
  }

  @Benchmark
  def forkManyZio(): Int = {
    import zio.{Promise, Ref, ZIO}

    def repeat[R, E, A](n: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
      if (n <= 1) zio
      else zio *> repeat(n - 1)(zio)

    val io = for {
      promise <- Promise.make[Nothing, Unit]
      ref     <- Ref.make(10000)
      effect = ref
        .modify(n => (if (n == 1) promise.succeed(()) else ZIO.unit, n - 1))
        .flatten
      _ <- repeat(10000)(effect.forkDaemon)
      _ <- promise.await
    } yield 0

    ZioRuntime.run(io)
  }
}
