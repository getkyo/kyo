package kyo.bench

import org.openjdk.jmh.annotations.*

class RendezvousBench extends Bench.ForkOnly(10000 * (10000 + 1) / 2):

    given canEqualNull[A]: CanEqual[A, A | Null] = CanEqual.derived

    val depth = 10000

    def catsBench() =
        import cats.effect.*
        import cats.effect.kernel.*

        def produce(waiting: Ref[IO, Any], n: Int = 0): IO[Unit] =
            if n <= depth then
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
            else
                IO.unit

        def consume(waiting: Ref[IO, Any], n: Int = 0, acc: Int = 0): IO[Int] =
            if n <= depth then
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
            else
                IO.pure(acc)

        for
            waiting  <- Ref[IO].of[Any](null)
            _        <- produce(waiting).start
            consumer <- consume(waiting).start
            res      <- consumer.joinWithNever
        yield res
        end for
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        def produce(waiting: AtomicRef[Any], n: Int = 0): Unit < Async =
            if n <= depth then
                Promise.init[Nothing, Unit].flatMap { p =>
                    waiting.compareAndSet(null, (p, n)).flatMap {
                        case false =>
                            waiting.getAndSet(null).flatMap {
                                _.asInstanceOf[Promise[Nothing, Int]].complete(Result.success(n))
                            }
                        case true =>
                            p.get
                    }.flatMap { _ =>
                        produce(waiting, n + 1)
                    }
                }
            else
                IO.unit

        def consume(waiting: AtomicRef[Any], n: Int = 0, acc: Int = 0): Int < Async =
            if n <= depth then
                Promise.init[Nothing, Int].flatMap { p =>
                    waiting.compareAndSet(null, p).flatMap {
                        case false =>
                            waiting.getAndSet(null).flatMap {
                                case (p2: Promise[Nothing, Unit] @unchecked, i: Int) =>
                                    p2.complete(Result.unit).map(_ => i)
                            }
                        case true =>
                            p.get
                    }.flatMap { i =>
                        consume(waiting, n + 1, acc + i)
                    }
                }
            else
                acc

        for
            waiting  <- AtomicRef.init[Any](null)
            _        <- Async.run(produce(waiting))
            consumer <- Async.run(consume(waiting))
            res      <- consumer.get
        yield res
        end for
    end kyoBenchFiber

    def zioBench() =
        import zio.*

        def produce(waiting: Ref[Any], n: Int = 0): Task[Unit] =
            if n <= depth then
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
            else
                ZIO.unit

        def consume(waiting: Ref[Any], n: Int = 0, acc: Int = 0): Task[Int] =
            if n <= depth then
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
            else
                ZIO.succeed(acc)

        for
            waiting  <- Ref.make[Any](null)
            _        <- produce(waiting).fork
            consumer <- consume(waiting).fork
            res      <- consumer.join.orDie
        yield res
        end for
    end zioBench

end RendezvousBench
