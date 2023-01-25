package kyo.bench

import org.openjdk.jmh.annotations._
import cats.effect.kernel.Fiber

import kyo.bench.Bench
import kyo.bench.CatsRuntime
import kyo.bench.ZioRuntime
import kyo.core.>
import kyo.concurrent.fibers.Fibers
import kyo.ios.IOs

class ForkManyBench extends Bench[Int] {

  def catsBench() = {
    import cats.effect.IO

    def repeat[A](n: Int)(io: IO[A]): IO[A] =
      if (n <= 1) io
      else io.flatMap(_ => repeat(n - 1)(io))

    for {
      deferred <- IO.deferred[Unit]
      ref      <- IO.ref(10000)
      effect = ref
        .modify(n => (n - 1, if (n == 1) deferred.complete(()) else IO.unit))
        .flatten
      _ <- repeat(10000)(effect.start)
      _ <- deferred.get
    } yield 0
  }

  def kyoBench() = Fibers.block(kyoBenchFiber())
  override def kyoBenchFiber() = {
    import kyo.core._
    import kyo.ios._
    import kyo.concurrent.refs._
    import kyo.concurrent.fibers._

    def repeat[A](n: Int)(io: A > IOs): A > IOs =
      if (n <= 1) io
      else io(_ => repeat(n - 1)(io))

    for {
      promise <- Fibers.promise[Unit]
      ref     <- IntRef(10000)
      effect = ref.decrementAndGet {
        case 1 =>
          promise.complete(())
        case _ =>
          false
      }
      _ <- repeat(10000)(Fibers.forkFiber(effect))
      _ <- promise.join
    } yield 0
  }

  def zioBench() = {
    import zio.{Promise, Ref, ZIO}

    def repeat[R, E, A](n: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
      if (n <= 1) zio
      else zio *> repeat(n - 1)(zio)

    for {
      promise <- Promise.make[Nothing, Unit]
      ref     <- Ref.make(10000)
      effect = ref
        .modify(n => (if (n == 1) promise.succeed(()) else ZIO.unit, n - 1))
        .flatten
      _ <- repeat(10000)(effect.forkDaemon)
      _ <- promise.await
    } yield 0
  }
}
