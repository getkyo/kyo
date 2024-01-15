package kyo.bench

import org.openjdk.jmh.annotations._

class RendezvousBench extends Bench.ForkOnly[Int] {

  val depth = 10000

  def catsBench() = {
    import cats.effect._
    import cats.effect.kernel._

    def produce(waiting: Ref[IO, Any], n: Int = 0): IO[Unit] =
      if (n <= depth) {
        Deferred[IO, Unit].flatMap { p =>
          waiting.modify {
            case null => ((p, n), true)
            case v    => (v, false)
          }.flatMap {
            case false =>
              waiting.getAndSet(null).flatMap {
                _.asInstanceOf[Deferred[IO, Int]].complete(n)
              }
            case true =>
              p.get
          }.flatMap { _ =>
            produce(waiting, n + 1)
          }
        }
      } else {
        IO.unit
      }

    def consume(waiting: Ref[IO, Any], n: Int = 0, acc: Int = 0): IO[Int] =
      if (n <= depth) {
        Deferred[IO, Int].flatMap { p =>
          waiting.modify {
            case null => (p, true)
            case v    => (v, false)
          }.flatMap {
            case false =>
              waiting.getAndSet(null).flatMap {
                case (p2: Deferred[IO, Unit] @unchecked, i: Int) =>
                  p2.complete(()).map(_ => i)
              }
            case true =>
              p.get
          }.flatMap { i =>
            consume(waiting, n + 1, acc + i)
          }
        }
      } else {
        IO.pure(acc)
      }

    for {
      waiting  <- Ref[IO].of[Any](null)
      _        <- produce(waiting).start
      consumer <- consume(waiting).start
      res      <- consumer.joinWithNever
    } yield res
  }

  override def kyoBenchFiber() = {
    import kyo._
    import kyo._
    import kyo.ios._
    import kyo.fibers._

    def produce(waiting: AtomicRef[Any], n: Int = 0): Unit < Fibers =
      if (n <= depth) {
        Fibers.initPromise[Unit].flatMap { p =>
          waiting.cas(null, (p, n)).flatMap {
            case false =>
              waiting.getAndSet(null).flatMap {
                _.asInstanceOf[Promise[Int]].complete(n)
              }
            case true =>
              p.get
          }.flatMap { _ =>
            produce(waiting, n + 1)
          }
        }
      } else {
        IOs.unit
      }

    def consume(waiting: AtomicRef[Any], n: Int = 0, acc: Int = 0): Int < Fibers =
      if (n <= depth) {
        Fibers.initPromise[Int].flatMap { p =>
          waiting.cas(null, p).flatMap {
            case false =>
              waiting.getAndSet(null).flatMap {
                case (p2: Promise[Unit] @unchecked, i: Int) =>
                  p2.complete(()).map(_ => i)
              }
            case true =>
              p.get
          }.flatMap { i =>
            consume(waiting, n + 1, acc + i)
          }
        }
      } else {
        acc
      }

    for {
      waiting  <- Atomics.initRef[Any](null)
      _        <- Fibers.init(produce(waiting))
      consumer <- Fibers.init(consume(waiting))
      res      <- consumer.get
    } yield res
  }

  def zioBench() = {
    import zio._

    def produce(waiting: Ref[Any], n: Int = 0): Task[Unit] =
      if (n <= depth) {
        Promise.make[Nothing, Unit].flatMap { p =>
          waiting.modify {
            case null => (true, (p, n))
            case v    => (false, v)
          }.flatMap {
            case false =>
              waiting.getAndSet(null).flatMap {
                _.asInstanceOf[Promise[Nothing, Int]].succeed(n)
              }
            case true =>
              p.await
          }.flatMap { _ =>
            produce(waiting, n + 1)
          }
        }
      } else {
        ZIO.unit
      }

    def consume(waiting: Ref[Any], n: Int = 0, acc: Int = 0): Task[Int] =
      if (n <= depth) {
        Promise.make[Nothing, Int].flatMap { p =>
          waiting.modify {
            case null => (true, p)
            case v    => (false, v)
          }.flatMap {
            case false =>
              waiting.getAndSet(null).flatMap {
                case (p2: Promise[Nothing, Unit] @unchecked, i: Int) =>
                  p2.succeed(()).map(_ => i)
              }
            case true =>
              p.await
          }.flatMap { i =>
            consume(waiting, n + 1, acc + i)
          }
        }
      } else {
        ZIO.succeed(acc)
      }

    for {
      waiting  <- Ref.make[Any](null)
      _        <- produce(waiting).fork
      consumer <- consume(waiting).fork
      res      <- consumer.join.orDie
    } yield res
  }

  @Benchmark
  def forkOx() = {
    import ox._
    import java.util.concurrent.atomic.AtomicReference
    import java.util.concurrent.CompletableFuture

    def produce(waiting: AtomicReference[Any]): Unit =
      for (n <- 0 to depth) {
        val p = new CompletableFuture[Unit]()
        if (!waiting.compareAndSet(null, (p, n))) {
          waiting.getAndSet(null)
            .asInstanceOf[CompletableFuture[Int]]
            .complete(n)
        } else {
          p.join()
        }
      }

    def consume(waiting: AtomicReference[Any]): Int = {
      var acc = 0
      for (n <- 0 to depth) {
        val p = new CompletableFuture[Int]()
        if (!waiting.compareAndSet(null, p)) {
          val (p, n) =
            waiting.getAndSet(null)
              .asInstanceOf[(CompletableFuture[Unit], Int)]
          p.complete(())
          acc += n
        } else {
          acc += p.join()
        }
      }
      acc
    }

    scoped {
      val waiting = new AtomicReference[Any]()
      val f1 = fork {
        produce(waiting)
      }
      val f2 = fork {
        consume(waiting)
      }
      f1.join()
      f2.join()
    }
  }
}
