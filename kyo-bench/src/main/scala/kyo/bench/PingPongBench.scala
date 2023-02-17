// package kyo.bench

// import org.openjdk.jmh.annotations._
// import cats.effect.IO
// import kyo.core._
// import kyo.ios._
// import zio.{ZIO, UIO}
// import java.util.concurrent.Executors
// import kyo.concurrent.fibers._
// import kyo.concurrent.channels._
// import kyo.concurrent.Access

// import kyo.bench.Bench
// import java.util.concurrent.atomic.AtomicInteger
// import cats.effect.kernel.Deferred
// import kyo.concurrent.atomics.AtomicInt

// class PingPongBench extends Bench[Unit] {

//   val depth = 1000

//   def catsBench(): IO[Unit] = {
//     import cats.effect.std.Queue

//     def repeat[A](n: Int)(io: IO[A]): IO[A] =
//       if (n <= 1) io
//       else io.flatMap(_ => repeat(n - 1)(io))

//     def iterate(deferred: Deferred[IO, Unit], n: Int): IO[Any] =
//       for {
//         ref   <- IO.ref(n)
//         queue <- Queue.bounded[IO, Unit](1)
//         effect = queue.offer(()).start >>
//           queue.take >>
//           ref
//             .modify(n =>
//               (n - 1, if (n == 1) deferred.complete(()) else IO.unit)
//             )
//             .flatten
//         _ <- repeat(depth)(effect.start)
//       } yield ()

//     for {
//       deferred <- IO.deferred[Unit]
//       _        <- iterate(deferred, depth).start
//       _        <- deferred.get
//     } yield ()
//   }

//   def kyoBench() = Fibers.block(kyoBenchFiber())

//   override def kyoBenchFiber(): Unit > (IOs | Fibers) = {
//     import kyo.concurrent.queues._

//     def repeat[A](n: Int)(io: A > (IOs | Fibers)): A > (IOs | Fibers) =
//       if (n <= 1) io
//       else io(_ => repeat(n - 1)(io))

//     def iterate(promise: Promise[Unit], n: Int): Unit > (IOs | Fibers) =
//       for {
//         ref  <- AtomicInt(n)
//         chan <- Channel.blocking[Unit](1)
//         effect =
//           for {
//             _ <- Fibers.forkFiber(chan.offer(()))
//             _ <- chan.take
//             n <- ref.decrementAndGet
//             _ <- if (n == 0) promise.complete(()) else IOs.unit
//           } yield ()
//         _ <- repeat(depth)(Fibers.fork(effect))
//       } yield ()

//     for {
//       promise <- Fibers.promise[Unit]
//       _       <- Fibers.forkFiber(iterate(promise, depth))
//       _       <- promise.join
//     } yield ()
//   }

//   def zioBench(): UIO[Unit] = {
//     import zio.Queue
//     import zio.Promise
//     import zio.Ref

//     def repeat[R, E, A](n: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
//       if (n <= 1) zio
//       else zio *> repeat(n - 1)(zio)

//     def iterate(promise: Promise[Nothing, Unit], n: Int): ZIO[Any, Nothing, Any] =
//       for {
//         ref   <- Ref.make(n)
//         queue <- Queue.bounded[Unit](1)
//         effect = queue.offer(()).forkDaemon *>
//           queue.take *>
//           ref
//             .modify(n =>
//               (if (n == 1) promise.succeed(()) else ZIO.unit, n - 1)
//             )
//             .flatten
//         _ <- repeat(depth)(effect.forkDaemon)
//       } yield ()

//     for {
//       promise <- Promise.make[Nothing, Unit]
//       _       <- iterate(promise, depth).forkDaemon
//       _       <- promise.await
//     } yield ()
//   }
// }
