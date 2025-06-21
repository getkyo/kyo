package kyo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kyo.*
import kyo.ZIOs.toError
import kyo.ZIOs.toResult
import kyo.kernel.Platform
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.Eventually.*
import scala.concurrent.Future
import zio.Cause
import zio.Exit
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
        Abort.run(a).map(e => assert(e.isFailure))
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
                case Result.Failure(ex) =>
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
                case Result.Failure(ex) =>
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
            Async.run(ZIOs.get(ZIOs.run(42))).map(_.get)
                .map(v => assert(v == 42))
        }

        "complex nested pattern with parallel and race" in runKyo {
            def kyoTask(i: Int): Int < (Abort[Nothing] & Async)   = Async.run(i * 2).map(_.get)
            def zioTask(i: Int): Int < (Abort[Throwable] & Async) = ZIOs.get(ZIO.succeed(i + 1))

            for
                (v1, v2) <- Async.zip(kyoTask(1), zioTask(2))
                (v3, v4) <- Async.race(
                    Async.zip[Throwable, Int, Int, Any](kyoTask(v1), zioTask(v2)),
                    ZIOs.get(ZIO.succeed((v1, v2)))
                )
                (v5, v6) <- Async.zip(
                    kyoTask(v3 + v4),
                    Async.race[Throwable, Int, Any](zioTask(v1), kyoTask(v2))
                )
                result <- ZIOs.get(ZIO.succeed(v1 + v2 + v4 + v5))
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
                        loop(i + 1)
                }
            Sync.ensure(Sync(done.countDown()))(loop(0))
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
                            Async.zip[Throwable, Unit, Unit, Any](loop1, loop2)
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
                            _ <- ZIOs.get(zioLoop(started, done))
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
                        val loop1 = ZIOs.get(zioLoop(started, done))
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
                        val loop1 = ZIOs.get(zioLoop(started, done))
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

        "synthetic" in runKyo {
            val interrupted = ZIOs.get(ZIO.never.fork.flatMap(_.interrupt).flatten)
            Abort.run(interrupted).map { result =>
                assert(result.isPanic)
            }
        }
    }

    "failure translation" - {
        "fail to Abort[E]" in runKyo {
            val zioFailure: ZIO[Any, String, Int]        = ZIO.fail("ZIO failed")
            val kyoEffect: Int < (Abort[String] & Async) = ZIOs.get(zioFailure)
            Abort.run(kyoEffect).map { result =>
                assert(result == Result.fail("ZIO failed"))
            }
        }

        "die to Abort[Nothing] (panic)" in runKyo {
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

        "ZIO failure Both" in runKyo {
            val zioFailure: ZIO[Any, String, Int]        = ZIO.failCause(Cause.Both(Cause.empty, Cause.fail("ZIO failure")))
            val kyoEffect: Int < (Abort[String] & Async) = ZIOs.get(zioFailure)
            Abort.run(kyoEffect).map { result =>
                assert(result == Result.fail("ZIO failure"))
            }
        }

        "ZIO failure Then" in runKyo {
            val zioFailure: ZIO[Any, String, Int]        = ZIO.failCause(Cause.Then(Cause.fail("Left"), Cause.fail("Right")))
            val kyoEffect: Int < (Abort[String] & Async) = ZIOs.get(zioFailure)
            Abort.run(kyoEffect).map { result =>
                assert(result == Result.fail("Left"))
            }
        }

        "ZIO failure Empty" in runKyo {
            val zioFailure: ZIO[Any, Nothing, Int]        = ZIO.failCause(Cause.empty)
            val kyoEffect: Int < (Abort[Nothing] & Async) = ZIOs.get(zioFailure)
            Abort.run(kyoEffect).map { result =>
                assert(result.isPanic)
            }
        }
    }

    "Exit toResult" - {
        "Success" in {
            assert(Exit.succeed(42).toResult == Result.succeed(42))
        }

        "Failure" in {
            assert(Exit.fail("error").toResult == Result.fail("error"))
        }
    }

    "Cause toError" - {
        "Fail" in runKyo {
            val cause = Cause.fail("error")
            assert(cause.toError == Result.fail("error"))
        }

        "Die" in runKyo {
            val exception = new RuntimeException("die")
            val cause     = Cause.die(exception)
            cause.toError match
                case Result.Panic(e) => assert(e == exception)
        }

        "Interrupt" in runKyo {
            Cause.interrupt(zio.FiberId.None).toError match
                case Result.Panic(e: Fiber.Interrupted) => succeed
                case _                                  => fail("Expected Result.Panic with Fiber.Interrupted")
            end match
        }

        "Then" in runKyo {
            val cause = Cause.Then(Cause.fail("first"), Cause.fail("second"))
            assert(cause.toError == Result.fail("first"))
        }

        "Both" in runKyo {
            val cause = Cause.Both(Cause.fail("left"), Cause.fail("right"))
            assert(cause.toError == Result.fail("left"))
        }

        "Stackless" in runKyo {
            val innerCause = Cause.fail("error")
            val cause      = Cause.stackless(innerCause)
            assert(cause.toError == Result.fail("error"))
        }

        "Empty" in runKyo {
            val cause = Cause.empty
            cause.toError match
                case Result.Panic(e) =>
                    assert(e.getMessage.startsWith("Unexpected zio.Cause.Empty at"))
            end match
        }
    }

end ZIOsTest
