package kyo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kyo.*
import kyo.kernel.Platform
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.Eventually.*
import scala.concurrent.Future
import zio.Task
import zio.ZIO

class ZIOsTest extends Test:

    def runZIO[T](v: Task[T]): T =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.run(v).getOrThrow()
        )

    def runKyo(v: => Assertion < (Abort[Throwable] & Async)): Future[Assertion] =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.runToFuture(
                ZIOs.run(v)
            )
        )

    "Abort[String]" in runKyo {
        val a: Nothing < (Abort[String] & Async) = ZIOs.get(ZIO.fail("error"))
        Abort.run(a).map(e => assert(e.isFail))
    }
    "Abort[Throwable]" in runKyo {
        val a: Boolean < (Abort[Throwable] & Async) = ZIOs.get(ZIO.fail(new RuntimeException).when(false).as(true))
        Abort.run(a).map(e => assert(e.isSuccess))
    }

    "Abort ordering" - {
        "kyo then zio" in runKyo {
            object zioFailure extends RuntimeException
            object kyoFailure extends RuntimeException
            val a = Abort.fail(kyoFailure)
            val b = ZIOs.get(ZIO.fail(zioFailure))
            Abort.run(a.map(_ => b)).map {
                case Result.Fail(ex) =>
                    assert(ex == kyoFailure)
                case _ =>
                    fail()
            }
        }
        "zio then kyo" in runKyo {
            object zioFailure extends RuntimeException
            object kyoFailure extends RuntimeException
            val a = ZIOs.get(ZIO.fail(zioFailure))
            val b = Abort.fail(kyoFailure)
            Abort.run(a.map(_ => b)).map {
                case Result.Fail(ex) =>
                    assert(ex == zioFailure)
                case _ =>
                    fail()
            }
        }
    }

    "Envs[Int]" in pendingUntilFixed {
        assertCompiles("""
            val a: Int < (Env[Int] & ZIOs) = ZIOs.get(ZIO.service[Int])
            val b: Int < ZIOs              = Env.run(10)(a)
            b.map(v => assert(v == 10))
        """)
    }
    "Envs[Int & Double]" in pendingUntilFixed {
        assertCompiles("""
            val a = ZIOs.get(ZIO.service[Int] *> ZIO.service[Double])
        """)
    }

    "A < ZIOs" in runKyo {
        val a: Int < (Abort[Throwable] & Async) = ZIOs.get(ZIO.succeed(10))
        val b                                   = a.map(_ * 2)
        b.map(i => assert(i == 20))
    }

    "nested" in runKyo {
        val a: (String < (Abort[Throwable] & Async)) < (Abort[Throwable] & Async) = ZIOs.get(ZIO.succeed(ZIOs.get(ZIO.succeed("Nested"))))
        val b: String < (Abort[Throwable] & Async)                                = a.flatten
        b.map(s => assert(s == "Nested"))
    }

    "fibers" - {
        "basic interop" in runKyo {
            for
                v1 <- ZIOs.get(ZIO.succeed(1))
                v2 <- Async.run(2).map(_.get)
                v3 <- ZIOs.get(ZIO.succeed(3))
            yield assert(v1 == 1 && v2 == 2 && v3 == 3)
        }

        "nested Kyo in ZIO" in runKyo {
            ZIOs.get(ZIO.suspendSucceed {
                val kyoComputation = Async.run(42).map(_.get)
                ZIOs.run(kyoComputation)
            }).map(v => assert(v == 42))
        }

        "nested ZIO in Kyo" in runKyo {
            val nestedZIO = ZIOs.get(ZIO.succeed("nested"))
            Async.run(nestedZIO).map(_.get).map(s => assert(s == "nested"))
        }

        "complex nested pattern with parallel and race" in runKyo {
            def kyoTask(i: Int): Int < (Abort[Nothing] & Async)   = Async.run(i * 2).map(_.get)
            def zioTask(i: Int): Int < (Abort[Throwable] & Async) = ZIOs.get(ZIO.succeed(i + 1))

            for
                (v1, v2) <- Async.parallel(kyoTask(1), zioTask(2))
                (v3, v4) <- Async.race(
                    Async.parallel[Throwable, Int, Int, Any](kyoTask(v1), zioTask(v2)),
                    ZIOs.get(ZIO.succeed((v1, v2)))
                )
                (v5, v6) <- Async.parallel(
                    kyoTask(v3 + v4),
                    Async.race[Throwable, Int, Any](zioTask(v1), kyoTask(v2))
                )
                result <- ZIOs.get(ZIO.succeed(v1 + v2 + v4 + v5))
            yield assert(result >= 15 && result <= 30)
            end for
        }
    }

    "interrupts" - {

        def kyoLoop(started: CountDownLatch, done: CountDownLatch): Unit < IO =
            def loop(i: Int): Unit < IO =
                IO {
                    if i == 0 then
                        IO(started.countDown()).andThen(loop(i + 1))
                    else
                        loop(i + 2)
                }
            IO.ensure(IO(done.countDown()))(loop(0))
        end kyoLoop

        def zioLoop(started: CountDownLatch, done: CountDownLatch): Task[Unit] =
            def loop(i: Int): Task[Unit] =
                ZIO.suspend {
                    if i == 0 then
                        ZIO.attempt(started.countDown())
                            .flatMap(_ => loop(i + 1))
                    else
                        loop(i + 1)
                }
            loop(0).ensuring(ZIO.succeed(done.countDown()))
        end zioLoop

        if Platform.isJVM then

            "runZIO" - {
                "zio to kyo" in runZIO {
                    val started = new CountDownLatch(1)
                    val done    = new CountDownLatch(1)
                    for
                        f <- ZIOs.run(kyoLoop(started, done)).fork
                        _ <- ZIO.attempt(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.await
                        _ <- ZIO.attempt(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isInterrupted)
                    end for
                }

                "both" in runZIO {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    val v =
                        for
                            _ <- ZIOs.get(zioLoop(started, done))
                            _ <- Async.run(kyoLoop(started, done))
                        yield ()
                    for
                        f <- ZIOs.run(v).fork
                        _ <- ZIO.attempt(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.await
                        _ <- ZIO.attempt(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isInterrupted)
                    end for
                }

                "parallel loops" in runZIO {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def parallelEffect =
                        ZIOs.run {
                            val loop1 = ZIOs.get(zioLoop(started, done))
                            val loop2 = kyoLoop(started, done)
                            Async.parallel[Throwable, Unit, Unit, Any](loop1, loop2)
                        }
                    for
                        f <- parallelEffect.fork
                        _ <- ZIO.attempt(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.await
                        _ <- ZIO.attempt(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isInterrupted)
                    end for
                }

                "race loops" in runZIO {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def raceEffect =
                        ZIOs.run {
                            val loop1 = ZIOs.get(zioLoop(started, done))
                            val loop2 = kyoLoop(started, done)
                            Async.race[Throwable, Unit, Any](loop1, loop2)
                        }
                    for
                        f <- raceEffect.fork
                        _ <- ZIO.attempt(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.await
                        _ <- ZIO.attempt(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isInterrupted)
                    end for
                }
            }

            "runKyo" - {
                "kyo to zio" in runKyo {
                    val started = new CountDownLatch(1)
                    val done    = new CountDownLatch(1)
                    val panic   = Result.Panic(new Exception)
                    for
                        f <- Async.run(ZIOs.get(zioLoop(started, done)))
                        _ <- IO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt(panic)
                        r <- f.getResult
                        _ <- IO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r == panic)
                    end for
                }

                "both" in runKyo {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    val v =
                        for
                            _ <- ZIOs.get(zioLoop(started, done))
                            _ <- kyoLoop(started, done)
                        yield ()
                    for
                        f <- Async.run(v)
                        _ <- IO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.getResult
                        _ <- IO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isPanic)
                    end for
                }

                "parallel loops" in runKyo {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def parallelEffect =
                        val loop1 = ZIOs.get(zioLoop(started, done))
                        val loop2 = kyoLoop(started, done)
                        Async.parallel[Throwable, Unit, Unit, Any](loop1, loop2)
                    end parallelEffect
                    for
                        f <- Async.run(parallelEffect)
                        _ <- IO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.getResult
                        _ <- IO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isPanic)
                    end for
                }

                "race loops" in runKyo {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def raceEffect =
                        val loop1 = ZIOs.get(zioLoop(started, done))
                        val loop2 = kyoLoop(started, done)
                        Async.race(loop1, loop2)
                    end raceEffect
                    for
                        f <- Async.run(raceEffect)
                        _ <- IO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.getResult
                        _ <- IO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isPanic)
                    end for
                }
            }
        end if
    }

    "ZIO failure translation" - {
        "regular failure to Abort[E]" in runKyo {
            val zioFailure: ZIO[Any, String, Int]        = ZIO.fail("ZIO failed")
            val kyoEffect: Int < (Abort[String] & Async) = ZIOs.get(zioFailure)
            Abort.run(kyoEffect).map { result =>
                assert(result == Result.fail("ZIO failed"))
            }
        }

        "ZIO defect to Abort[Nothing] (panic)" in runKyo {
            val zioDefect: ZIO[Any, Nothing, Int]         = ZIO.die(new RuntimeException("ZIO defect"))
            val kyoEffect: Int < (Abort[Nothing] & Async) = ZIOs.get(zioDefect)
            Abort.run(kyoEffect).map { result =>
                assert(result.isPanic)
                assert(result.panic.exists(_.getMessage == "ZIO defect"))
            }
        }

        "nested ZIO failure in Kyo" in runKyo {
            val nestedZIO: ZIO[Any, String, Int] = ZIO.fail("Nested ZIO failed")
            val kyoEffect: Int < (Abort[String] & Async) =
                ZIOs.get(ZIO.succeed(ZIOs.get(nestedZIO))).flatten
            Abort.run(kyoEffect).map { result =>
                assert(result == Result.fail("Nested ZIO failed"))
            }
        }

        "ZIO failure with custom error type" in runKyo {
            case class CustomError(message: String)
            val zioFailure: ZIO[Any, CustomError, Int]        = ZIO.fail(CustomError("Custom ZIO error"))
            val kyoEffect: Int < (Abort[CustomError] & Async) = ZIOs.get(zioFailure)
            Abort.run(kyoEffect).map { result =>
                assert(result == Result.fail(CustomError("Custom ZIO error")))
            }
        }
    }
end ZIOsTest
