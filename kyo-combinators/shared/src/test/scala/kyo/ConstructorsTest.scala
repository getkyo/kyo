package kyo

import scala.util.Failure
import scala.util.Success

class ConstructorsTest extends Test:

    "Kyo constructors" - {
        "emit" - {
            "should emit a value" in run {
                val effect =
                    for
                        _ <- Kyo.emit(1)
                        _ <- Kyo.emit(2)
                        _ <- Kyo.emit(3)
                    yield "done"
                Emit.run(effect).map:
                    case (values, result) => assert(values == Chunk(1, 2, 3) && result == "done")
            }
        }

        "fail" - {
            "should create an effect that fails with Abort[E]" in {
                val effect = Kyo.fail("Error message")
                val result = Abort.run[String](effect).eval
                assert(result == Result.fail("Error message"))
            }
        }

        "fromEither" - {
            "should create an effect from Either[E, A]" in {
                val successEffect = Kyo.fromEither(Right(42))
                val failureEffect = Kyo.fromEither(Left("Error"))

                val successResult = Abort.run[String](successEffect).eval
                val failureResult = Abort.run[String](failureEffect).eval

                assert(successResult == Result.succeed(42))
                assert(failureResult == Result.fail("Error"))
            }
        }

        "fromOption" - {
            "should create an effect from Option[A]" in {
                val someEffect = Kyo.fromOption(Some(42))
                val noneEffect = Kyo.fromOption(None)

                val someResult = Abort.run[Absent](someEffect).eval
                val noneResult = Abort.run[Absent](noneEffect).eval

                assert(someResult == Result.succeed(42))
                assert(noneResult == Result.fail(Absent))
            }
        }

        "fromMaybe" - {
            "should create an effect from Maybe[A]" in {
                val definedEffect = Kyo.fromMaybe(Present(42))
                val emptyEffect   = Kyo.fromMaybe(Absent)

                val definedResult = Abort.run[Absent](definedEffect).eval
                val emptyResult   = Abort.run[Absent](emptyEffect).eval

                assert(definedResult == Result.succeed(42))
                assert(emptyResult == Result.fail(Absent))
            }
        }

        "fromResult" - {
            "should create an effect from Result[E, A]" in {
                val successEffect = Kyo.fromResult(Result.succeed(42))
                val failureEffect = Kyo.fromResult(Result.fail("Error"))

                val successResult = Abort.run[String](successEffect).eval
                val failureResult = Abort.run[String](failureEffect).eval

                assert(successResult == Result.succeed(42))
                assert(failureResult == Result.fail("Error"))
            }
        }

        "fromTry" - {
            "should create an effect from Try[A]" in {
                val successEffect = Kyo.fromTry(Success(42))
                val failureEffect = Kyo.fromTry(Failure(new Exception("Error")))

                val successResult = Abort.run[Throwable](successEffect).eval
                val failureResult = Abort.run[Throwable](failureEffect).eval

                assert(successResult == Result.succeed(42))
                assert(failureResult.isInstanceOf[Result.Failure[?]])
            }
        }

        "logInfo" - {
            "should log an informational message" in run {
                val effect = Kyo.logInfo("Info message")
                effect.map { result =>
                    assert(result == ())
                }
            }
        }

        "logWarn" - {
            "should log a warning message" in run {
                val effect = Kyo.logWarn("Warning message")
                effect.map { result =>
                    assert(result == ())
                }
            }
        }

        "logDebug" - {
            "should log a debug message" in run {
                val effect = Kyo.logDebug("Debug message")
                effect.map { result =>
                    assert(result == ())
                }
            }
        }

        "logError" - {
            "should log an error message" in run {
                val effect = Kyo.logError("Error message")
                effect.map { result =>
                    assert(result == ())
                }
            }
        }

        "logTrace" - {
            "should log a trace message" in run {
                val effect = Kyo.logTrace("Trace message")
                effect.map { result =>
                    assert(result == ())
                }
            }
        }

        "suspend" - {
            "should suspend an effect using Sync" in run {
                var executed = false
                val effect = Kyo.suspend {
                    executed = true
                    42
                }
                assert(!executed)
                effect.map { result =>
                    assert(executed)
                    assert(result == 42)
                }
            }
        }

        "suspendAttempt" - {
            "should suspend an effect and handle exceptions" in {
                import AllowUnsafe.embrace.danger
                val successEffect = Kyo.suspendAttempt(42)
                val failureEffect = Kyo.suspendAttempt(throw new Exception("Error"))

                val successResult = Sync.Unsafe.evalOrThrow(Abort.run[Throwable](successEffect))
                val failureResult = Sync.Unsafe.evalOrThrow(Abort.run[Throwable](failureEffect))

                assert(successResult == Result.succeed(42))
                assert(failureResult.isInstanceOf[Result.Failure[?]])
            }
        }

        "foreachPar" - {
            "should apply a function to each element in parallel" in run {
                val input  = Seq(1, 2, 3, 4, 5)
                val result = Kyo.foreachPar(input)(x => Async.sleep(1.millis).andThen(x * 2))
                result.map { r =>
                    assert(r == Seq(2, 4, 6, 8, 10))
                }
            }
            "should support context effects" in run {
                val input  = Seq(1, 2, 3, 4, 5)
                val result = Kyo.foreachPar(input)(x => Env.use[Int](d => Async.sleep(d.millis)).andThen(x * 2))
                Env.run(1) {
                    result.map { r =>
                        assert(r == Seq(2, 4, 6, 8, 10))
                    }
                }
            }
        }

        "foreachParDiscard" - {
            "should apply a function to each element in parallel and discard the results" in run {
                val input = Seq(1, 2, 3, 4, 5)
                AtomicInt.init(0).map { counter =>
                    Kyo.foreachParDiscard(input) { x =>
                        Async.sleep(1.millis).andThen(counter.incrementAndGet)
                    }.map { _ =>
                        counter.get.map { count =>
                            assert(count == 5)
                        }
                    }
                }
            }
            "should support context effects" in run {
                val input = Seq(1, 2, 3, 4, 5)
                Env.run(1) {
                    AtomicInt.init(0).map { counter =>
                        Kyo.foreachParDiscard(input) { x =>
                            Env.use[Int](d => Async.sleep(d.millis)).andThen(counter.incrementAndGet)
                        }.map { _ =>
                            counter.get.map { count =>
                                assert(count == 5)
                            }
                        }
                    }
                }
            }
        }
    }

end ConstructorsTest
