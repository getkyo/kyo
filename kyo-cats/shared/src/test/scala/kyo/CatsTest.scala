package kyo

import cats.effect.IO as CatsIO
import cats.effect.unsafe.implicits.global
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kyo.*
import kyo.kernel.Platform
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.Eventually.*
import scala.concurrent.Future

class CatsTest extends Test:

    def runCatsIO[T](v: CatsIO[T]): Future[T] =
        v.unsafeToFuture()

    def runKyo(v: => Assertion < (Abort[Nothing] & Async)): Future[Assertion] =
        Cats.run(v).unsafeToFuture()

    "Abort ordering" - {
        "kyo then cats" in runKyo {
            object catsFailure extends RuntimeException
            object kyoFailure  extends RuntimeException
            val a = Abort.fail(kyoFailure)
            val b = Cats.get(CatsIO.raiseError(catsFailure))
            Abort.run[Throwable](a.map(_ => b)).map {
                case Result.Failure(ex) =>
                    assert(ex == kyoFailure)
                case _ =>
                    fail()
            }
        }
        "cats then kyo" in runKyo {
            object catsFailure extends RuntimeException
            object kyoFailure  extends RuntimeException
            val a = Cats.get(CatsIO.raiseError(catsFailure))
            val b = Abort.fail(kyoFailure)
            Abort.run[Throwable](a.map(_ => b)).map {
                case Result.Panic(ex) =>
                    assert(ex == catsFailure)
                case ex =>
                    fail()
            }
        }
    }

    "A < Cats" in runKyo {
        val a = Cats.get(CatsIO.pure(10))
        val b = a.map(_ * 2)
        b.map(i => assert(i == 20))
    }

    "nested" in runKyo {
        val a = Cats.get(CatsIO.pure(Cats.get(CatsIO.pure("Nested")))).flatten
        a.map(s => assert(s == "Nested"))
    }

    "fibers" - {
        "basic interop" in runKyo {
            for
                v1 <- Cats.get(CatsIO.pure(1))
                v2 <- Async.run(2).map(_.get)
                v3 <- Cats.get(CatsIO.pure(3))
            yield assert(v1 == 1 && v2 == 2 && v3 == 3)
        }

        "nested Kyo in Cats" in runKyo {
            Cats.get(CatsIO.defer {
                val kyoComputation = Async.run(42).map(_.get)
                Cats.run(kyoComputation)
            }).map(v => assert(v == 42))
        }

        "nested Cats in Kyo" in runKyo {
            val nestedCats = Cats.get(CatsIO.pure("nested"))
            Async.run(nestedCats).map(_.get).map(s => assert(s == "nested"))
        }

        "complex nested pattern with parallel and race" in runKyo {
            def kyoTask(i: Int): Int < (Abort[Nothing] & Async)  = Async.run(i * 2).map(_.get)
            def catsTask(i: Int): Int < (Abort[Nothing] & Async) = Cats.get(CatsIO.pure(i + 1))

            for
                (v1, v2) <- Async.zip(kyoTask(1), catsTask(2))
                (v3, v4) <- Async.race(
                    Async.zip[Nothing, Int, Int, Any](kyoTask(v1), catsTask(v2)),
                    Cats.get(CatsIO.pure((v1, v2)))
                )
                (v5, v6) <- Async.zip(
                    kyoTask(v3 + v4),
                    Async.race[Nothing, Int, Any](catsTask(v1), kyoTask(v2))
                )
                result <- Cats.get(CatsIO.pure(v1 + v2 + v4 + v5))
            yield assert(result >= 15 && result <= 30)
            end for
        }
    }

    "interrupts" - {

        def kyoLoop(started: CountDownLatch, done: CountDownLatch): Unit < Sync =
            def loop(i: Int): Unit < Sync =
                Sync {
                    if i == 0 then
                        Sync(started.countDown()).andThen(loop(i + 1))
                    else
                        loop(i + 2)
                }
            Sync.ensure(Sync(done.countDown()))(loop(0))
        end kyoLoop

        def catsLoop(started: CountDownLatch, done: CountDownLatch): CatsIO[Unit] =
            def loop(i: Int): CatsIO[Unit] =
                CatsIO.defer {
                    if i == 0 then
                        CatsIO(started.countDown())
                            .flatMap(_ => loop(i + 1))
                    else
                        loop(i + 1)
                }
            loop(0).guarantee(CatsIO(done.countDown()))
        end catsLoop

        if Platform.isJVM then

            "runCatsIO" - {
                "cats to kyo" in runCatsIO {
                    val started = new CountDownLatch(1)
                    val done    = new CountDownLatch(1)
                    for
                        f <- Cats.run(kyoLoop(started, done)).start
                        _ <- CatsIO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.cancel
                        r <- f.join
                        _ <- CatsIO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isCanceled)
                    end for
                }

                "both" in runCatsIO {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    val v =
                        for
                            _ <- Cats.get(catsLoop(started, done))
                            _ <- Async.run(kyoLoop(started, done))
                        yield ()
                    for
                        f <- Cats.run(v).start
                        _ <- CatsIO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.cancel
                        r <- f.join
                        _ <- CatsIO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isCanceled)
                    end for
                }

                "parallel loops" in runCatsIO {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def parallelEffect =
                        Cats.run {
                            val loop1 = Cats.get(catsLoop(started, done))
                            val loop2 = kyoLoop(started, done)
                            Async.zip[Throwable, Unit, Unit, Any](loop1, loop2)
                        }
                    for
                        f <- parallelEffect.start
                        _ <- CatsIO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.cancel
                        r <- f.join
                        _ <- CatsIO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isCanceled)
                    end for
                }

                "race loops" in runCatsIO {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def raceEffect =
                        Cats.run {
                            val loop1 = Cats.get(catsLoop(started, done))
                            val loop2 = kyoLoop(started, done)
                            Async.race[Throwable, Unit, Any](loop1, loop2)
                        }
                    for
                        f <- raceEffect.start
                        _ <- CatsIO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.cancel
                        r <- f.join
                        _ <- CatsIO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isCanceled)
                    end for
                }
            }

            "runKyo" - {
                "kyo to cats" in runKyo {
                    val started = new CountDownLatch(1)
                    val done    = new CountDownLatch(1)
                    val panic   = Result.Panic(new Exception)
                    for
                        f <- Async.run(Cats.get(catsLoop(started, done)))
                        _ <- Sync(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt(panic)
                        r <- f.getResult
                        _ <- Sync(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r == panic)
                    end for
                }

                "both" in runKyo {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    val v =
                        for
                            _ <- Cats.get(catsLoop(started, done))
                            _ <- kyoLoop(started, done)
                        yield ()
                    for
                        f <- Async.run(v)
                        _ <- Sync(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.getResult
                        _ <- Sync(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isPanic)
                    end for
                }

                "parallel loops" in runKyo {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def parallelEffect =
                        val loop1 = Cats.get(catsLoop(started, done))
                        val loop2 = kyoLoop(started, done)
                        Async.zip[Throwable, Unit, Unit, Any](loop1, loop2)
                    end parallelEffect
                    for
                        f <- Async.run(parallelEffect)
                        _ <- Sync(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.getResult
                        _ <- Sync(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isPanic)
                    end for
                }

                "race loops" in runKyo {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def raceEffect =
                        val loop1 = Cats.get(catsLoop(started, done))
                        val loop2 = kyoLoop(started, done)
                        Async.race(loop1, loop2)
                    end raceEffect
                    for
                        f <- Async.run(raceEffect)
                        _ <- Sync(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.getResult
                        _ <- Sync(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isPanic)
                    end for
                }
            }
        end if
    }

    "Error handling" - {
        "Kyo Abort to Cats IO error" in runKyo {
            val kyoAbort  = Abort.fail(new Exception("Kyo error"))
            val converted = Cats.get(CatsIO.fromEither(Abort.run(kyoAbort).eval.toEither))
            Abort.run[Throwable](converted).map {
                case Result.Panic(ex) => assert(ex.getMessage() == "Kyo error")
                case _                => fail("Expected a String error")
            }
        }

        "Cats IO error to Kyo Abort" in runKyo {
            val catsError = CatsIO.raiseError[Int](new Exception("Cats error"))
            val converted = Cats.get(catsError)
            Abort.run[Throwable](converted).map {
                case Result.Panic(error: Exception) => assert(error.getMessage == "Cats error")
                case _                              => fail("Expected an Exception")
            }
        }
    }

end CatsTest
