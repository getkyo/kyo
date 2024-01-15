package kyo.bench

import org.openjdk.jmh.annotations._

class ForkChainedBench extends Bench.ForkOnly[Int] {

  val depth = 10000

  def catsBench() = {
    import cats.effect._

    def iterate(deferred: Deferred[IO, Unit], n: Int): IO[Any] =
      if (n <= 0) deferred.complete(())
      else IO.unit.flatMap(_ => iterate(deferred, n - 1).start)

    for {
      deferred <- IO.deferred[Unit]
      _        <- iterate(deferred, depth).start
      _        <- deferred.get
    } yield 0
  }

  override def kyoBenchFiber() = {
    import kyo._
    import kyo.ios._

    def iterate(p: Promise[Unit], n: Int): Unit < IOs =
      if (n <= 0) p.complete(()).unit
      else IOs.unit.flatMap(_ => Fibers.init(iterate(p, n - 1)).unit)

    for {
      p <- Fibers.initPromise[Unit]
      _ <- Fibers.init(iterate(p, depth))
      _ <- p.get
    } yield 0
  }

  def zioBench() = {
    import zio.{Promise, ZIO}

    def iterate(promise: Promise[Nothing, Unit], n: Int): ZIO[Any, Nothing, Any] =
      if (n <= 0) promise.succeed(())
      else ZIO.unit.flatMap(_ => iterate(promise, n - 1).forkDaemon)

    for {
      promise <- Promise.make[Nothing, Unit]
      _       <- iterate(promise, depth).forkDaemon
      _       <- promise.await
    } yield 0
  }

  @Benchmark
  def forkOx() = {
    import ox._
    import java.util.concurrent.CompletableFuture

    def iterate(p: CompletableFuture[Unit], n: Int): Unit =
      if (n <= 0) p.complete(())
      else
        scoped {
          fork(iterate(p, n - 1))
        }

    scoped {
      val p = new CompletableFuture[Unit]()
      fork(iterate(p, depth))
      p.get()
    }
  }
}
