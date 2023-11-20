package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

class ProducerConsumerBench extends Bench.ForkOnly[Unit] {

  val depth = 10000

  def catsBench() = {
    import cats.effect._
    import cats.effect.std._
    import cats.effect.kernel._

    def repeat[A](n: Int)(io: IO[A]): IO[A] =
      if (n <= 1) io
      else io.flatMap(_ => repeat(n - 1)(io))

    Queue.bounded[IO, Unit](depth / 2).flatMap { q =>
      for {
        producer <- repeat(depth)(q.offer(())).start
        consumer <- repeat(depth)(q.take).start
        _        <- producer.join
        _        <- consumer.join
      } yield {}
    }
  }

  override def kyoBenchFiber() = {
    import kyo._
    import kyo.ios._
    import kyo.concurrent.fibers._
    import kyo.concurrent.channels._
    import kyo.concurrent.Access

    def repeat[A](n: Int)(io: A > (IOs with Fibers)): A > (IOs with Fibers) =
      if (n <= 1) io
      else io.flatMap(_ => repeat(n - 1)(io))

    Channels.init[Unit](depth / 2, Access.Spsc).flatMap { q =>
      for {
        producer <- Fibers.fork(repeat(depth)(q.put(())))
        consumer <- Fibers.fork(repeat(depth)(q.take))
        _        <- producer.get
        _        <- consumer.get
      } yield {}
    }
  }

  def zioBench() = {
    import zio._

    def repeat[R, E, A](n: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
      if (n <= 1) zio
      else zio *> repeat(n - 1)(zio)

    Queue.bounded[Unit](depth / 2).flatMap { q =>
      for {
        producer <- repeat(depth)(q.offer(())).fork
        consumer <- repeat(depth)(q.take).fork
        _        <- producer.await
        _        <- consumer.await
      } yield {}
    }
  }

  @Benchmark
  def forkOx() = {
    import ox._
    import ox.channels._

    val q = Channel[Unit](depth / 2)
    scoped {
      val f1 =
        fork {
          for (_ <- 0 until depth) q.send(()).orThrow
        }
      val f2 =
        fork {
          for (_ <- 0 until depth) q.take(1).drain()
        }
      f1.join()
      f2.join()
    }
  }
}
