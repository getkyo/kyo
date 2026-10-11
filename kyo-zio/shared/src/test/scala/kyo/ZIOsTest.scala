package kyo

import kyo.*
import kyo.ZIOs.*
import zio.Cause
import zio.Exit
import zio.Task
import zio.ZIO

class ZIOsTest extends kyo.test.Test[Any]:
    given [E]: CanEqual[Cause[E], Cause[E]]        = CanEqual.derived
    given [E, A]: CanEqual[Exit[E, A], Exit[E, A]] = CanEqual.derived

    // Run the body THROUGH the ZIO runtime (preserving the kyo<->ZIO interop these tests cover), bridging the
    // resulting Future back into a kyo computation so it can be a kyo-test leaf body.
    def runZIO[T](v: Task[T]): T < Async =
        Async.fromFuture(zio.Unsafe.unsafe(implicit u => zio.Runtime.default.unsafe.runToFuture(v)))

    def runKyo(v: => Unit < (Abort[Throwable] & Async)): Unit < Async =
        Async.fromFuture(zio.Unsafe.unsafe(implicit u => zio.Runtime.default.unsafe.runToFuture(ZIOs.run(v))))

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

    "Envs[Int]" in {
        typeCheckFailure("""
            val a: Int < (Env[Int] & Async) = ZIOs.get(ZIO.service[Int])
            val b: Int < Async              = Env.run(10)(a)
            b.map(v => assert(v == 10))
        """)("ZIO environments are not supported yet.")
    }
    "Envs[Int & Double]" in {
        typeCheckFailure("""
            val a = ZIOs.get(ZIO.service[Int] *> ZIO.service[Double])
        """)(
            """ZIO environments are not supported yet. Please handle them before calling this method.
You must not use an intersection type, yet have provided scala.Int & scala.Double"""
        )
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
                v2 <- Fiber.initUnscoped(2).map(_.get)
                v3 <- ZIOs.get(ZIO.succeed(3))
            yield assert(v1 == 1 && v2 == 2 && v3 == 3)
        }

        "nested Kyo in ZIO" in runKyo {
            ZIOs.get(ZIO.suspendSucceed {
                val kyoComputation = Fiber.initUnscoped(42).map(_.get)
                ZIOs.run(kyoComputation)
            }).map(v => assert(v == 42))
        }

        "nested ZIO in Kyo" in runKyo {
            Fiber.initUnscoped(ZIOs.get(ZIOs.run(42))).map(_.get)
                .map(v => assert(v == 42))
        }

        "complex nested pattern with parallel and race" in runKyo {
            def kyoTask(i: Int): Int < (Abort[Nothing] & Async)   = Fiber.initUnscoped(i * 2).map(_.get)
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

        def kyoLoop(started: Latch, done: Latch): Unit < Sync =
            def spin: Unit < Sync = Sync.defer(spin)
            Sync.ensure(done.release)(started.release.andThen(spin))

        def zioLoop(started: zio.Promise[Nothing, Unit], done: zio.Promise[Nothing, Unit]): Task[Unit] =
            def spin: Task[Unit] = ZIO.suspendSucceed(spin)
            started.succeed(()).zipRight(spin).ensuring(done.succeed(()))

        def zioSignal: zio.UIO[zio.Promise[Nothing, Unit]] = zio.Promise.make[Nothing, Unit]

        "runZIO" - {
            "zio to kyo" in runZIO {
                for
                    started <- ZIOs.run(Latch.init(1))
                    done    <- ZIOs.run(Latch.init(1))
                    f       <- ZIOs.run(kyoLoop(started, done)).fork
                    _       <- ZIOs.run(started.await)
                    _       <- f.interrupt
                    r       <- f.await
                    pending <- ZIOs.run(done.pending)
                yield assert(r.isInterrupted && pending == 0)
                end for
            }

            "both" in runZIO {
                for
                    zioStarted <- zioSignal
                    zioDone    <- zioSignal
                    kyoStarted <- ZIOs.run(Latch.init(1))
                    kyoDone    <- ZIOs.run(Latch.init(1))
                    f          <- ZIOs.run(ZIOs.get(zioLoop(zioStarted, zioDone)).andThen(kyoLoop(kyoStarted, kyoDone))).fork
                    _          <- zioStarted.await
                    _          <- f.interrupt
                    r          <- f.await
                    _          <- zioDone.await
                    pending    <- ZIOs.run(kyoStarted.pending)
                yield assert(r.isInterrupted && pending == 1)
                end for
            }

            "parallel loops" in runZIO {
                for
                    zioStarted <- zioSignal
                    zioDone    <- zioSignal
                    kyoStarted <- ZIOs.run(Latch.init(1))
                    kyoDone    <- ZIOs.run(Latch.init(1))
                    f          <- ZIOs.run(Async.zip[Throwable, Unit, Unit, Any](
                        ZIOs.get(zioLoop(zioStarted, zioDone)),
                        kyoLoop(kyoStarted, kyoDone)
                    )).fork
                    _ <- zioStarted.await
                    _ <- ZIOs.run(kyoStarted.await)
                    _ <- f.interrupt
                    r <- f.await
                    _ <- zioDone.await
                    _ <- ZIOs.run(kyoDone.await)
                yield assert(r.isInterrupted)
                end for
            }

            "race loops" in runZIO {
                for
                    zioStarted <- zioSignal
                    zioDone    <- zioSignal
                    kyoStarted <- ZIOs.run(Latch.init(1))
                    kyoDone    <- ZIOs.run(Latch.init(1))
                    f          <- ZIOs.run(Async.race[Throwable, Unit, Any](
                        ZIOs.get(zioLoop(zioStarted, zioDone)),
                        kyoLoop(kyoStarted, kyoDone)
                    )).fork
                    _ <- zioStarted.await
                    _ <- ZIOs.run(kyoStarted.await)
                    _ <- f.interrupt
                    r <- f.await
                    _ <- zioDone.await
                    _ <- ZIOs.run(kyoDone.await)
                yield assert(r.isInterrupted)
                end for
            }

            "interrupt racing acquisition never orphans the bridged kyo fiber" in runZIO {
                // Each iteration interrupts the bridge right after forking it, so the interrupt can land before
                // ZIOs.run wires its interruption path. A bridged kyo fiber completes only after its finalizers,
                // so once the ZIO fiber is awaited a spin that was entered must also have stopped. The interrupt
                // may stop the body before it enters, so only `entered` without `stopped` is an orphan; the
                // recheck once every iteration has run catches an orphan that only started after its own check.
                def spin: Unit < Sync = Sync.defer(spin)

                def orphaned(entered: AtomicBoolean, stopped: AtomicBoolean): zio.UIO[Boolean] =
                    ZIOs.run(stopped.get.map(s => entered.get.map(e => e && !s)))
                val iteration: Task[(Boolean, AtomicBoolean, AtomicBoolean)] =
                    for
                        entered <- ZIOs.run(AtomicBoolean.init)
                        stopped <- ZIOs.run(AtomicBoolean.init)
                        f       <- ZIOs.run(Sync.ensure(stopped.set(true))(entered.set(true).andThen(spin))).fork
                        _       <- f.interrupt
                        orphan  <- orphaned(entered, stopped)
                    yield (orphan, entered, stopped)
                for
                    runs <- ZIO.foreach(Chunk.from(1 to 400))(_ => iteration)
                    late <- ZIO.foreach(runs)((_, entered, stopped) => orphaned(entered, stopped))
                yield assert(runs.count(_._1) == 0 && late.count(identity) == 0)
                end for
            }
        }

        "runKyo" - {
            "kyo to zio" in runKyo {
                val panic = Result.Panic(new Exception)
                for
                    started <- ZIOs.get(zioSignal)
                    done    <- ZIOs.get(zioSignal)
                    f       <- Fiber.initUnscoped(ZIOs.get(zioLoop(started, done)))
                    _       <- ZIOs.get(started.await)
                    _       <- f.interrupt(panic)
                    r       <- f.getResult
                    _       <- ZIOs.get(done.await)
                yield assert(r == panic)
                end for
            }

            "both" in runKyo {
                for
                    zioStarted <- ZIOs.get(zioSignal)
                    zioDone    <- ZIOs.get(zioSignal)
                    kyoStarted <- Latch.init(1)
                    kyoDone    <- Latch.init(1)
                    f          <- Fiber.initUnscoped(ZIOs.get(zioLoop(zioStarted, zioDone)).andThen(kyoLoop(kyoStarted, kyoDone)))
                    _          <- ZIOs.get(zioStarted.await)
                    _          <- f.interrupt
                    r          <- f.getResult
                    _          <- ZIOs.get(zioDone.await)
                    pending    <- kyoStarted.pending
                yield assert(r.isPanic && pending == 1)
                end for
            }

            "parallel loops" in runKyo {
                for
                    zioStarted <- ZIOs.get(zioSignal)
                    zioDone    <- ZIOs.get(zioSignal)
                    kyoStarted <- Latch.init(1)
                    kyoDone    <- Latch.init(1)
                    f          <- Fiber.initUnscoped(Async.zip[Throwable, Unit, Unit, Any](
                        ZIOs.get(zioLoop(zioStarted, zioDone)),
                        kyoLoop(kyoStarted, kyoDone)
                    ))
                    _ <- ZIOs.get(zioStarted.await)
                    _ <- kyoStarted.await
                    _ <- f.interrupt
                    r <- f.getResult
                    _ <- ZIOs.get(zioDone.await)
                    _ <- kyoDone.await
                yield assert(r.isPanic)
                end for
            }

            "race loops" in runKyo {
                for
                    zioStarted <- ZIOs.get(zioSignal)
                    zioDone    <- ZIOs.get(zioSignal)
                    kyoStarted <- Latch.init(1)
                    kyoDone    <- Latch.init(1)
                    f          <- Fiber.initUnscoped(Async.race[Throwable, Unit, Any](
                        ZIOs.get(zioLoop(zioStarted, zioDone)),
                        kyoLoop(kyoStarted, kyoDone)
                    ))
                    _ <- ZIOs.get(zioStarted.await)
                    _ <- kyoStarted.await
                    _ <- f.interrupt
                    r <- f.getResult
                    _ <- ZIOs.get(zioDone.await)
                    _ <- kyoDone.await
                yield assert(r.isPanic)
                end for
            }
        }

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
            val nestedZIO: ZIO[Any, String, Int]         = ZIO.fail("Nested ZIO failed")
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
                case Result.Panic(_: Interrupted) => succeed("interrupt cause converts to a Panic wrapping Interrupted")
                case _                            => fail("Expected Result.Panic with Interrupted")
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

    "Result.Error toCause" - {
        "Failure to Cause.Fail" in {
            val error = Result.Failure("test error")
            assert(error.toCause == Cause.fail("test error"))
        }

        "Panic to Cause.Die" in {
            val exception = new RuntimeException("test exception")
            val error     = Result.Panic(exception)
            assert(error.toCause == Cause.die(exception))
        }
    }

    "Result toExit" - {
        "Success to Exit.Success" in {
            val result = Result.succeed(42)
            assert(result.toExit == Exit.succeed(42))
        }

        "Failure to Exit.Failure" in {
            val result = Result.fail("error")
            assert(result.toExit == Exit.fail("error"))
        }

        "Panic to Exit.Die" in {
            val exception = new RuntimeException("panic")
            val result    = Result.Panic(exception)
            assert(result.toExit == Exit.die(exception))
        }
    }

end ZIOsTest
